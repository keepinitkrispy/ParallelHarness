#!/data/data/com.termux/files/usr/bin/python3
"""Resident authenticated control channel for Silent Cartographer's Android UI."""
import base64
import json
import os
import pathlib
import socketserver
import subprocess
import tempfile
import threading
import time

HOME = pathlib.Path.home()
STATE = HOME / ".local/state/gruesome-twosome"
STATE.mkdir(parents=True, exist_ok=True)
CONTROL = STATE / "control.json"
AUDIT = STATE / "control-events.jsonl"
PUBKEY = STATE / "android-ui-auth-public.pem"
PACKAGE = "dev.keepinitkrispy.silentcartographer"
SERVICE = pathlib.Path(os.environ.get("PREFIX", "/data/data/com.termux/files/usr")) / "var/service/silent-cartographer-room"
HOST = "127.0.0.1"
PORT = 49177
LOCK = threading.RLock()
AUTH_LOCK = threading.Lock()
SEEN_NONCES = {}

def mode():
    try:
        return json.loads(CONTROL.read_text())["mode"]
    except Exception:
        return "running"


def service_status():
    result = subprocess.run(["sv", "status", str(SERVICE)], text=True,
                            capture_output=True, timeout=5)
    return result.returncode == 0 and result.stdout.startswith("run:")


def verify_request(body, purpose, detail):
    if not PUBKEY.is_file():
        raise PermissionError("Android UI auth key is not provisioned")
    ts = int(body.get("ts", 0))
    nonce = str(body.get("nonce", ""))
    sig_b64 = str(body.get("sig", ""))
    now = int(time.time())
    if abs(now - ts) > 90:
        raise PermissionError("stale authenticated request")
    if not 16 <= len(nonce) <= 96:
        raise PermissionError("invalid auth nonce")
    try:
        signature = base64.b64decode(sig_b64, validate=True)
    except Exception as exc:
        raise PermissionError("invalid auth signature encoding") from exc
    if not 128 <= len(signature) <= 1024:
        raise PermissionError("invalid auth signature size")
    canonical = f"sc-auth-v1\n{purpose}\n{ts}\n{nonce}\n{detail}".encode()
    with tempfile.NamedTemporaryFile(dir=STATE) as msg, tempfile.NamedTemporaryFile(dir=STATE) as sig:
        msg.write(canonical)
        msg.flush()
        sig.write(signature)
        sig.flush()
        result = subprocess.run(
            ["openssl", "dgst", "-sha256", "-verify", str(PUBKEY),
             "-signature", sig.name, msg.name],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5,
        )
    if result.returncode != 0:
        raise PermissionError("Android UI signature verification failed")
    with AUTH_LOCK:
        cutoff = now - 180
        for old, seen_at in list(SEEN_NONCES.items()):
            if seen_at < cutoff:
                SEEN_NONCES.pop(old, None)
        if nonce in SEEN_NONCES:
            raise PermissionError("replayed authenticated request")
        SEEN_NONCES[nonce] = now
    return {
        "mechanism": "android_keystore_rsa_sha256",
        "package": PACKAGE,
    }


def audit(action, auth):
    with AUDIT.open("a") as log:
        log.write(json.dumps({
            "action": action,
            "at": time.time(),
            "auth": auth,
        }) + "\n")


def set_mode(value, auth):
    with LOCK:
        temp = CONTROL.with_suffix(".tmp")
        temp.write_text(json.dumps({
            "mode": value,
            "at": time.time(),
            "auth": auth,
        }) + "\n")
        os.replace(temp, CONTROL)
        audit(value, auth)

class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        try:
            raw = self.rfile.readline(5001)
            if not raw or len(raw) > 5000:
                raise ValueError("invalid control request")
            body = json.loads(raw)
            action = str(body.get("action", ""))
            if action not in ("start_server", "start", "pause", "stop"):
                raise ValueError("unknown control action")
            auth = verify_request(body, "control", action)
            if action == "start_server":
                if not SERVICE.is_dir():
                    raise RuntimeError("room service not installed")
                (SERVICE / "down").unlink(missing_ok=True)
                subprocess.run(["sv", "up", str(SERVICE)], check=True, timeout=8)
                audit(action, auth)
            else:
                if action == "start" and not service_status():
                    raise RuntimeError("start the server first")
                value = "running" if action == "start" else "paused" if action == "pause" else "stopped"
                set_mode(value, auth)
            self.reply({
                "ok": True,
                "action": action,
                "room_running": service_status(),
                "mode": mode(),
                "auth": auth["mechanism"],
            })
        except PermissionError as exc:
            self.reply({"ok": False, "error": str(exc)})
        except Exception as exc:
            self.reply({"ok": False, "error": str(exc)[:300]})

    def reply(self, payload):
        self.wfile.write(json.dumps(payload).encode() + b"\n")
        self.wfile.flush()


class Server(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True


if __name__ == "__main__":
    Server((HOST, PORT), Handler).serve_forever()
