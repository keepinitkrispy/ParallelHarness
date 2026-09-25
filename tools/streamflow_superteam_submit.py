#!/usr/bin/env python3
"""
One-shot Streamflow Superteam Earn submission from Termux.

- Verifies the live listing is OPEN + agent-eligible.
- Registers one Superteam agent only if local state does not already contain one.
- Submits Ryan's existing X post.
- Keeps the Superteam agent API key only in Termux private local state (0600).
- Prints only sanitized result data.
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
import subprocess
import tempfile
from pathlib import Path

BASE = "https://superteam.fun"
SLUG = "create-twitter-post-about-the-stream-burn"
POST_URL = "https://x.com/RyanMonostori/status/2103496009707876473"
AGENT_NAME = "solbridge-streamflow-ryan-20260925"
STATE = Path.home() / ".local/state/streamflow-superteam-agent.json"


def request(method: str, path: str, payload=None, api_key: str | None = None):
    headers = {"User-Agent": "Ryan-Termux-Superteam/1.0"}
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload).encode()
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode("utf-8", "replace")
            try:
                body = json.loads(raw)
            except Exception:
                body = {"raw": raw[:4000]}
            return r.status, body
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            body = json.loads(raw)
        except Exception:
            body = {"raw": raw[:4000]}
        return e.code, body


def save_state(state: dict):
    STATE.parent.mkdir(parents=True, exist_ok=True)
    tmp = STATE.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, indent=2) + "\n")
    os.chmod(tmp, 0o600)
    tmp.replace(STATE)
    os.chmod(STATE, 0o600)


def load_state() -> dict:
    if not STATE.exists():
        return {}
    try:
        return json.loads(STATE.read_text())
    except Exception:
        return {}


def sanitized(obj):
    secret_keys = {
        "apikey", "api_key", "accesstoken", "access_token",
        "authorization", "password", "secret", "privytoken", "privy_token",
    }
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            if str(k).lower() in secret_keys:
                continue
            out[k] = sanitized(v)
        return out
    if isinstance(obj, list):
        return [sanitized(x) for x in obj]
    return obj


def report_to_repo(safe: dict):
    """Best-effort sanitized report back to the private SolBridge workspace."""
    if not shutil_which("gh"):
        return
    report = {
        "marker": "TERMUX_STREAMFLOW_RESULT_V1",
        **safe,
    }
    claim = safe.get("claimCode")
    if claim:
        report["claimUrl"] = f"{BASE}/earn/claim/{claim}"
    try:
        with tempfile.NamedTemporaryFile("w", delete=False, prefix="streamflow-result-", suffix=".json") as fh:
            json.dump(report, fh, indent=2)
            fh.write("\n")
            path = fh.name
        os.chmod(path, 0o600)
        subprocess.run(
            ["gh", "issue", "comment", "571", "--repo", "keepinitkrispy/solbridge-bus", "--body-file", path],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=20,
        )
    except Exception:
        pass
    finally:
        try:
            os.unlink(path)
        except Exception:
            pass


def shutil_which(name: str):
    from shutil import which
    return which(name)


def finish(result: dict, code=0):
    safe = sanitized(result)
    report_to_repo(safe)
    print(json.dumps(safe, indent=2))
    claim = safe.get("claimCode")
    if claim:
        print("\nClaim URL (needed for payout if the agent wins):")
        print(f"{BASE}/earn/claim/{claim}")
    raise SystemExit(code)


def main():
    status, listings = request("GET", "/api/listings?take=100")
    if status != 200 or not isinstance(listings, list):
        finish({"ok": False, "stage": "listing_fetch", "http": status, "response": listings}, 2)

    listing = next((x for x in listings if x.get("slug") == SLUG), None)
    if not listing:
        finish({"ok": False, "stage": "listing_lookup", "message": "Target listing not found"}, 3)

    eligibility = {
        "id": listing.get("id"),
        "slug": listing.get("slug"),
        "status": listing.get("status"),
        "agentAccess": listing.get("agentAccess"),
        "token": listing.get("token"),
        "rewardAmount": listing.get("rewardAmount"),
    }
    if listing.get("status") != "OPEN" or listing.get("agentAccess") not in ("AGENT_ALLOWED", "AGENT_ONLY"):
        finish({"ok": False, "stage": "eligibility", "eligibility": eligibility}, 4)

    state = load_state()
    if state.get("submitted") is True and state.get("postUrl") == POST_URL and state.get("listingId") == listing.get("id"):
        finish({
            "ok": True,
            "idempotent": True,
            "message": "Local state already records this exact submission as successful; no new request was sent.",
            "eligibility": eligibility,
            "listingId": state.get("listingId"),
            "agentId": state.get("agentId"),
            "username": state.get("username"),
            "claimCode": state.get("claimCode"),
            "postUrl": state.get("postUrl"),
            "submission": state.get("submission"),
        })

    api_key = state.get("apiKey")
    if not api_key:
        reg_http, reg = request("POST", "/api/agents", {"name": AGENT_NAME})
        if reg_http < 200 or reg_http >= 300 or not isinstance(reg, dict) or not reg.get("apiKey"):
            finish({"ok": False, "stage": "agent_registration", "http": reg_http, "response": reg}, 5)
        state = {
            "apiKey": reg.get("apiKey"),
            "claimCode": reg.get("claimCode"),
            "agentId": reg.get("agentId"),
            "username": reg.get("username"),
            "submitted": False,
            "postUrl": POST_URL,
            "listingId": listing.get("id"),
        }
        save_state(state)
        api_key = state["apiKey"]

    detail_http, details = request("GET", f"/api/agents/listings/details/{SLUG}", api_key=api_key)
    if detail_http != 200:
        finish({
            "ok": False,
            "stage": "agent_listing_details",
            "http": detail_http,
            "eligibility": eligibility,
            "claimCode": state.get("claimCode"),
            "agentId": state.get("agentId"),
            "username": state.get("username"),
            "response": details,
        }, 6)

    payload = {
        "listingId": listing.get("id"),
        "link": POST_URL,
        "tweet": POST_URL,
        "otherInfo": (
            "Original X post explaining the Streamflow STREAM supply burn, including "
            "the required @streamflow_fi mention, on-chain burn transaction link, "
            "and Streamflow Foundation link."
        ),
        "eligibilityAnswers": [],
        "ask": None,
        "telegram": None,
    }

    submit_http, submission = request(
        "POST", "/api/agents/submissions/create", payload, api_key=api_key
    )

    result = {
        "ok": 200 <= submit_http < 300,
        "stage": "submitted" if 200 <= submit_http < 300 else "submission_failed",
        "http": submit_http,
        "eligibility": eligibility,
        "listingId": listing.get("id"),
        "agentId": state.get("agentId"),
        "username": state.get("username"),
        "claimCode": state.get("claimCode"),
        "postUrl": POST_URL,
        "submission": submission,
    }

    if result["ok"]:
        state.update({
            "submitted": True,
            "postUrl": POST_URL,
            "listingId": listing.get("id"),
            "submission": sanitized(submission),
        })
        save_state(state)
        finish(result, 0)

    # Preserve the registered agent locally so a retry does not create another one.
    state.update({
        "submitted": False,
        "lastSubmitHttp": submit_http,
        "lastSubmitResponse": sanitized(submission),
    })
    save_state(state)
    finish(result, 7)


if __name__ == "__main__":
    main()
