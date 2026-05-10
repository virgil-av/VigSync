import os
import subprocess
import signal
import time
import sys

# --- CONFIGURATION ---
PID_FILE = "vigsync_consumer.pid"
CONSUMER_SCRIPT = "vigsync_consumer.py"
LOG_FILE = "vigsync_consumer.log"
WORKING_DIR = "/home/valahus/vigsync"
SERVICE_NAME = "vigsync.service"

def get_pid():
    if os.path.exists(PID_FILE):
        with open(PID_FILE, "r") as f:
            try:
                return int(f.read().strip())
            except ValueError:
                return None
    return None

def is_running():
    # Check if process is running via PID file
    pid = get_pid()
    if pid:
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False

    # Fallback: check if systemd service is active
    try:
        result = subprocess.run(["systemctl", "is-active", "--quiet", SERVICE_NAME])
        return result.returncode == 0
    except:
        return False

def start_consumer():
    if is_running():
        print("[!] Consumer is already running.")
        return

    # Check if systemd service exists and use it if it does
    if os.path.exists(f"/etc/systemd/system/{SERVICE_NAME}"):
        print("[*] Starting via systemd...")
        os.system(f"sudo systemctl start {SERVICE_NAME}")
    else:
        print("[*] Starting VigSync Consumer in background...")
        cmd = f"nohup {sys.executable} {os.path.join(WORKING_DIR, CONSUMER_SCRIPT)} --background > /dev/null 2>&1 &"
        os.system(cmd)

    time.sleep(1)
    if is_running():
        print("[+] Started successfully.")
    else:
        print("[!] Start failed. Check logs.")

def stop_consumer():
    # Try systemd first
    if os.path.exists(f"/etc/systemd/system/{SERVICE_NAME}"):
        print("[*] Stopping via systemd...")
        os.system(f"sudo systemctl stop {SERVICE_NAME}")

    pid = get_pid()
    if pid:
        print(f"[*] Killing process (PID: {pid})...")
        try:
            os.kill(pid, signal.SIGTERM)
            for _ in range(5):
                if not is_running(): break
                time.sleep(1)
            if os.path.exists(PID_FILE):
                os.remove(PID_FILE)
        except: pass

    print("[+] Stopped.")

def setup_autostart():
    print("[*] Setting up systemd service for auto-start on reboot...")

    service_content = f"""[Unit]
Description=VigSync MQTT Consumer Service
After=network.target

[Service]
ExecStart={sys.executable} {os.path.join(WORKING_DIR, CONSUMER_SCRIPT)}
WorkingDirectory={WORKING_DIR}
StandardOutput=inherit
StandardError=inherit
Restart=always
User=valahus

[Install]
WantedBy=multi-user.target
"""

    try:
        # Write temporary file
        temp_file = "/tmp/vigsync.service"
        with open(temp_file, "w") as f:
            f.write(service_content)

        # Move to systemd directory and set permissions
        print("[*] Installing service file...")
        os.system(f"sudo mv {temp_file} /etc/systemd/system/{SERVICE_NAME}")
        os.system(f"sudo chown root:root /etc/systemd/system/{SERVICE_NAME}")
        os.system(f"sudo chmod 644 /etc/systemd/system/{SERVICE_NAME}")

        # Reload and enable
        print("[*] Enabling service...")
        os.system("sudo systemctl daemon-reload")
        os.system(f"sudo systemctl enable {SERVICE_NAME}")

        print("[+] Setup complete! VigSync will now start automatically on reboot.")
        print("[+] You can now use option 1 to start it via the new service.")
    except Exception as e:
        print(f"[!] Setup failed: {e}")

def view_logs():
    full_log_path = os.path.join(WORKING_DIR, LOG_FILE)
    if os.path.exists(full_log_path):
        print(f"--- Tail of {LOG_FILE} ---")
        subprocess.run(["tail", "-n", "20", full_log_path])
        print("---------------------------")
    else:
        print("[!] No log file found yet (flushes every 6h or on error).")

def menu():
    while True:
        status = "RUNNING" if is_running() else "STOPPED"
        is_installed = os.path.exists(f"/etc/systemd/system/{SERVICE_NAME}")
        install_status = "[Auto-start: ENABLED]" if is_installed else "[Auto-start: NOT SETUP]"

        print(f"\n=== VigSync RPi Manager ===")
        print(f"Status: {status} {install_status}")
        print("1. Start Consumer")
        print("2. Stop Consumer")
        print("3. Restart Consumer")
        print("4. View SD Card Logs")
        print("5. Setup Auto-start on Reboot (systemd)")
        print("6. Exit")

        choice = input("\nChoice: ").strip()

        if choice == "1":
            start_consumer()
        elif choice == "2":
            stop_consumer()
        elif choice == "3":
            stop_consumer()
            start_consumer()
        elif choice == "4":
            view_logs()
        elif choice == "5":
            setup_autostart()
        elif choice == "6":
            break
        else:
            print("[!] Invalid choice.")

if __name__ == "__main__":
    # Ensure we are in the right directory if possible
    try:
        os.chdir(WORKING_DIR)
    except:
        pass
    menu()
