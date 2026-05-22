# Backlog: shadow/develop

This backlog is branch-local. Keep source branches untouched; all implementation work for this track belongs on `shadow/develop`.

## Now

- Add focused tests or testable helpers for MQTT publish queueing, event buffering, and device stale/offline behavior.

## Next

- Continue hardening background survival: battery optimization UX, boot recovery, network recovery, and service state reporting.
- Add a practical smoke-test checklist for connect, disconnect, reconnect, publish-while-offline, heartbeat/status, event receive, and notification display.

## Later

- Improve diagnostics so the UI can show connection state, queued publish count, last publish result, last received message, and last processing error.
- Review DataStore/Room persistence boundaries for durable local outbox behavior beyond the current in-memory MQTT queue.
- Review whether the MQTT v3/v5 detection cache should expire or be invalidated when broker settings change.

## Blocked

- Online push is blocked by GitHub permissions for the current SSH identity: `Permission to virgil-av/VigSync.git denied to 24vlh`.

## Done

- Stabilized the MQTT sync engine in commit `058b4d85b4c47704837a38559703c958f7e11e3f`.
- Added branch-local task log and backlog documentation.
- Restored local Android verification using `/home/vlahus/Android/Sdk`; `compileDebugKotlin` and `lintDebug` now pass on this branch.
