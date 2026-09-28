#!/data/data/com.termux/files/usr/bin/python3
"""Resident control socket for Silent Cartographer's Android UI."""
import json
import os
import pathlib
import socket
import socketserver
import struct
import subprocess
import threading
import time

HOME = pathlib.Path.home()
STATE = HOME / ".local/state/gruesome-twosome"
STATE.mkdir(parents=True, exist_ok=True)
CONTROL = STATE / "control.json"
AUDIT = STATE / "control-events.jsonl"
SOCKET = "\0silent_cartographer_control_v1"
PACKAGE = "dev.keepinitkrispy.silentcartographer"
SERVICE = pathlib.Path(os.environ.get("PREFIX", "/data/data/com.termux/files/usr")) / "var/service/silent-cartographer-room"
LOCK = threading.RLock()


def trusted_uid():
    output = subprocess.check_output(
        ["cmd", "package", "list", "packages", "-U", "--user", "0"],
        text=True, stderr=subprocess.DEVNULL, timeout=5)
    prefix = "package:" + PACKAGE + " uid:"
    for line in output.splitlines():
        if line.startswith(prefix):
            return int(line[len(prefix):].strip())
    raise RuntimeError("Silent Cartographer package UID unavailable")


def mode():
    try:
        return json.loads(CONTROL.read_text())["mode"]
    except Exception:
        return "running"


def service_status():
    result = subprocess.run(["sv", "status", str(SERVICE)], text=True,
                            capture_output=True, timeout=5)
    return result.returncode == 0 and result.stdout.startswith("run:")


def set_mode(value, uid):
    with LOCK:
        temp = CONTROL.with_suffix(".tmp")
        temp.write_text(json.dumps({"mode": value, "at": time.time(), "by_uid": uid}) + "\n")
        os.replace(temp, CONTROL)
        with AUDIT.open("a") as log:
            log.write(json.dumps({"action": value, "at": time.time(), "by_uid": uid}) + "\n")


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        try:
            pid, uid, _gid = struct.unpack(
                "3i", self.request.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
            if uid != trusted_uid():
                self.reply({"ok": False, "error": "unauthorized Android app"})
                return
            raw = self.rfile.readline(2001)
            if not raw or len(raw) > 2000:
                raise ValueError("invalid control request")
            action = json.loads(raw)["action"]
            if action == "start_server":
                if not SERVICE.is_dir():
                    raise RuntimeError("room service not installed")
                (SERVICE / "down").unlink(missing_ok=True)
                subprocess.run(["sv", "up", str(SERVICE)], check=True, timeout=8)
            elif action in ("start", "pause", "stop"):
                if action == "start" and not service_status():
                    raise RuntimeError("start the server first")
                set_mode("running" if action == "start" else "paused" if action == "pause" else "stopped", uid)
            else:
                raise ValueError("unknown control action")
            self.reply({"ok": True, "action": action, "room_running": service_status(), "mode": mode()})
        except Exception as exc:
            self.reply({"ok": False, "error": str(exc)[:300]})

    def reply(self, payload):
        self.wfile.write(json.dumps(payload).encode() + b"\n")
        self.wfile.flush()


class Server(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True


if __name__ == "__main__":
    Server(SOCKET, Handler).serve_forever()
