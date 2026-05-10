import json
import time
import os
import subprocess
import paho.mqtt.client as mqtt
import sys
import signal
from datetime import datetime

# --- CONFIGURATION ---
CONFIG_FILE = "vigsync_server_config.json"
LOG_FILE = "vigsync_consumer.log"
PID_FILE = "vigsync_consumer.pid"
# The path inside the phone where the app saves the config
ADB_PHONE_PATH = "/storage/emulated/0/Android/data/com.vigsync/files/Documents/"

# --- MEMORY LOGGING ---
memory_logs = []
MAX_MEM_LOGS = 1000
last_flush_time = time.time()
FLUSH_INTERVAL = 6 * 3600 # 6 hours

def log_mem(message, level="INFO"):
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    entry = f"[{timestamp}] [{level}] {message}"
    
    # 1. Print to stdout for real-time viewing
    print(entry)
    sys.stdout.flush()

    # 2. Add to memory buffer
    memory_logs.append(entry)
    if len(memory_logs) > MAX_MEM_LOGS:
        memory_logs.pop(0)

    # Immediate flush on ERROR
    if level == "ERROR":
        flush_logs()

def flush_logs():
    global last_flush_time
    if not memory_logs:
        return
    try:
        with open(LOG_FILE, "a", encoding='utf-8') as f:
            for entry in memory_logs:
                f.write(entry + "\n")
        memory_logs.clear()
        last_flush_time = time.time()
    except Exception as e:
        sys.stderr.write(f"Failed to flush logs: {e}\n")

# --- MQTT CALLBACKS ---

def on_connect(client, userdata, flags, rc):
    rc_map = {
        0: "Connection successful",
        1: "Connection refused - incorrect protocol version",
        2: "Connection refused - invalid client identifier",
        3: "Connection refused - server unavailable",
        4: "Connection refused - bad username or password",
        5: "Connection refused - not authorised"
    }
    msg = rc_map.get(rc, f"Unknown error (code: {rc})")
    if rc == 0:
        log_mem(f"MQTT Connected: {msg}")
    else:
        log_mem(f"MQTT Connection Failed: {msg}", "ERROR")

def on_disconnect(client, userdata, rc):
    if rc != 0:
        log_mem(f"Unexpected MQTT disconnection (code: {rc}).", "WARNING")
    else:
        log_mem("MQTT Disconnected safely.")

def on_publish(client, userdata, mid):
    # This callback is called when the broker confirms receipt (PUBACK)
    log_mem(f"Delivery Confirmed (msg_id: {mid})", "TRACE")

def on_log(client, userdata, level, buf):
    # Low-level Paho logs for deep debugging
    if "sending" in buf.lower() or "received" in buf.lower() or "error" in buf.lower():
        log_mem(f"Paho Internal: {buf}", "DEBUG")

# --- CORE LOGIC ---

def pull_config_via_adb():
    """Forces a fresh config pull from the connected phone."""
    log_mem(f"Pulling fresh configuration from ADB device...")
    phone_config_path = f"{ADB_PHONE_PATH}{CONFIG_FILE}"
    
    try:
        result = subprocess.run(["adb", "shell", f"cat {phone_config_path}"], capture_output=True, text=True, encoding='utf-8')
        if result.returncode == 0 and result.stdout.strip():
            with open(CONFIG_FILE, "w", encoding='utf-8') as f:
                f.write(result.stdout)
            
            try:
                data = json.loads(result.stdout)
                device = data.get("device_name", "Unknown")
                log_mem(f"Successfully retrieved config for device: {device}")
                return True
            except:
                log_mem("Retrieved config but JSON is invalid.", "WARNING")
                return True 
                
        result = subprocess.run(["adb", "pull", phone_config_path, "."], capture_output=True, text=True)
        if result.returncode == 0:
            log_mem("Successfully pulled configuration via ADB pull.")
            return True
        else:
            log_mem(f"ADB Pull failed: {result.stderr.strip()}", "WARNING")
            return False
            
    except Exception as e:
        log_mem(f"Error pulling configuration: {e}", "ERROR")
        return False

