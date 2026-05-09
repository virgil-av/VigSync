import json
import time
import os
import subprocess
import paho.mqtt.client as mqtt

# --- CONFIGURATION ---
CONFIG_FILE = "vigsync_server_config.json"
# The path inside the phone where the app saves the config
ADB_PHONE_PATH = "/storage/emulated/0/Android/data/com.vigsync/files/Documents/"

def pull_config_via_adb():
    """Attempts to pull the config file from the connected phone."""
    print(f"[*] Searching for {CONFIG_FILE} on connected ADB device...")
    phone_config_path = f"{ADB_PHONE_PATH}{CONFIG_FILE}"
    try:
        result = subprocess.run(["adb", "shell", f"cat {phone_config_path}"], capture_output=True, text=True, encoding='utf-8')
        if result.returncode == 0 and result.stdout.strip():
            with open(CONFIG_FILE, "w", encoding='utf-8') as f:
                f.write(result.stdout)
            print(f"[+] Successfully retrieved {CONFIG_FILE} from phone.")
            return True
        else:
            # Fallback to adb pull
            result = subprocess.run(["adb", "pull", phone_config_path, "."], capture_output=True, text=True)
            if result.returncode == 0:
                print(f"[+] Successfully pulled {CONFIG_FILE} from phone via fallback.")
                return True
            else:
                print(f"[!] ADB Pull failed: {result.stderr.strip()}")
                return False
    except Exception as e:
        print(f"[!] Error retrieving config: {e}")
        return False

def load_config():
    if not os.path.exists(CONFIG_FILE):
        if not pull_config_via_adb():
            print(f"[!] Config file {CONFIG_FILE} not found locally or on phone.")
            exit(1)

    with open(CONFIG_FILE, "r", encoding='utf-8') as f:
        return json.load(f)

def on_connect(client, userdata, flags, rc):
    if rc == 0:
        print("[*] Connected to MQTT Broker successfully.")
    else:
        print(f"[!] Failed to connect, return code {rc}")

def run_adb_tail_stream(config, mqtt_client):
    """Uses ADB shell to stream the log file in real-time."""
    log_path = config.get("export_file_path")
    prefix = config.get("topic_prefix", "vigsync")
    device_id = config.get("device_id")

    print(f"[*] Starting real-time stream for: {log_path}")
    print(f"[*] Base topic: {prefix}/[events|status]/{device_id}")

    # Use -n 0 to only get NEW entries
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
            line = process.stdout.readline()
            if not line:
                if process.poll() is not None:
                    print("[!] ADB stream process died. Retrying in 5s...")
                    time.sleep(5)
                    return
                continue

            line = line.strip()
            if line and "|" in line:
                # Format: topic_suffix|encrypted_payload
                parts = line.split("|", 1)
                if len(parts) == 2:
                    suffix, payload = parts
                    full_topic = f"{prefix}/{suffix}/{device_id}"
                    mqtt_client.publish(full_topic, payload, qos=1)
                    print(f"[+] Published to {full_topic}: {payload[:40]}...")

    except KeyboardInterrupt:
        process.terminate()
        print("\n[*] Stopping stream...")
    except Exception as e:
        print(f"[!] Streaming error: {e}")
        time.sleep(2)

def run():
    config = load_config()

    # MQTT Setup
    client = mqtt.Client()
    if config.get("username") and config.get("password"):
        client.username_pw_set(config["username"], config["password"])

    client.on_connect = on_connect

    try:
        client.connect(config["broker_url"], config["broker_port"], 60)
    except Exception as e:
        print(f"[!] Could not connect to broker: {e}")
        exit(1)

    client.loop_start()

    while True:
        try:
            run_adb_tail_stream(config, client)
        except Exception:
            time.sleep(5)

if __name__ == "__main__":
    print("=== VigSync RPi/PC ADB Consumer (Compatibility Mode) ===")
    run()
