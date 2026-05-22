# Branch Log: shadow/raspberry-pi-poc

This log is the durable record for completed work on this shadow branch. After each completed task, append a new entry with the objective, what changed, why it changed, verification, blockers, and the recommended next task.

## 2026-05-22 22:25 EEST - Harden Raspberry Pi sync pipeline

- Commit: `c735be27a409957e24ca4e61b1577547b422f230`
- Objective: improve reliability and completeness of the phone export -> ADB tail -> Raspberry Pi MQTT publish -> desktop client flow.
- Why: this branch has a different scope from `develop`; the Android phone is a local collector and the Raspberry Pi is the bridge. The main failure risk was event loss during buffering, file writing, ADB interruption, MQTT interruption, or process restart.

### Findings Addressed

- Android non-call events could be lost because `SyncManager.publishEvent()` scheduled the delayed buffer publication before the buffered event was inserted.
- Periodic Android status export had no retained job handle, so the local sync loop could not be stopped cleanly.
- `EventExporter` appended lines without synchronization and only flushed the stream, which made ADB tailing more fragile during concurrent writes or sudden process/device interruption.
- `VigSyncService` stop handling did not explicitly stop local monitoring and `SyncManager`.
- `vigsync_consumer.py` tailed from `tail -n 0 -f`, so events written while the ADB stream was down could be skipped.
- `vigsync_consumer.py` relied mostly on in-memory MQTT behavior and had no durable spool for offline publish attempts.
- `vigsync_consumer.py` could overwrite the local config with invalid ADB output.
- `vigsync_manager.py` had hard-coded path/user assumptions and brittle process/systemd command handling.
- `vigsync_gui_client.py` did not normalize broker URLs, cap MQTT queues, report subscribe failures clearly, or dedupe replayed messages.

### Files Changed

- `app/src/main/kotlin/com/vigsync/core/SyncManager.kt`
- `app/src/main/kotlin/com/vigsync/core/EventExporter.kt`
- `app/src/main/kotlin/com/vigsync/core/VigSyncService.kt`
- `rpi_script/vigsync_consumer.py`
- `rpi_script/vigsync_manager.py`
- `rpi_script/vigsync_gui_client.py`

### Verification

- Passed: `python3 -m py_compile rpi_script/vigsync_consumer.py rpi_script/vigsync_manager.py rpi_script/vigsync_gui_client.py`
- Passed: `git diff --check`
- Blocked: `./gradlew :app:compileDebugKotlin` did not reach Kotlin compilation because the local Android SDK setup is broken. `local.properties` points to a Windows SDK path from the Linux shell, and the mounted SDK reports build-tools `36.0.0` missing `aapt`.
- Blocked: pushing this branch failed because GitHub rejected the current SSH identity: `Permission to virgil-av/VigSync.git denied to 24vlh`.

### Recommended Next Task

Fix the local Android SDK/build-tools verification path first, then add Python unit tests around spool flush, replay dedupe, malformed stream lines, and config fallback.
