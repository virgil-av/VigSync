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

## 2026-05-22 22:52 EEST - Restore local Android verification

- Commit: pending documentation commit for this task.
- Objective: make Gradle compile/lint verification work from this WSL/Linux workspace without committing machine-specific SDK paths or changing shared Gradle configuration.
- Why: Android verification was previously blocked because the workspace used a Windows SDK path and Windows build-tools from a Linux shell.

### Work Completed

- Installed Linux-compatible Android SDK components under `/home/vlahus/Android/Sdk` using `sdkmanager`.
- Installed/confirmed required components: `platforms;android-35`, `build-tools;35.0.0`, `build-tools;36.0.0`, and `platform-tools`.
- Updated ignored local-only `local.properties` to `sdk.dir=/home/vlahus/Android/Sdk`.
- Ran SDK setup with empty proxy variables unset for the command, because `HTTP_PROXY=` and `HTTPS_PROXY=` caused `sdkmanager` URL parsing errors.
- Kept all SDK path changes out of tracked project files.

### Verification

- Passed: `./gradlew :app:compileDebugKotlin`
- Passed: `./gradlew :app:lintDebug`
- Lint report generated locally at `app/build/reports/lint-results-debug.html`.
- Remaining warnings are non-blocking: deprecated Gradle/Android options, Room schema export warning, deprecated telephony APIs, and deprecated Compose auto-mirrored icons.

### Known Blockers

- Online push is still blocked by GitHub permissions for the current SSH identity: `Permission to virgil-av/VigSync.git denied to 24vlh`.

### Recommended Next Task

Add Python unit tests for `vigsync_consumer.py` spool flush, replay dedupe, malformed stream line handling, and config fallback.

## 2026-05-22 23:14 EEST - Add Raspberry Pi bridge unit tests

- Commit: pending documentation commit for this task.
- Objective: add focused Python unit coverage for the Raspberry Pi bridge reliability behavior.
- Why: the bridge is responsible for not losing phone-exported events during ADB/MQTT interruptions, so its spool, replay, malformed payload, and config fallback paths need fast local coverage without a phone, broker, or external Python test dependency.

### Work Completed

- Added stdlib `unittest` coverage under `rpi_script/tests/`.
- Stubbed the minimal `paho.mqtt.client` module needed to import `vigsync_consumer.py` when local Python does not have `paho-mqtt` installed.
- Added isolated temp-file tests for spool append, successful spool flush/removal, failed publish preservation, replay dedupe, malformed JSON skip, disconnected publish spooling, config fallback, and invalid ADB config protection.

### Files Changed

- `rpi_script/tests/test_vigsync_consumer.py`

### Verification

- Passed: `python3 -m unittest discover -s rpi_script/tests`
- Passed: `python3 -m py_compile rpi_script/vigsync_consumer.py rpi_script/vigsync_manager.py rpi_script/vigsync_gui_client.py`
- Passed: `./gradlew :app:testDebugUnitTest`
- Passed: `./gradlew :app:compileDebugKotlin`
- Passed: `./gradlew :app:lintDebug`
- Remaining Android warnings are non-blocking and unchanged in character: deprecated Gradle/Android options, Room schema export warning, deprecated telephony APIs, and deprecated Compose auto-mirrored icons.

### Known Blockers

- Online push is still blocked by GitHub permissions for the current SSH identity: `Permission to virgil-av/VigSync.git denied to 24vlh`.

### Recommended Next Task

Improve Pi consumer durability with persistent read offsets or explicit acknowledged delivery tracking.

## 2026-05-22 23:29 EEST - Add durable Pi consumer delivery tracking

- Commit: pending commit for this task.
- Objective: make the Raspberry Pi consumer resilient to MQTT disconnects, process restarts, and ADB tail replay without losing or duplicating phone-exported events.
- Why: the prior spool treated a successful Paho `publish()` call as delivered, even though QoS 1 delivery is only complete after MQTT acknowledgement. Replay dedupe was also memory-only, so ADB reconnects or process restarts could replay recently tailed lines.

### Work Completed

- Added `VIGSYNC_STATE_FILE` support for persistent bounded replay fingerprints.
- Changed the JSONL spool to keep structured records with stable IDs, fingerprints, creation timestamps, and publish attempt counts.
- Changed `publish_or_spool()` so valid payloads are durably spooled before they are marked as seen.
- Changed `flush_spool()` so queued records remain on disk while awaiting MQTT QoS acknowledgement.
- Added pending MQTT message ID tracking and removed spool records only from `on_publish()`.
- Kept malformed JSON payloads non-poisoning: they are logged and skipped without updating spool or replay state.
- Extended Python unit tests to cover acknowledgement removal, unacknowledged retry preservation, duplicate replay after restart, and durable connected/disconnected publish behavior.

### Files Changed

- `rpi_script/vigsync_consumer.py`
- `rpi_script/tests/test_vigsync_consumer.py`
- `documentation_and_planning/backlog.md`
- `documentation_and_planning/branch_log.md`

### Verification

- Passed: `python3 -m unittest discover -s rpi_script/tests`
- Passed: `python3 -m py_compile rpi_script/vigsync_consumer.py rpi_script/vigsync_manager.py rpi_script/vigsync_gui_client.py`
- Passed: `./gradlew :app:testDebugUnitTest` (`NO-SOURCE` for Android unit tests on this branch)
- Passed: `./gradlew :app:compileDebugKotlin`
- Passed: `./gradlew :app:lintDebug`
- Remaining warnings are non-blocking and unchanged in character: deprecated Gradle/Android options and dependency constraint sync warnings.

### Known Blockers

- Online push is still blocked by GitHub permissions for the current SSH identity: `Permission to virgil-av/VigSync.git denied to 24vlh`.

### Recommended Next Task

Add a practical smoke-test checklist for phone connected over ADB, USB reconnect, MQTT disconnect/reconnect, and desktop client display.
