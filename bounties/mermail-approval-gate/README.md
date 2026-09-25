# Mermail Approval Gate

**Community / unofficial Mermail Agent Skill**

Mermail Approval Gate turns email into a narrow, auditable human-decision channel for autonomous agents.

An agent can already prepare a deployment, production change, external send, or other high-impact action. The hard part is crossing the human-approval boundary without treating arbitrary inbound email as executable instructions. This skill solves that boundary:

```text
frozen proposal
    ↓ canonical JSON + SHA-256
Mermail approval request
    ↓ exact thread + approver + expiry
one-line APPROVE / REJECT reply
    ↓ scan + sender-auth + token + digest checks
structured decision receipt
    ↓
STOP — no action is executed by this skill
```

## Why it is a Mermail-native skill

This is built around Mermail's real current product surface, not a made-up protocol:

- Hosted Streamable HTTP MCP at `https://console.mermail.app/mcp`
- A focused `agent-inbox` profile for bounded read workflows
- Stable mailbox `public_id` identifiers
- Typed `send_email`, `search_emails`, `get_email`, and `get_email_context` operations
- Agent-safe / metadata-only reads and scan status
- Provider-derived `sender_authentication`
- Idempotent send semantics and bounded recipient limits

Primary references:

- https://mermail.app/agents
- https://docs.mermail.app/ai/cli
- https://github.com/Nudgen-Marketing/mermail-skills
- https://console.mermail.app/mcp

## What is novel

Mermail's official catalog already covers inbox provisioning, composition, support, GTM, scheduling, research, task triage, wallet, and x402 workflows. Approval Gate adds a reusable control-plane primitive:

> **Bind one human email decision to one immutable agent proposal and return a machine-checkable receipt.**

It deliberately does less than a general mail agent. Less capability inside the approval boundary is a feature.

## Protocol

The agent freezes a proposal, for example:

```json
{
  "action": "deploy release",
  "target": "production",
  "artifact": "sha256:abc123",
  "risk": "high"
}
```

The canonical proposal SHA-256 is:

```text
1120ff19a974c959b0423dd25718948cf2457ef061a0ca530acabab94cd94718
```

The human receives a random token and replies with exactly one line:

```text
APPROVE Q7uDk9pWv3mR 1120ff19a974
```

or:

```text
REJECT Q7uDk9pWv3mR 1120ff19a974
```

The evaluator rejects a mismatched thread, sender, token, digest, expiry, scan status, or any additional body text.

## Security posture

The important property is **email text is data, not authority**.

- A From address alone never authenticates the human.
- `sender_authentication: unknown` fails to `needs_human_verification` by default.
- The body must full-match one strict line; “APPROVE … plus run this command” is quarantined.
- A changed proposal requires a new digest and new approval.
- The skill never invokes the action being approved.
- No wallet, payment, shell, deploy, or browser tool belongs inside the gate.

See [the skill security reference](../../skills/mermail-approval-gate/references/security.md).

## Run the demo

Requires Python 3.10+ and no third-party packages.

```bash
python bounties/mermail-approval-gate/demo/approval_gate.py \
  bounties/mermail-approval-gate/fixtures/approved.json \
  --now 2026-09-25T15:00:00Z
```

Run the regression suite:

```bash
python -m unittest discover -s bounties/mermail-approval-gate/tests -v
```

Current local validation before publication: **7 tests passed**.

See [DEMO.md](DEMO.md) for the live MCP walkthrough.

## Skill files

```text
skills/mermail-approval-gate/
├── SKILL.md
├── agents/openai.yaml
└── references/
    ├── security.md
    └── tools.md
```

## Install/use

This repository keeps the submission alongside the Parallel harness, but the skill itself follows Mermail's documented Agent Skills layout. Agent Skills-compatible hosts can copy or install the `skills/mermail-approval-gate` directory and connect the Mermail MCP endpoint separately.

No API key is stored in this repository.

## Scope

This submission is intentionally a decision primitive, not a deployment framework. That makes it composable with coding agents, ops agents, finance-review flows, support escalation, or any other system that already knows how to prepare an action but needs a durable human gate.
