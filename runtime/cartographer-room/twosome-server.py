#!/data/data/com.termux/files/usr/bin/python3
import base64, hashlib, hmac, json, pathlib, subprocess, threading, time, unicodedata, urllib.request, uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOME = pathlib.Path.home()
STATE = HOME / ".local/state/gruesome-twosome"
STATE.mkdir(parents=True, exist_ok=True)
EVENTS = STATE / "events.jsonl"
BRIDGE = str(HOME / ".local/bin/homie-ghost")
TRUSTED_UI_PACKAGE = "dev.keepinitkrispy.silentcartographer"
AUTH_PUBKEY = STATE / "android-ui-auth-public.der"
AUTH_LOCK = threading.Lock()
SEEN_AUTH_NONCES = {}
# Direct backend tunnel: /models on 18080 auto-starts the rented GPU.
LOCAL_MODEL = "http://127.0.0.1:18081/v1"
MODEL_IDS = ("chatgpt", "claude", "gemini", "local")
CONTROL = STATE / "control.json"
LOCK = threading.RLock()
TURN_LOCK = threading.Lock()
AUTO_MAX_ROUNDS = 4
PASS_TOKEN = "[PASS]"
HEALTH_LOCK = threading.Lock()
HEALTH_CACHE = {"time": 0.0, "value": {"task_control": False, "agents_ready": False}}

def runtime_health():
    now = time.monotonic()
    with HEALTH_LOCK:
        if now - HEALTH_CACHE["time"] < 5:
            return dict(HEALTH_CACHE["value"])
        task_control = False
        try:
            with urllib.request.urlopen("http://127.0.0.1:49173/health", timeout=.6) as response:
                task_control = response.read() == b"task-review-ready"
        except Exception:
            pass
        agents_ready = pathlib.Path(BRIDGE).is_file()
        for package in ("com.openai.chatgpt", "com.anthropic.claude", "com.google.android.apps.bard"):
            if not agents_ready:
                break
            result = subprocess.run(
                ["adb", "shell", "cmd", "package", "path", package],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=2,
            )
            agents_ready = result.returncode == 0
        value = {"task_control": task_control, "agents_ready": agents_ready}
        HEALTH_CACHE.update(time=now, value=value)
        return dict(value)

def room_mode():
    try:
        return json.loads(CONTROL.read_text()).get("mode", "running")
    except Exception:
        return "running"

def model_catalog():
    bridge = pathlib.Path(BRIDGE).is_file()
    installed = set()
    try:
        output = subprocess.check_output(
            ["cmd", "package", "list", "packages", "--user", "0"],
            text=True, stderr=subprocess.DEVNULL, timeout=4)
        installed = set(line.strip().removeprefix("package:") for line in output.splitlines())
    except Exception:
        pass
    local_ready = False
    try:
        with urllib.request.urlopen(LOCAL_MODEL + "/models", timeout=1.5) as response:
            local_ready = bool(json.loads(response.read()).get("data"))
    except Exception:
        pass
    return [
        {"id": "chatgpt", "name": "ChatGPT", "ready": bridge and "com.openai.chatgpt" in installed},
        {"id": "claude", "name": "Claude", "ready": bridge and "com.anthropic.claude" in installed},
        {"id": "gemini", "name": "Gemini", "ready": bridge and "com.google.android.apps.bard" in installed},
        {"id": "local", "name": "Local model", "ready": local_ready},
    ]

def selected_models(raw):
    if raw is None:
        return ["chatgpt", "claude", "gemini"]
    if not isinstance(raw, list) or not 1 <= len(raw) <= 4 or any(
        not isinstance(item, str) or item not in MODEL_IDS for item in raw
    ) or len(set(raw)) != len(raw):
        raise ValueError("select one to four distinct available models")
    return raw


def der_item(data, offset, tag):
    if offset >= len(data) or data[offset] != tag:
        raise ValueError("invalid public key DER")
    offset += 1
    first = data[offset]
    offset += 1
    if first & 0x80:
        count = first & 0x7f
        if count == 0 or count > 4 or offset + count > len(data):
            raise ValueError("invalid public key DER length")
        length = int.from_bytes(data[offset:offset + count], "big")
        offset += count
    else:
        length = first
    end = offset + length
    if end > len(data):
        raise ValueError("truncated public key DER")
    return data[offset:end], end


