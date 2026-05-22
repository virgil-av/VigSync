# Branch Log: shadow/develop

This log is the durable record for completed work on this shadow branch. After each completed task, append a new entry with the objective, what changed, why it changed, verification, blockers, and the recommended next task.

## 2026-05-22 22:17 EEST - Stabilize MQTT sync engine

- Commit: `058b4d85b4c47704837a38559703c958f7e11e3f`
- Objective: improve reliability and completeness of the Android-to-Android MQTT sync engine.
- Why: this branch is the direct Android MQTT sync track. The main failure risk was event loss or stale state during buffering, disconnected publishing, reconnects, service restarts, and heartbeat/status handling.

### Findings Addressed

- Non-call events could be lost because `SyncManager.publishEvent()` scheduled delayed publication before inserting the event into the buffer.
- MQTT publishes were silently dropped while disconnected.
- MQTT reconnect/subscription handling could duplicate subscriptions or fail to restore them cleanly after reconnect.
- MQTT auth was always applied with empty credentials, which made broker behavior less predictable.
- Local events were published without being reliably persisted first.
- Device online status had no deterministic stale/offline transition.
- Foreground service stop/restart behavior did not fully reflect the sync engine state.
- Processing errors were too easy to lose in trace-only logs.

### Files Changed

- `app/src/main/kotlin/com/vigsync/core/SyncManager.kt`
- `app/src/main/kotlin/com/vigsync/core/mqtt/MqttManager.kt`
- `app/src/main/kotlin/com/vigsync/core/mqtt/MqttService.kt`
- `app/src/main/kotlin/com/vigsync/data/local/VigSyncDatabase.kt`

### Verification

- Passed: `git diff --check`
- Blocked: `./gradlew :app:compileDebugKotlin` did not reach Kotlin compilation because the local Android SDK setup is broken. `local.properties` points to a Windows SDK path from the Linux shell, and the mounted SDK reports build-tools `36.0.0` missing `aapt`.
- Blocked: pushing this branch failed because GitHub rejected the current SSH identity: `Permission to virgil-av/VigSync.git denied to 24vlh`.

### Recommended Next Task

Fix the local Android SDK/build-tools verification path first, then add focused tests or testable helpers for MQTT publish queueing, event buffering, and device stale/offline behavior.
