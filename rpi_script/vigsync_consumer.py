import json
import time
import os
import subprocess
import paho.mqtt.client as mqtt
import sys
import signal
import select
import hashlib
import shlex
from dataclasses import dataclass
from datetime import datetime

# --- CONFIGURATION ---
CONFIG_FILE = os.environ.get("VIGSYNC_CONFIG_FILE", "vigsync_server_config.json")
LOG_FILE = os.environ.get("VIGSYNC_LOG_FILE", "vigsync_consumer.log")
PID_FILE = os.environ.get("VIGSYNC_PID_FILE", "vigsync_consumer.pid")
SPOOL_FILE = os.environ.get("VIGSYNC_SPOOL_FILE", "vigsync_spool.jsonl")
STATE_FILE = os.environ.get("VIGSYNC_STATE_FILE", "vigsync_consumer_state.json")
# The path inside the phone where the app saves the config
ADB_PHONE_PATH = os.environ.get("VIGSYNC_ADB_PHONE_PATH", "/storage/emulated/0/Android/data/com.vigsync/files/Documents/")

# --- WATCHDOG ---
STREAM_TIMEOUT = 180 # 3 minutes. (App heartbeats every 60s, so 180s is safe)
RECENT_REPLAY_LINES = int(os.environ.get("VIGSYNC_RECENT_REPLAY_LINES", "200"))
RECONNECT_DELAY = int(os.environ.get("VIGSYNC_RECONNECT_DELAY", "5"))
STREAM_HEALTH_CHECK_INTERVAL = int(os.environ.get("VIGSYNC_STREAM_HEALTH_CHECK_INTERVAL", "10"))

# --- MEMORY LOGGING ---
memory_logs = []
MAX_MEM_LOGS = 1000
last_flush_time = time.time()
FLUSH_INTERVAL = 6 * 3600 # 6 hours
seen_fingerprints = []
seen_fingerprint_set = set()
MAX_SEEN_FINGERPRINTS = 2000
_loaded_state_file = None
pending_publish_by_mid = {}
pending_spool_ids = set()

@dataclass(frozen=True)
class RemoteFileSnapshot:
    identity: str
    size: int

def log_mem(message, level="INFO"):
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    entry = f"[{timestamp}] [{level}] {message}"
    
    print(entry)
    sys.stdout.flush()

    memory_logs.append(entry)
    if len(memory_logs) > MAX_MEM_LOGS:
        memory_logs.pop(0)

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

def make_fingerprint(topic, payload):
    return hashlib.sha256(f"{topic}\0{payload}".encode("utf-8", errors="replace")).hexdigest()

