# Hidden task status on Pixel

`task-status start|step|done|failed TASK DETAIL` posts an Android notification with the task name and concrete current action or observed result. It records each event in `~/.local/state/capability-cartographer/task-events.jsonl`. It uses the existing phone-local ADB connection and Android's shell notification service; no Termux:API process or approval prompt is needed.

`task-run TASK CURRENT_STEP SUCCESS_RESULT -- COMMAND [ARGS...]` posts a start event, runs the command, and posts completion only after exit 0. The caller must supply a result that the command actually verifies. On failure it posts a failure event. Long tasks can call `task-status step` during execution.

The updated `ghostcatalog` calls this channel automatically: it names the app being read on the ghost display and reports the extracted version, permission count, and repository after saving the snapshot.

This covers jobs launched through these wrappers and ParallelHarness's existing run notifications. It does not intercept arbitrary ChatGPT tasks or other Android apps. Android's `cmd notification post` uses one notification ID, so the latest state replaces the prior visible status; the JSONL retains event history.

Install:

```sh
cp scripts/task-complete/task-{status,run} ~/.local/bin/
cp scripts/ghost-ui/ghostcatalog ~/.local/bin/
chmod 755 ~/.local/bin/task-{status,run} ~/.local/bin/ghostcatalog
```
