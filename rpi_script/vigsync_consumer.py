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
    
    # 1. Print to stdout for real-time viewing (captured by systemd or manager)
    print(entry)
    sys.stdout.flush()

    # 2. Add to memory buffer
    memory_logs.append(entry)

    # Keep memory usage bounded
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
        # Fallback to stderr if SD card is read-only or failing
        sys.stderr.write(f"Failed to flush logs: {e}\n")

def pull_config_via_adb():
    log_mem(f"Searching for {CONFIG_FILE} on connected ADB device...")
    phone_config_path = f"{ADB_PHONE_PATH}{CONFIG_FILE}"
    try:
        result = subprocess.run(["adb", "shell", f"cat {phone_config_path}"], capture_output=True, text=True, encoding='utf-8')
        if result.returncode == 0 and result.stdout.strip():
            with open(CONFIG_FILE, "w", encoding='utf-8') as f:
                f.write(result.stdout)
            log_mem(f"Successfully retrieved {CONFIG_FILE} from phone.")
            return True
        else:
            result = subprocess.run(["adb", "pull", phone_config_path, "."], capture_output=True, text=True)
            if result.returncode == 0:
                log_mem(f"Successfully pulled {CONFIG_FILE} from phone via fallback.")
                return True
            else:
                log_mem(f"ADB Pull failed: {result.stderr.strip()}", "ERROR")
                return False
    except Exception as e:
        log_mem(f"Error retrieving config: {e}", "ERROR")
        return False

def load_config():
    if not os.path.exists(CONFIG_FILE):
        if not pull_config_via_adb():
            log_mem(f"Config file {CONFIG_FILE} not found locally or on phone.", "ERROR")
            sys.exit(1)

    with open(CONFIG_FILE, "r", encoding='utf-8') as f:
        return json.load(f)

def on_connect(client, userdata, flags, rc):
    if rc == 0:
        log_mem("Connected to MQTT Broker successfully.")
    else:
        log_mem(f"Failed to connect, return code {rc}", "ERROR")

def run_adb_tail_stream(config, mqtt_client):
    log_path = config.get("export_file_path")
    prefix = config.get("topic_prefix", "vigsync")
    device_id = config.get("device_id")

    log_mem(f"Starting real-time stream for: {log_path}")

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
            # Check if we need to flush logs (6 hours)
            if time.time() - last_flush_time > FLUSH_INTERVAL:
                flush_logs()

            line = process.stdout.readline()
            if not line:
                if process.poll() is not None:
                    log_mem("ADB stream process died. Retrying in 5s...", "ERROR")
                    time.sleep(5)
                    return
                continue

            line = line.strip()
            if line and "|" in line:
                parts = line.split("|", 1)
                if len(parts) == 2:
                    suffix, payload = parts
                    full_topic = f"{prefix}/{suffix}/{device_id}"
                    mqtt_client.publish(full_topic, payload, qos=1)
                    log_mem(f"Published to {full_topic}")

    except Exception as e:
        log_mem(f"Streaming error: {e}", "ERROR")
        time.sleep(2)

def daemonize():
    """Simple background execution without full double-fork for PC/RPi compatibility."""
    if os.path.exists(PID_FILE):
        log_mem("Consumer already running (PID file exists).", "ERROR")
        sys.exit(1)

    # Save PID
    with open(PID_FILE, "w") as f:
        f.write(str(os.getpid()))

    # Clean exit handler
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

    client = mqtt.Client()
    if config.get("username") and config.get("password"):
        client.username_pw_set(config["username"], config["password"])

    client.on_connect = on_connect

    try:
        client.connect(config["broker_url"], config["broker_port"], 60)
    except Exception as e:
        log_mem(f"Could not connect to broker: {e}", "ERROR")
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
