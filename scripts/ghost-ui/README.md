# Ghost UI semantic bridge

Verified on Pixel 6 / Android 17, 2026-09-27. Uses existing ghostctl display and local ADB. No persistent daemon, screenshots, OCR, accessibility permission dialog, or external infrastructure.

## Build/install (Termux)

Requires javac, d8 and Android API 35 android.jar (existing ghost-display toolchain).

```
mkdir -p ~/.local/libexec/ghost-ui/{src,classes,dex}
cp GhostUI.java ~/.local/libexec/ghost-ui/src/
javac -source 8 -target 8 -cp ~/.local/libexec/ghost-display/android-35.jar -d ~/.local/libexec/ghost-ui/classes GhostUI.java
d8 --lib ~/.local/libexec/ghost-display/android-35.jar --output ~/.local/libexec/ghost-ui/dex ~/.local/libexec/ghost-ui/classes/GhostUI.class
adb -s emulator-5554 push ~/.local/libexec/ghost-ui/dex/classes.dex /data/local/tmp/ghost-ui.dex
cp ghostui ghostcatalog ~/.local/bin/
chmod 755 ~/.local/bin/ghostui ~/.local/bin/ghostcatalog
```

## Callable tools

- `ghostui dump`: structured windows/nodes ONLY from ghost display; text, descriptions, resource IDs, bounds, flags, package.
- `ghostui click text 'Pensum' me.timschneeberger.shizustore`: unique visible selector → display-specific tap.
- `ghostui set-text id RESOURCE PACKAGE VALUE`: semantic ACTION_SET_TEXT (implemented, not yet live-tested).
- `ghostcatalog`: append visible store data and repository URLs to capability-cartographer/catalog-discoveries.jsonl for next acquisition cycle.

UiAutomation preserves existing accessibility services with connect flag 1. Connections disconnect after each call. Wrapper serializes runs, limits commands to 25 seconds, rejects display 0, and requires unique visible/enabled selectors.

## Verified composition

ShizuStore on display 3 → select Pensum → select More about this app → structured description. Display 0 remained the same Threads activity/task (12015) across detailed-description navigation. Retrieved candidate version 0.1.0-beta, source https://github.com/troikoss/Pensum, 2 requested permissions, 8 MB, and process-monitoring description. These are catalog claims; Pensum was not installed or independently tested.

## Critical limitation discovered

F-Droid Repositories / Navigate up moved its task to physical display 0 even when actions were targeted to display 3. Both accessibility actions and display-specific input showed this behavior. Recovered task with `am display move-stack 12011 3`. F-Droid in-place Latest tab works on display 3. Do not treat arbitrary app navigation as display-safe.

Wrapper detects an existing ghost task that changed displays immediately after an action, moves it back, and reports escape. This is recovery, NOT preventive containment; brief disruption, late transitions and newly created tasks remain possible. ShizuStore tested routes remained on display 3. Snapshot checks cannot exclude every transient change.

No perpetual polling or workloads are installed. Thermal status 0 at start, 1 at end; experiments stopped after verification.