def load_config():
    pull_config_via_adb()
    if not os.path.exists(CONFIG_FILE):
        log_mem(f"Config file {CONFIG_FILE} not found. Please ensure the app is running.", "ERROR")
        sys.exit(1)

    with open(CONFIG_FILE, "r", encoding='utf-8') as f:
        return json.load(f)

def run_adb_tail_stream(config, mqtt_client):
    log_path = config.get("export_file_path")
    prefix = config.get("topic_prefix", "vigsync")
    device_id = config.get("device_id")
    device_name = config.get("device_name", "Unknown")

    log_mem(f"Monitoring device: {device_name} ({device_id})")
    log_mem(f"Targeting Topic: {prefix}/[events|status]/{device_id}")

    adb_command = ["adb", "shell", f"tail -n 0 -f {log_path}"]

    try:
        process = subprocess.Popen(
            adb_command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding='utf-8',
            errors='replace',
            bufsize=1
        )

        while True:
            if time.time() - last_flush_time > FLUSH_INTERVAL:
                flush_logs()

            line = process.stdout.readline()
            if not line:
                if process.poll() is not None:
                    log_mem("ADB stream process died. Check USB connection.", "ERROR")
                    time.sleep(5)
                    return
                continue

            line = line.strip()
            if line and "|" in line:
                parts = line.split("|", 1)
                if len(parts) == 2:
                    suffix, payload = parts
                    full_topic = f"{prefix}/{suffix}/{device_id}"
                    
                    # Publish and capture the info object
                    info = mqtt_client.publish(full_topic, payload, qos=1)
                    
                    # Log that it was queued
                    log_mem(f"Queued for {full_topic} (msg_id: {info.mid})")
                    
                    if not mqtt_client.is_connected():
                        log_mem("Warning: Client is currently DISCONNECTED. Message will be queued locally.", "WARNING")

    except Exception as e:
        log_mem(f"Streaming error: {e}", "ERROR")
        time.sleep(2)

def daemonize():
    if os.path.exists(PID_FILE):
        try:
            with open(PID_FILE, "r") as f:
                pid = int(f.read().strip())
            os.kill(pid, 0)
            log_mem(f"Consumer already running (PID: {pid}).", "ERROR")
            sys.exit(1)
        except (ProcessLookupError, ValueError, OSError):
            os.remove(PID_FILE)
    
    with open(PID_FILE, "w") as f:
        f.write(str(os.getpid()))
    
    def signal_handler(sig, frame):
        log_mem("Shutdown signal received.")
        flush_logs()
        if os.path.exists(PID_FILE):
            os.remove(PID_FILE)
        sys.exit(0)
    
    signal.signal(signal.SIGTERM, signal_handler)
    signal.signal(signal.SIGINT, signal_handler)

def run():
    daemonize()
    config = load_config()

    client = mqtt.Client(client_id=f"vigsync_pi_{config.get('device_id')[-8:]}", clean_session=False)
    
    if config.get("username") and config.get("password"):
        client.username_pw_set(config["username"], config["password"])

    if config.get("use_tls", False):
        try:
            client.tls_set()
            log_mem("SSL/TLS Enabled for MQTT.")
        except Exception as e:
            log_mem(f"Failed to enable TLS: {e}", "ERROR")

    # Set callbacks
    client.on_connect = on_connect
    client.on_disconnect = on_disconnect
    client.on_publish = on_publish
    client.on_log = on_log

    try:
        log_mem(f"Connecting to {config['broker_url']}:{config['broker_port']}...")
        client.connect(config["broker_url"], config["broker_port"], 60)
    except Exception as e:
        log_mem(f"CRITICAL: Could not initiate connection: {e}", "ERROR")
        sys.exit(1)

    client.loop_start()

    while True:
        try:
            run_adb_tail_stream(config, client)
        except Exception as e:
            log_mem(f"Main loop error: {e}", "ERROR")
            time.sleep(5)

if __name__ == "__main__":
    if "--background" in sys.argv:
        run()
    else:
        run()
