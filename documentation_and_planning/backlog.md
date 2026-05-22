# Backlog: shadow/raspberry-pi-poc

This backlog is branch-local. Keep source branches untouched; all implementation work for this track belongs on `shadow/raspberry-pi-poc`.

## Now

- Improve Pi consumer durability with persistent read offsets or explicit acknowledged delivery tracking.

## Next

- Add a practical smoke-test checklist for phone connected over ADB, USB reconnect, MQTT disconnect/reconnect, and desktop client display.

## Later

- Make the Raspberry Pi service installer more configurable for non-systemd or multiple-user environments.
- Add structured operational logs or status output for the Pi bridge, including current config source, ADB state, MQTT state, spool size, and last delivered event time.
- Review whether the desktop GUI should persist connection settings and recent event state between launches.

## Blocked

- Online push is blocked by GitHub permissions for the current SSH identity: `Permission to virgil-av/VigSync.git denied to 24vlh`.

## Done

- Hardened the Raspberry Pi sync pipeline in commit `c735be27a409957e24ca4e61b1577547b422f230`.
- Added branch-local task log and backlog documentation.
- Restored local Android verification using `/home/vlahus/Android/Sdk`; `compileDebugKotlin` and `lintDebug` now pass on this branch.
- Added Python unit tests for spool flush, replay dedupe, malformed payload handling, disconnected publish spooling, and config fallback.