def ensure_state_loaded():
    global _loaded_state_file
    if _loaded_state_file == STATE_FILE:
        return

    seen_fingerprints.clear()
    seen_fingerprint_set.clear()
    _loaded_state_file = STATE_FILE

    if not os.path.exists(STATE_FILE):
        return

    try:
        with open(STATE_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        for fingerprint in data.get("seen_fingerprints", [])[-MAX_SEEN_FINGERPRINTS:]:
            if isinstance(fingerprint, str) and fingerprint not in seen_fingerprint_set:
                seen_fingerprint_set.add(fingerprint)
                seen_fingerprints.append(fingerprint)
    except Exception as e:
        log_mem(f"Failed to load consumer state; starting with empty replay cache: {e}", "WARNING")

def save_seen_fingerprints():
    try:
        with open(STATE_FILE, "w", encoding="utf-8") as f:
            json.dump({"seen_fingerprints": seen_fingerprints[-MAX_SEEN_FINGERPRINTS:]}, f)
    except Exception as e:
        log_mem(f"Failed to save consumer state: {e}", "ERROR")

def has_fingerprint(topic, payload):
    ensure_state_loaded()
    return make_fingerprint(topic, payload) in seen_fingerprint_set

def remember_fingerprint(topic, payload):
    ensure_state_loaded()
    fingerprint = hashlib.sha256(f"{topic}\0{payload}".encode("utf-8", errors="replace")).hexdigest()
    if fingerprint in seen_fingerprint_set:
        return False
    seen_fingerprint_set.add(fingerprint)
    seen_fingerprints.append(fingerprint)
    if len(seen_fingerprints) > MAX_SEEN_FINGERPRINTS:
        old = seen_fingerprints.pop(0)
        seen_fingerprint_set.discard(old)
    save_seen_fingerprints()
    return True

def read_spool_entries():
    if not os.path.exists(SPOOL_FILE):
        return []

    entries = []
    try:
        with open(SPOOL_FILE, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    item = json.loads(line)
                    topic = item["topic"]
                    payload = item["payload"]
                    fingerprint = item.get("fingerprint") or make_fingerprint(topic, payload)
                    entries.append({
                        "id": item.get("id") or fingerprint,
                        "fingerprint": fingerprint,
                        "topic": topic,
                        "payload": payload,
                        "created_at": item.get("created_at") or datetime.now().isoformat(),
                        "attempts": int(item.get("attempts", 0)),
                    })
                except Exception as e:
                    log_mem(f"Dropping invalid spool line: {e}", "ERROR")
    except Exception as e:
        log_mem(f"Failed to read spool: {e}", "ERROR")
    return entries

def write_spool_entries(entries):
    if not entries:
        if os.path.exists(SPOOL_FILE):
            os.remove(SPOOL_FILE)
        return

    with open(SPOOL_FILE, "w", encoding="utf-8") as f:
        for entry in entries:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")

def append_spool(topic, payload, fingerprint=None):
    fingerprint = fingerprint or make_fingerprint(topic, payload)
    entry_id = fingerprint
    entries = read_spool_entries()
    if any(entry["id"] == entry_id for entry in entries):
        return entry_id

    entry = {
        "id": entry_id,
        "fingerprint": fingerprint,
        "topic": topic,
        "payload": payload,
        "created_at": datetime.now().isoformat(),
        "attempts": 0,
    }
    try:
        with open(SPOOL_FILE, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
        log_mem(f"Spooling message for later delivery: {topic}", "WARNING")
        return entry_id
    except Exception as e:
        log_mem(f"Failed to spool message for {topic}: {e}", "ERROR")
        return None

def mark_spool_pending(entry, mid):
    pending_publish_by_mid[mid] = entry["id"]
    pending_spool_ids.add(entry["id"])

def mark_spool_acknowledged(spool_id):
    entries = [entry for entry in read_spool_entries() if entry["id"] != spool_id]
    write_spool_entries(entries)
    pending_spool_ids.discard(spool_id)

def flush_spool(client):
    if not os.path.exists(SPOOL_FILE) or not client.is_connected():
        return

    entries = read_spool_entries()
    if not entries:
        write_spool_entries([])
        return

    queued = 0
    changed = False
    try:
        for entry in entries:
            if entry["id"] in pending_spool_ids:
                continue
            info = client.publish(entry["topic"], entry["payload"], qos=1)
            entry["attempts"] = entry.get("attempts", 0) + 1
            changed = True
            if info.rc == mqtt.MQTT_ERR_SUCCESS:
                mark_spool_pending(entry, info.mid)
                queued += 1
            else:
                log_mem(f"MQTT refused queued publish for {entry['topic']} (rc={info.rc}).", "WARNING")

        if changed:
            write_spool_entries(entries)

        if queued:
            log_mem(f"Queued {queued} spooled messages for MQTT acknowledgement.")
    except Exception as e:
        log_mem(f"Failed to flush spool: {e}", "ERROR")

def publish_or_spool(client, topic, payload):
    try:
        json.loads(payload)
    except Exception as e:
        log_mem(f"Skipping malformed payload for {topic}: {e}", "ERROR")
        return

    if has_fingerprint(topic, payload):
        log_mem(f"Skipping duplicate replay for {topic}", "TRACE")
        return

    fingerprint = make_fingerprint(topic, payload)
    spool_id = append_spool(topic, payload, fingerprint)
    if not spool_id:
        return
    remember_fingerprint(topic, payload)

    if not client.is_connected():
        return

    info = client.publish(topic, payload, qos=1)
    if info.rc == mqtt.MQTT_ERR_SUCCESS:
        mark_spool_pending({"id": spool_id}, info.mid)
        log_mem(f"Queued for {topic} (msg_id: {info.mid})")
    else:
        log_mem(f"MQTT refused publish for {topic} (rc={info.rc}); keeping spooled.", "WARNING")

def process_stream_line(line, prefix, device_id, mqtt_client):
    line = line.strip()
    if line and "|" in line:
        parts = line.split("|", 1)
        if len(parts) == 2:
            suffix, payload = parts
            full_topic = f"{prefix}/{suffix}/{device_id}"
            publish_or_spool(mqtt_client, full_topic, payload)
            return True
    elif line:
        log_mem(f"Skipping malformed stream line: {line[:120]}", "WARNING")
    return False

def parse_remote_file_snapshot(output):
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if not lines or lines[0] == "MISSING":
        return None

    for line in lines:
        if not line.startswith("STAT:"):
            continue
        parts = line[5:].split(":")
        if len(parts) >= 3:
            try:
                return RemoteFileSnapshot(identity=f"stat:{parts[0]}:{parts[1]}", size=int(parts[2]))
            except ValueError:
                break

    size = None
    for line in lines:
        if line.startswith("SIZE:"):
            try:
                size = int(line[5:])
            except ValueError:
                size = None
            break

    if size is None:
        return None
    return RemoteFileSnapshot(identity="fallback", size=size)

def get_remote_file_snapshot(log_path):
    quoted_path = shlex.quote(log_path)
    command = (
        f"if [ -e {quoted_path} ]; then "
        f"(stat -c 'STAT:%d:%i:%s:%Y' {quoted_path} 2>/dev/null) || "
        f"(bytes=$(wc -c < {quoted_path} 2>/dev/null | tr -d ' '); echo SIZE:$bytes); "
        f"else echo MISSING; fi"
    )
    try:
        result = subprocess.run(
            ["adb", "shell", command],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=10,
        )
    except Exception as e:
        log_mem(f"Failed to inspect remote export file: {e}", "WARNING")
        return None

    if result.returncode != 0:
        log_mem(f"Remote export file inspection failed: {result.stderr.strip()}", "WARNING")
        return None
    return parse_remote_file_snapshot(result.stdout)

def evaluate_stream_health(previous_snapshot, current_snapshot):
    if current_snapshot is None:
        return True, "remote export file is missing or unreadable"
    if previous_snapshot is None:
        return False, None
    if current_snapshot.identity != previous_snapshot.identity:
        return True, "remote export file was recreated"
    if current_snapshot.size < previous_snapshot.size:
        return True, "remote export file was truncated"
    return False, None

def stop_tail_process(process):
    if not process:
        return
    try:
        process.terminate()
        process.wait(timeout=5)
    except Exception:
        try:
            process.kill()
        except Exception:
            pass

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
        flush_spool(client)
    else:
        log_mem(f"MQTT Connection Failed: {msg}", "ERROR")

def on_disconnect(client, userdata, rc):
    if rc != 0:
        log_mem(f"Unexpected MQTT disconnection (code: {rc}).", "WARNING")
    else:
        log_mem("MQTT Disconnected safely.")

def on_publish(client, userdata, mid):
    spool_id = pending_publish_by_mid.pop(mid, None)
    if spool_id:
        mark_spool_acknowledged(spool_id)
    log_mem(f"Delivery Confirmed (msg_id: {mid})", "TRACE")

def on_log(client, userdata, level, buf):
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
            try:
                data = json.loads(result.stdout)
                device = data.get("device_name", "Unknown")
                with open(CONFIG_FILE, "w", encoding='utf-8') as f:
                    f.write(result.stdout)
                log_mem(f"Successfully retrieved config for device: {device}")
                return True
            except:
                log_mem("Retrieved config but JSON is invalid.", "WARNING")
                return False
                
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
    pulled = pull_config_via_adb()
    if not os.path.exists(CONFIG_FILE):
        log_mem(f"Config file {CONFIG_FILE} not found.", "ERROR")
        sys.exit(1)
    if not pulled:
        log_mem("Using last local configuration because ADB config pull failed.", "WARNING")

    with open(CONFIG_FILE, "r", encoding='utf-8') as f:
        return json.load(f)

def run_adb_tail_stream(config, mqtt_client):
    log_path = config.get("export_file_path")
    prefix = config.get("topic_prefix", "vigsync")
    device_id = config.get("device_id")
    device_name = config.get("device_name", "Unknown")

    if not log_path:
        log_mem("Config has no export_file_path; cannot start ADB tail.", "ERROR")
        time.sleep(RECONNECT_DELAY)
        return

    log_mem(f"Monitoring device: {device_name} ({device_id})")
    log_mem(f"Stream Source: {log_path}")

    adb_command = ["adb", "shell", f"tail -n {RECENT_REPLAY_LINES} -f {shlex.quote(log_path)}"]
    process = None
    file_snapshot = get_remote_file_snapshot(log_path)
    last_data_at = time.monotonic()
    next_health_check_at = time.monotonic() + STREAM_HEALTH_CHECK_INTERVAL

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

            if process.poll() is not None:
                log_mem("ADB stream process died. Check USB connection.", "ERROR")
                time.sleep(5)
                return

            now = time.monotonic()
            stall_remaining = max(0.0, STREAM_TIMEOUT - (now - last_data_at))
            health_remaining = max(0.0, next_health_check_at - now)
            wait_for = min(stall_remaining, health_remaining)

            ready, _, _ = select.select([process.stdout], [], [], wait_for)
            
            if ready:
                line = process.stdout.readline()
                if not line:
                    if process.poll() is not None:
                        log_mem("ADB stream process died. Check USB connection.", "ERROR")
                        time.sleep(5)
                        return
                    continue

                last_data_at = time.monotonic()
                process_stream_line(line, prefix, device_id, mqtt_client)
                continue

            now = time.monotonic()
            if now - last_data_at >= STREAM_TIMEOUT:
                log_mem(f"ADB stream stalled (no data for {STREAM_TIMEOUT}s). Heartbeat missed. Restarting...", "WARNING")
                stop_tail_process(process)
                return 

            if now >= next_health_check_at:
                current_snapshot = get_remote_file_snapshot(log_path)
                should_restart, reason = evaluate_stream_health(file_snapshot, current_snapshot)
                if should_restart:
                    log_mem(f"ADB stream source changed ({reason}). Restarting tail.", "WARNING")
                    stop_tail_process(process)
                    return
                if current_snapshot is not None:
                    file_snapshot = current_snapshot
                next_health_check_at = now + STREAM_HEALTH_CHECK_INTERVAL

    except Exception as e:
        log_mem(f"Streaming error: {e}", "ERROR")
        time.sleep(2)
        stop_tail_process(process)

def daemonize():
    if os.path.exists(PID_FILE):
        try:
            with open(PID_FILE, "r") as f:
                pid = int(f.read().strip())
            os.kill(pid, 0)
            log_mem(f"Consumer already running (PID: {pid}).", "ERROR")
            sys.exit(1)
        except:
            if os.path.exists(PID_FILE):
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

    device_id = str(config.get("device_id") or "unknown_device")
    client = mqtt.Client(client_id=f"vigsync_pi_{device_id[-8:]}", clean_session=False)
    client.reconnect_delay_set(min_delay=1, max_delay=30)
    client.max_inflight_messages_set(20)
    client.max_queued_messages_set(1000)
    
    if config.get("username") and config.get("password"):
        client.username_pw_set(config["username"], config["password"])

    if config.get("use_tls", False):
        try:
            client.tls_set()
            log_mem("SSL/TLS Enabled for MQTT.")
        except Exception as e:
            log_mem(f"Failed to enable TLS: {e}", "ERROR")

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
            config = load_config()
            run_adb_tail_stream(config, client)
        except Exception as e:
            log_mem(f"Main loop error: {e}", "ERROR")
            time.sleep(RECONNECT_DELAY)

if __name__ == "__main__":
    if "--background" in sys.argv:
        run()
    else:
        run()
