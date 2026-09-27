# Hidden task review on Pixel

A task launched with `task-run TASK WHY CURRENT_STEP VERIFIED_RESULT -- COMMAND` posts one start notification naming the task and reason. It opens a local detail page when tapped; that request sends SIGSTOP to the task's process group. The page shows reason, current status and meaningful updates, with a Resume button that sends SIGCONT. Completion or failure replaces the same task notification with its actual result. `task-status milestone TASK DETAIL TOKEN` can send an occasional substantive update. Individual shell commands and UI taps do not notify.

The tiny Task Review app owns notification PendingIntents. Shell notifications cannot attach a working tap action on this Pixel. Source/build instructions are under `android/`; its manifest restricts shell broadcast to callers with Android DUMP permission. The review service binds only 127.0.0.1:49173, uses an unguessable token in each page URL, and does not poll. A local event ledger is `~/.local/state/capability-cartographer/task-events.jsonl`. Task records are in `tasks/<token>.json`.

Installed on Pixel:
- `~/.local/bin/task-status`, `task-run`, and `task-review-server`
- `dev.keepinitkrispy.taskreview` APK signed with a device-local key
- `~/.termux/boot/start-task-review` starts the lightweight review service after reboot
- `ghostcatalog` is wrapped in `task-run` and reports actual app/version/permission/source findings.

Verify with a harmless timed job. Start `task-run 'Pause test' 'Verify pause' 'Waiting' 'Finished' -- sleep 30`; open its notification, verify the page shows Paused and the process is T (stopped), then press Resume. The completed record should show the outcome. The Pixel's physical screen is intentionally used only when the user opens the task.

Scope: these notifications cover work launched through the runner. ParallelHarness has its own independent agent-run notifications. This does not intercept every ChatGPT conversation or arbitrary Android process. If Android kills the review service, task-run starts it for the next job; a prior notification shows an explanatory fallback page until it restarts. The shell notification experiment did not support a tap action and has been superseded.
