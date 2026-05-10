import os
import subprocess
import signal
import time
import sys

# --- CONFIGURATION ---
PID_FILE = "vigsync_consumer.pid"
CONSUMER_SCRIPT = "vigsync_consumer.py"
LOG_FILE = "vigsync_consumer.log"

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
            # Signal 0 checks if process exists
            os.kill(pid, 0)
            return True
        except OSError:
            return False
    return False

def start_consumer():
    if is_running():
        print("[!] Consumer is already running.")
        return

    print("[*] Starting VigSync Consumer in background...")
    # Use nohup and redirect to dev/null to keep it running after terminal closes
    # The script handles its own logging to memory/file
    cmd = f"nohup {sys.executable} {CONSUMER_SCRIPT} --background > /dev/null 2>&1 &"
    os.system(cmd)

    # Wait a moment to let PID file be created
    time.sleep(1)
    if is_running():
        print("[+] Started successfully.")
    else:
        print("[!] Start failed. Check logs.")

def stop_consumer():
    pid = get_pid()
    if pid:
        print(f"[*] Stopping consumer (PID: {pid})...")
        try:
            os.kill(pid, signal.SIGTERM)
            # Wait for cleanup
            for _ in range(5):
                if not is_running():
                    break
                time.sleep(1)

            if os.path.exists(PID_FILE):
                os.remove(PID_FILE)
            print("[+] Stopped.")
        except Exception as e:
            print(f"[!] Error stopping: {e}")
    else:
        print("[!] Consumer is not running.")

def view_logs():
    if os.path.exists(LOG_FILE):
        print(f"--- Tail of {LOG_FILE} ---")
        subprocess.run(["tail", "-n", "20", LOG_FILE])
        print("---------------------------")
    else:
        print("[!] No log file found yet (flushes every 6h or on error).")

def menu():
    while True:
        status = "RUNNING" if is_running() else "STOPPED"
        print(f"\n=== VigSync RPi Manager [Status: {status}] ===")
        print("1. Start Consumer")
        print("2. Stop Consumer")
        print("3. Restart Consumer")
        print("4. View SD Card Logs")
        print("5. Exit")

        choice = input("\nChoice: ").strip()

        if choice == "1":
            start_consumer()
        elif choice == "2":
            stop_consumer()
        elif choice == choice == "3":
            stop_consumer()
            start_consumer()
        elif choice == "4":
            view_logs()
        elif choice == "5":
            break
        else:
            print("[!] Invalid choice.")

if __name__ == "__main__":
    menu()
