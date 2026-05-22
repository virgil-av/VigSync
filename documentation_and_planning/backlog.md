# Backlog: shadow/develop

This backlog is branch-local. Keep source branches untouched; all implementation work for this track belongs on `shadow/develop`.

## Now

- Fix local Android SDK/build-tools verification so Gradle can compile from this workspace.

## Next

- Add focused tests or testable helpers for MQTT publish queueing, event buffering, and device stale/offline behavior.
- Continue hardening background survival: battery optimization UX, boot recovery, network recovery, and service state reporting.
- Add a practical smoke-test checklist for connect, disconnect, reconnect, publish-while-offline, heartbeat/status, event receive, and notification display.

## Later

- Improve diagnostics so the UI can show connection state, queued publish count, last publish result, last received message, and last processing error.
- Review DataStore/Room persistence boundaries for durable local outbox behavior beyond the current in-memory MQTT queue.
- Review whether the MQTT v3/v5 detection cache should expire or be invalidated when broker settings change.

## Blocked

- Android compile/lint is blocked by the local SDK/build-tools setup: `local.properties` points to a Windows SDK path from Linux, and mounted build-tools `36.0.0` is missing `aapt`.
- Online push is blocked by GitHub permissions for the current SSH identity: `Permission to virgil-av/VigSync.git denied to 24vlh`.

## Done

- Stabilized the MQTT sync engine in commit `058b4d85b4c47704837a38559703c958f7e11e3f`.
- Added branch-local task log and backlog documentation.
