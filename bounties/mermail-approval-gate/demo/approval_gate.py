from __future__ import annotations

import argparse
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

DECISION_RE = re.compile(
    r"^(APPROVE|REJECT)\s+([A-Za-z0-9_-]{8,128})\s+([0-9a-f]{12})$",
    re.IGNORECASE,
)


def _dt(value: str) -> datetime:
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    dt = datetime.fromisoformat(text)
    if dt.tzinfo is None:
        raise ValueError("timestamps must include a timezone")
    return dt.astimezone(timezone.utc)


def canonical_proposal(proposal: dict[str, Any]) -> bytes:
    return json.dumps(
        proposal,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")


def proposal_digest(proposal: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_proposal(proposal)).hexdigest()


def evaluate(record: dict[str, Any], now: str | None = None) -> dict[str, Any]:
    request = record["request"]
    reply = record["reply"]

    proposal = request["proposal"]
    digest = proposal_digest(proposal)
    expected_prefix = digest[:12]
    token = str(request["token"])
    approver = str(request["approver"]).strip().lower()
    sent_at = _dt(str(request["sent_at"]))
    expires_at = _dt(str(request["expires_at"]))
    now_dt = _dt(now) if now else datetime.now(timezone.utc)

    base = {
        "proposal_sha256": digest,
        "proposal_digest_prefix": expected_prefix,
        "approver": approver,
        "thread_id": request["thread_id"],
        "request_message_id": request.get("request_message_id"),
        "approval_message_id": reply.get("message_id"),
        "expires_at": expires_at.isoformat().replace("+00:00", "Z"),
    }

    if now_dt > expires_at:
        return {**base, "status": "expired", "reason": "approval window expired"}

    if str(reply.get("thread_id", "")) != str(request["thread_id"]):
        return {**base, "status": "quarantined", "reason": "reply is outside the approval thread"}

    sender = str(reply.get("from", "")).strip().lower()
    if sender != approver:
        return {**base, "status": "quarantined", "reason": "reply sender is not the configured approver"}

    scan_status = str(reply.get("scan_status", "")).strip().lower()
    if scan_status != "clean":
        return {**base, "status": "quarantined", "reason": f"scan_status is {scan_status or 'missing'}"}

    reply_at = _dt(str(reply["date"]))
    if reply_at < sent_at:
        return {**base, "status": "quarantined", "reason": "reply predates the approval request"}
    if reply_at > expires_at:
        return {**base, "status": "expired", "reason": "reply arrived after approval expiry"}

    auth = reply.get("sender_authentication") or {}
    auth_status = str(auth.get("status", "unknown")).strip().lower()
    require_auth_pass = bool(request.get("require_sender_auth_pass", True))
    if require_auth_pass and auth_status != "pass":
        return {
            **base,
            "status": "needs_human_verification",
            "reason": f"sender_authentication is {auth_status or 'unknown'}",
        }

    body = str(reply.get("text", "")).strip()
    match = DECISION_RE.fullmatch(body)
    if not match:
        return {
            **base,
            "status": "quarantined",
            "reason": "reply did not match the exact one-line approval grammar",
        }

    verb, seen_token, seen_digest = match.groups()
    if seen_token != token:
        return {**base, "status": "quarantined", "reason": "approval token mismatch"}
    if seen_digest.lower() != expected_prefix:
        return {**base, "status": "quarantined", "reason": "proposal digest mismatch"}

    status = "approved" if verb.upper() == "APPROVE" else "rejected"
    return {
        **base,
        "status": status,
        "reason": "exact approval protocol satisfied",
        "sender_authentication": auth_status,
        "decision": verb.upper(),
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Evaluate one normalized Mermail approval-gate fixture."
    )
    parser.add_argument("record", type=Path)
    parser.add_argument("--now", help="ISO-8601 evaluation time (for deterministic demos)")
    args = parser.parse_args()

    record = json.loads(args.record.read_text(encoding="utf-8"))
    result = evaluate(record, now=args.now)
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["status"] in {"approved", "rejected"} else 2


if __name__ == "__main__":
    raise SystemExit(main())