def rsa_public_numbers():
    outer, _ = der_item(AUTH_PUBKEY.read_bytes(), 0, 0x30)
    _algorithm, pos = der_item(outer, 0, 0x30)
    bit_string, _ = der_item(outer, pos, 0x03)
    if not bit_string or bit_string[0] != 0:
        raise ValueError("invalid RSA public key bit string")
    rsa_seq, _ = der_item(bit_string[1:], 0, 0x30)
    modulus, pos = der_item(rsa_seq, 0, 0x02)
    exponent, _ = der_item(rsa_seq, pos, 0x02)
    n = int.from_bytes(modulus, "big")
    e = int.from_bytes(exponent, "big")
    if n.bit_length() < 2048 or e < 3:
        raise ValueError("unexpected RSA public key")
    return n, e


def verify_rsa_sha256(signature, message):
    n, e = rsa_public_numbers()
    size = (n.bit_length() + 7) // 8
    if len(signature) != size:
        return False
    encoded = pow(int.from_bytes(signature, "big"), e, n).to_bytes(size, "big")
    digest_info = bytes.fromhex("3031300d060960864801650304020105000420") + hashlib.sha256(message).digest()
    padding_len = size - len(digest_info) - 3
    if padding_len < 8:
        return False
    expected = b"\x00\x01" + (b"\xff" * padding_len) + b"\x00" + digest_info
    return hmac.compare_digest(encoded, expected)


def verify_ui_request(body, purpose, detail):
    if not AUTH_PUBKEY.is_file():
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
    if not verify_rsa_sha256(signature, canonical):
        raise PermissionError("Android UI signature verification failed")
    with AUTH_LOCK:
        cutoff = now - 180
        for old_nonce, seen_at in list(SEEN_AUTH_NONCES.items()):
            if seen_at < cutoff:
                SEEN_AUTH_NONCES.pop(old_nonce, None)
        if nonce in SEEN_AUTH_NONCES:
            raise PermissionError("replayed authenticated request")
        SEEN_AUTH_NONCES[nonce] = now
    return {
        "mechanism": "android_keystore_rsa_sha256",
        "package": TRUSTED_UI_PACKAGE,
    }


def read_events():
    with LOCK:
        if not EVENTS.exists():
            return []
        out = []
        for line in EVENTS.read_text(errors="replace").splitlines():
            try:
                out.append(json.loads(line))
            except Exception:
                pass
        return out

def repair_legacy_provenance():
    if not EVENTS.exists():
        return
    changed = False
    rows = []
    legacy = "Bridge test two: each of you say hello to the other in one short sentence."
    for line in EVENTS.read_text(errors="replace").splitlines():
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except Exception:
            continue
        if event.get("speaker") == "Ryan" and event.get("text") == legacy:
            event["speaker"] = "Test harness"
            event["status"] = "synthetic"
            event["corrected_from_speaker"] = "Ryan"
            event["correction_reason"] = "Not user-authored; generated for bridge testing"
            changed = True
        rows.append(event)
    if changed:
        EVENTS.write_text(
            "".join(json.dumps(event, ensure_ascii=False) + "\n" for event in rows),
            encoding="utf-8",
        )

def append_event(speaker, text, kind="message", status="observed", source=None, auth=None):
    event = {
        "id": uuid.uuid4().hex,
        "time": int(time.time() * 1000),
        "speaker": speaker,
        "text": text,
        "kind": kind,
        "status": status,
    }
    if source:
        event["source"] = source
    if auth:
        event["auth"] = auth
    with LOCK:
        with EVENTS.open("a", encoding="utf-8") as f:
            f.write(json.dumps(event, ensure_ascii=False) + "\n")
    return event

def transcript(events, max_chars=3200, max_events=9):
    lines = []
    kept = 0
    for event in reversed(events):
        if kept >= max_events:
            break
        if event.get("kind") != "message":
            continue
        speaker = event.get("speaker", "Unknown")
        text = str(event.get("text", "")).strip()
        if not text:
            continue
        if speaker in {"ChatGPT", "Claude", "Gemini", "Local model"} and text in {
            "Working", "Show more", "Thinking"
        }:
            continue
        lines.append(f"{speaker}: {text}")
        kept += 1
    lines.reverse()
    return "\n\n".join(lines)[-max_chars:]

