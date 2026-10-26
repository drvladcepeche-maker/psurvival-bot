#!/usr/bin/env python3
"""Restricted Termux command bridge. Executes only fixed, allow-listed project commands."""
import json, os, pathlib, subprocess, time, urllib.request, urllib.error

API = "https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/termux-bridge"
BASE = pathlib.Path.home() / "psurvival-bot"
CONFIG = pathlib.Path.home() / ".config" / "termux-bridge" / "config.json"
MAX_OUTPUT = 40000
POLL_SECONDS = 4

def load_config():
    data = json.loads(CONFIG.read_text())
    token = data.get("token", "")
    if len(token) != 64:
        raise SystemExit("Invalid bridge token in " + str(CONFIG))
    return token

def request(token, payload):
    req = urllib.request.Request(API, data=json.dumps(payload).encode(), method="POST",
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors="replace")[:1000]
        raise RuntimeError("bridge HTTP " + str(e.code) + ": " + body)

def run_job(job):
    kind = job.get("command_type")
    args = job.get("args") or {}
    if not BASE.is_dir():
        return 2, "", "Project directory not found: " + str(BASE)
    commands = {
        "pwd": ["/system/bin/sh", "-c", "pwd"],
        "list_files": None,
        "git_status": ["git", "-C", str(BASE), "status", "--short", "--branch"],
        "git_log": ["git", "-C", str(BASE), "log", "-5", "--oneline"],
        "git_diff_stat": ["git", "-C", str(BASE), "diff", "--stat"],
        "gradle_test": ["bash", "./gradlew", "test"],
        "gradle_assemble_debug": ["bash", "./gradlew", "assembleDebug"],
    }
    if kind not in commands:
        return 2, "", "Command type not allow-listed"
    if kind == "list_files":
        lines = []
        excluded = {".git", ".gradle", "build", "node_modules", ".idea"}
        for root, dirs, files in os.walk(BASE):
            dirs[:] = [d for d in dirs if d not in excluded]
            for name in files:
                p = pathlib.Path(root) / name
                rel = str(p.relative_to(BASE))
                if rel.startswith(".env") or rel.endswith((".jks", ".keystore", ".pem", ".key")):
                    continue
                lines.append(rel)
                if len(lines) >= 500:
                    lines.append("... truncated at 500 files")
                    return 0, "\n".join(lines), ""
        return 0, "\n".join(lines), ""
    if kind == "pwd":
        return 0, str(BASE), ""
    cmd = commands[kind]
    cwd = BASE if kind.startswith("gradle_") else pathlib.Path.home()
    try:
        proc = subprocess.run(cmd, cwd=str(cwd), capture_output=True, text=True,
            timeout=900 if kind.startswith("gradle_") else 30, check=False)
        out = (proc.stdout or "")[-MAX_OUTPUT:]
        err = (proc.stderr or "")[-8000:]
        return proc.returncode, out, err
    except subprocess.TimeoutExpired:
        return 124, "", "Command timed out"
    except Exception as e:
        return 1, "", str(e)[:8000]

def main():
    token = load_config()
    print("Termux bridge connected. Allowed commands only; Ctrl+C stops the worker.")
    while True:
        try:
            result = request(token, {"action": "claim"})
            job = result.get("job")
            if not job:
                time.sleep(POLL_SECONDS)
                continue
            print("Running job", job.get("id"), job.get("command_type"), flush=True)
            code, output, error = run_job(job)
            request(token, {"action": "complete", "job_id": job["id"],
                "exit_code": code, "output": output, "error_text": error})
            print("Finished job", job.get("id"), "exit", code, flush=True)
        except KeyboardInterrupt:
            print("\nStopped.")
            break
        except Exception as e:
            print("Bridge error:", str(e)[:500], flush=True)
            time.sleep(10)

if __name__ == "__main__":
    main()
