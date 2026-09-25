# Submission draft — Build and Demo a Mermail Agent Skill

## Project title

**Mermail Approval Gate — auditable human approval for autonomous agents**

## One-line pitch

Turn one Mermail email thread into a fail-closed, machine-checkable approve/reject gate for an immutable agent action—without letting inbound email execute anything.

## What I built

A community Mermail Agent Skill that lets an autonomous agent:

1. freeze a proposed action into canonical JSON;
2. SHA-256 hash that exact proposal;
3. send a one-use approval request through Mermail;
4. correlate the reply to the exact approver, mailbox, thread, time window, token, and digest;
5. require clean scanned content and provider-derived sender authentication;
6. parse only an exact one-line `APPROVE` / `REJECT` grammar; and
7. return a structured decision receipt while deliberately refusing to execute the gated action.

## Why it matters

The last mile of agentic automation is often not another tool call—it is reliable human authorization. Generic email is too permissive: a body can contain prompt injection, forwarded instructions, or an ambiguous “looks good.” Approval Gate turns Mermail's typed inbox into a narrow control-plane primitive that other agents can compose with safely.

## Mermail integration

The live workflow maps to current Mermail MCP operations:

- `list_mailboxes`
- `send_email`
- `search_emails`
- `get_email`
- `get_email_context`

It uses Mermail's stable mailbox IDs, metadata-only / agent-safe reads, scan status, sender-authentication object, and idempotent send model.

## Demo and validation

Credential-free deterministic demo:

```bash
python bounties/mermail-approval-gate/demo/approval_gate.py \
  bounties/mermail-approval-gate/fixtures/approved.json \
  --now 2026-09-25T15:00:00Z
```

Regression suite:

```bash
python -m unittest discover -s bounties/mermail-approval-gate/tests -v
```

Seven tests cover approval, rejection, wrong thread, prompt injection, unknown sender authentication, digest mismatch, and expiry.

## Security design

Inbound mail never selects tools or changes the proposal. The body is accepted only if it exactly matches:

```text
APPROVE <one-use-token> <proposal-hash-prefix>
```

or the equivalent `REJECT` line.

Unknown sender authentication does not silently become approval. Any extra instructions quarantine the message. A downstream agent must independently decide whether and how to act on the resulting receipt.

## Public artifact

Repository: https://github.com/keepinitkrispy/ParallelHarness

Submission branch: `bounty/mermail-approval-gate`

Artifact path: `bounties/mermail-approval-gate/`

Skill path: `skills/mermail-approval-gate/`

## Limitation

The public demo intentionally uses fixtures rather than a committed Mermail credential. A live walkthrough follows the same documented flow with a connected Mermail MCP mailbox. Provider integrations that return `sender_authentication: unknown` produce `needs_human_verification` rather than autonomous approval.
