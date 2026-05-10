import os
import subprocess
import signal
import time
import sys

# --- CONFIGURATION ---
PID_FILE = "vigsync_consumer.pid"
CONSUMER_SCRIPT = "vigsync_consumer.py"
LOG_FILE = "vigsync_consumer.log"
LIVE_LOG_RAM = "/dev/shm/vigsync_live.log"
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
    pid = get_pid()
    if pid:
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False
    
    try:
        result = subprocess.run(["systemctl", "is-active", "--quiet", SERVICE_NAME])
        return result.returncode == 0
    except:
        return False

def start_consumer():
    if is_running():
        print("[!] Consumer is already running.")
        return
    
    if os.path.exists(f"/etc/systemd/system/{SERVICE_NAME}"):
        print("[*] Starting via systemd...")
        os.system(f"sudo systemctl start {SERVICE_NAME}")
    else:
        print("[*] Starting VigSync Consumer in background (RAM logging enabled)...")
        # Redirect stdout to RAM for live viewing if not using systemd
        cmd = f"nohup {sys.executable} {os.path.join(WORKING_DIR, CONSUMER_SCRIPT)} --background > {LIVE_LOG_RAM} 2>&1 &"
        os.system(cmd)
    
    time.sleep(1)
    if is_running():
        print("[+] Started successfully.")
    else:
        print("[!] Start failed. Check logs.")

def stop_consumer():
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
    print("[*] Setting up systemd service...")
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
        temp_file = "/tmp/vigsync.service"
        with open(temp_file, "w") as f:
            f.write(service_content)
        
        os.system(f"sudo mv {temp_file} /etc/systemd/system/{SERVICE_NAME}")
        os.system(f"sudo chown root:root /etc/systemd/system/{SERVICE_NAME}")
        os.system(f"sudo chmod 644 /etc/systemd/system/{SERVICE_NAME}")
        os.system("sudo systemctl daemon-reload")
        os.system(f"sudo systemctl enable {SERVICE_NAME}")
        print("[+] Setup complete!")
    except Exception as e:
        print(f"[!] Setup failed: {e}")

def view_live_logs():
    print("\n--- ENTERING REAL-TIME LOG VIEW (Press Ctrl+C to exit) ---")
    try:
        if os.path.exists(f"/etc/systemd/system/{SERVICE_NAME}"):
            # Use journalctl for systemd services
            subprocess.run(["journalctl", "-u", SERVICE_NAME, "-f", "-n", "50"])
        else:
            # Use RAM log for manual background runs
            if os.path.exists(LIVE_LOG_RAM):
                subprocess.run(["tail", "-f", LIVE_LOG_RAM])
            else:
                print("[!] No live log found in RAM. Try restarting the consumer.")
    except KeyboardInterrupt:
        print("\n--- Back to menu ---")

def view_sd_logs():
    full_log_path = os.path.join(WORKING_DIR, LOG_FILE)
    if os.path.exists(full_log_path):
        print(f"--- Tail of {LOG_FILE} (Last sync/error) ---")
        subprocess.run(["tail", "-n", "20", full_log_path])
        print("---------------------------")
    else:
        print("[!] No log file found yet.")

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
        print("4. View Real-time Logs (LIVE)")
        print("5. View SD Card Logs (HISTORY)")
        print("6. Setup Auto-start on Reboot (systemd)")
        print("7. Exit")
        
        choice = input("\nChoice: ").strip()
        
        if choice == "1":
            start_consumer()
        elif choice == "2":
            stop_consumer()
        elif choice == "3":
            stop_consumer()
            start_consumer()
        elif choice == "4":
            view_live_logs()
        elif choice == "5":
            view_sd_logs()
        elif choice == "6":
            setup_autostart()
        elif choice == "7":
            break
        else:
            print("[!] Invalid choice.")

if __name__ == "__main__":
    try:
        os.chdir(WORKING_DIR)
    except:
        pass
    menu()