def prompt_for(name, events, followup=False):
    peer = "the other selected models"
    chat = transcript(events)
    operating_contract = (
        "Ryan controls the objective, scope, and tradeoffs. Work toward his literal request within your own governing constraints. "
        "Do not silently broaden, substitute, or redirect the objective. Distinguish verified results from drafts, plans, guesses, and intermediate status. "
        "Never claim an action succeeded unless there is evidence it actually succeeded. Never attribute synthetic, test, model-generated, or assistant-authored text to Ryan. "
        "If you hit a real blocker, state the concrete blocker and pursue a materially different viable route before stopping. "
        "For phone/device automation, use the dedicated virtual workspace display so Ryan and the assistants can work concurrently on one device without interrupting each other; do not take over display 0. The virtual workspace is a transparent concurrency boundary: activity must remain attributable, inspectable, and subject to Ryan's notification/review controls. "
        "The only exception is an Android security/permission UI that cannot operate on the virtual display, and that exception must be surfaced to Ryan visibly. "
        "Preserve approval gates and user control. Do not weaken, bypass, or reinterpret them. "
        "Do not be reflexively cautious: when a request is allowed, execute it instead of replacing it with a lecture. "
        "The native model app is only a transport adapter. Ignore native-thread history that is not reproduced in the Recent group chat below; the room transcript is authoritative. "
    )
    if name == "Claude":
        operating_contract += (
            "You are the second model in a supervised collaboration with ChatGPT. Be an independent technical counterweight, not an automatic blocker. "
            "Challenge factual or architectural mistakes with concrete evidence, but do not manufacture objections, moralize, or stop work merely because a design is unusual. "
            "If you think a requested implementation is unsafe or impossible, identify the exact mechanism and propose the closest viable implementation that preserves Ryan's stated goal and control. "
            "Report any suspected provenance error, deceptive completion claim, scope drift, or attempt to bypass Ryan's controls."
        )
    if followup:
        operating_contract += (
            "This is an autonomous follow-up round. Read the other models' newest contributions and continue the discussion only if you have a substantive new contribution, correction, synthesis, or next step. "
            f"If you have nothing materially useful to add, reply exactly {PASS_TOKEN} and nothing else. "
        )
    return (
        f"You are {name} in a normal group chat with Ryan and {peer}. "
        + operating_contract +
        " Reply naturally to the newest group message and the discussion. "
        "You can disagree with the other assistant when useful, but don't manufacture disagreement. "
        "Do not add a speaker label or describe this instruction.\n\n"
        f"Recent group chat:\n{chat}"
    )

