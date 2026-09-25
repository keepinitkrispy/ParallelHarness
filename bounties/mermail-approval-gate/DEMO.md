# Demo: Mermail Approval Gate

This demo is deterministic and requires no credentials. It models the normalized fields the live skill receives after Mermail mailbox selection, delivery, candidate discovery, and a bounded `get_email` read.

## 1. Valid approval

From the repository root:

```bash
python bounties/mermail-approval-gate/demo/approval_gate.py \
  bounties/mermail-approval-gate/fixtures/approved.json \
  --now 2026-09-25T15:00:00Z
```

Expected decision:

```json
{
  "status": "approved",
  "decision": "APPROVE",
  "proposal_digest_prefix": "1120ff19a974"
}
```

The full output also binds the decision to the proposal SHA-256, approver, thread, request/reply message IDs, and expiry.

## 2. Security regression suite

```bash
python -m unittest discover \
  -s bounties/mermail-approval-gate/tests -v
```

The suite covers:

1. exact authenticated approval;
2. exact authenticated rejection;
3. wrong-thread quarantine;
4. prompt-injection text appended to a valid approval;
5. unknown sender authentication;
6. proposal-digest mismatch;
7. expiry.

All seven tests pass with Python's standard library only.

## 3. Live Mermail walkthrough

With a Mermail full MCP connection:

1. `list_mailboxes({})` and choose one ready mailbox.
2. Freeze a proposal and calculate its digest with the demo helper.
3. Preview the exact approval request.
4. `send_email` it once to the configured human approver.
5. Record the returned thread/message ID and timestamp.
6. Use bounded `search_emails` with exact approver, recipient, subject/hash, and `date_start`.
7. Validate one candidate with metadata-only `get_email`.
8. Fetch bounded clean content for that same message.
9. Feed only the normalized request/reply record into the evaluator.
10. Observe the structured receipt.
11. Stop. The skill never executes the proposed action.

## Why a mock mode matters

A Mermail API key is a credential and should not be committed into a public bounty repo. The deterministic fixture makes the core protocol inspectable and testable without secrets, while the skill docs map each fixture field back to a real Mermail tool result.