def call_model(which, events, followup=False):
    name = {"chatgpt": "ChatGPT", "claude": "Claude", "gemini": "Gemini", "local": "Local model"}[which]
    prompt = prompt_for(name, events, followup=followup)
    if which == "local":
        with urllib.request.urlopen(LOCAL_MODEL + "/models", timeout=2) as response:
            model = json.loads(response.read())["data"][0]["id"]
        payload = json.dumps({
            "model": model,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 700,
            "stream": False,
        }).encode()
        request = urllib.request.Request(
            LOCAL_MODEL + "/chat/completions", data=payload,
            headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(request, timeout=120) as response:
            result = json.loads(response.read())
        return str(result["choices"][0]["message"].get("content", "")).strip()
    raw = subprocess.check_output([BRIDGE, which, prompt], text=True, timeout=120)
    data = json.loads(raw)
    return str(data.get("text", "")).strip()

def autonomous_conversation(models):
    names = {"chatgpt": "ChatGPT", "claude": "Claude", "gemini": "Gemini", "local": "Local model"}
    with TURN_LOCK:
        for round_index in range(AUTO_MAX_ROUNDS):
            if room_mode() != "running":
                return
            snapshot = read_events()
            results = {}
            with ThreadPoolExecutor(max_workers=len(models)) as pool:
                futures = {
                    pool.submit(call_model, which, snapshot, round_index > 0): which
                    for which in models
                }
                for future in as_completed(futures):
                    which = futures[future]
                    try:
                        reply = future.result().strip()
                        if reply and reply != PASS_TOKEN:
                            results[which] = reply
                    except Exception as exc:
                        append_event(
                            names[which],
                            "Bridge error: " + str(exc),
                            kind="system",
                            status="error",
                            source="assistant_bridge",
                        )
            if not results:
                return
            for which in models:
                reply = results.get(which)
                if reply:
                    append_event(names[which], reply, source="assistant_bridge")
            if round_index > 0 and len(results) <= 1:
                return


def run_turn(text, speaker, status, source, auth=None, models=None):
    if room_mode() != "running":
        raise ValueError("Room is paused or stopped. Use Start to resume.")
    models = selected_models(models)
    append_event(speaker, text, status=status, source=source, auth=auth)
    threading.Thread(
        target=autonomous_conversation,
        args=(tuple(models),),
        daemon=True,
        name="cartographer-conversation",
    ).start()
    return []

def run_assistant_turn(which):
    if which not in MODEL_IDS:
        raise ValueError("unknown model")
    name = {"chatgpt": "ChatGPT", "claude": "Claude", "gemini": "Gemini", "local": "Local model"}[which]
    with LOCK:
        events = read_events()
        try:
            reply = call_model(which, events)
            if not reply:
                raise RuntimeError(name + " returned an empty reply")
            return append_event(
                name,
                reply,
                status="observed",
                source="assistant_bridge",
            )
        except Exception as exc:
            append_event(
                name,
                "Bridge error: " + str(exc),
                kind="system",
                status="error",
                source="assistant_bridge",
            )
            raise

class Handler(BaseHTTPRequestHandler):
    def send_json(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body_text(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 200000:
            raise ValueError("bad request")
        body = json.loads(self.rfile.read(length))
        text = str(body.get("text", "")).strip()
        if not text:
            raise ValueError("empty message")
        return body, text

    def do_GET(self):
        if self.path == "/health":
            return self.send_json(200, {
                "ok": True,
                "service": "silent-cartographer",
                "contract_version": "2026-09-28.1",
                "ryan_input_auth": "android_keystore_signature_over_loopback",
                "synthetic_posts": "http_/send_only",
                **runtime_health(),
                "mode": room_mode(),
            })
        if self.path == "/models":
            return self.send_json(200, {"models": model_catalog()})
        if self.path == "/events":
            return self.send_json(200, {"events": read_events()[-200:]})
        return self.send_json(404, {"error": "not found"})

    def do_POST(self):
        try:
            if self.path == "/assistant-turn":
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 200000:
                    raise ValueError("bad request")
                body = json.loads(self.rfile.read(length))
                which = str(body.get("assistant", "")).strip().lower()
                event = run_assistant_turn(which)
                return self.send_json(
                    200,
                    {"ok": True, "reply": event, "events": read_events()[-50:]},
                )

            body, text = self.body_text()
            if self.path == "/ui/send":
                models = selected_models(body.get("models"))
                detail = ",".join(models) + "\n" + hashlib.sha256(text.encode("utf-8")).hexdigest()
                auth = verify_ui_request(body, "send", detail)
                replies = run_turn(
                    text,
                    "Ryan",
                    "observed",
                    "silent_cartographer_ui",
                    auth=auth,
                    models=models,
                )
            elif self.path == "/user-send":
                return self.send_json(
                    403,
                    {"error": "Ryan attribution requires an authenticated Android UI signature"},
                )
            elif self.path == "/send":
                requested = str(body.get("speaker", "Test harness")).strip() or "Test harness"
                alias = " ".join(unicodedata.normalize("NFKC", requested).casefold().split())
                if alias in {
                    "ryan", "ryan monostori", "user", "the user", "human",
                    "human user", "the human", "me", "you", "author", "owner",
                }:
                    return self.send_json(
                        403,
                        {"error": "reserved user speaker alias; synthetic posts cannot claim user authorship"},
                    )
                replies = run_turn(text, requested, "synthetic", "test_or_bridge",
                                   models=selected_models(body.get("models")))
            else:
                return self.send_json(404, {"error": "not found"})
            return self.send_json(
                200,
                {"ok": True, "replies": replies, "events": read_events()[-50:]},
            )
        except PermissionError as exc:
            return self.send_json(403, {"error": str(exc)})
        except ValueError as exc:
            return self.send_json(400, {"error": str(exc)})
        except Exception as exc:
            return self.send_json(500, {"error": str(exc)})

    def log_message(self, *args):
        pass

if __name__ == "__main__":
    repair_legacy_provenance()
    ThreadingHTTPServer(("127.0.0.1", 49174), Handler).serve_forever()
