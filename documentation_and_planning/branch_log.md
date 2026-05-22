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
- Remaining warnings are non-blocking: deprecated Gradle/Android options, Room schema export warning, deprecated telephony APIs, Kotlin cleanup warnings in `MqttManager`, and deprecated Compose auto-mirrored icons.

### Known Blockers

- Online push is still blocked by GitHub permissions for the current SSH identity: `Permission to virgil-av/VigSync.git denied to 24vlh`.

### Recommended Next Task

Add focused tests or testable helpers for MQTT publish queueing, event buffering, and device stale/offline behavior.

## 2026-05-22 23:07 EEST - Add reliability helper unit tests

- Commit: pending documentation commit for this task.
- Objective: add focused JVM unit coverage for the reliability behavior introduced in the MQTT sync engine.
- Why: the previous reliability pass added important queueing, buffering, and stale-device behavior that needed fast automated coverage without requiring Android devices, MQTT brokers, or HiveMQ client internals.

### Work Completed

- Added `MqttPublishQueue` as a pure-Kotlin bounded FIFO helper for disconnected publish buffering.
- Added `EventBufferPolicy` as a pure-Kotlin helper for event keys, debounce acceptance, and immediate-vs-buffered publish decisions.
- Added `DeviceStalenessPolicy` as a pure-Kotlin helper for stale/offline threshold calculation.
- Refactored `MqttManager` and `SyncManager` only enough to use these helpers while preserving existing behavior.
- Added JVM unit tests under `app/src/test/kotlin` for queue order, capacity eviction, payload copying, requeue-on-failure ordering, event buffering/debounce, and stale/offline threshold behavior.

### Files Changed

- `app/src/main/kotlin/com/vigsync/core/mqtt/MqttPublishQueue.kt`
- `app/src/main/kotlin/com/vigsync/core/EventBufferPolicy.kt`
- `app/src/main/kotlin/com/vigsync/core/DeviceStalenessPolicy.kt`
- `app/src/main/kotlin/com/vigsync/core/mqtt/MqttManager.kt`
- `app/src/main/kotlin/com/vigsync/core/SyncManager.kt`
- `app/src/test/kotlin/com/vigsync/core/mqtt/MqttPublishQueueTest.kt`
- `app/src/test/kotlin/com/vigsync/core/EventBufferPolicyTest.kt`
- `app/src/test/kotlin/com/vigsync/core/DeviceStalenessPolicyTest.kt`

### Verification

- Passed: `./gradlew :app:testDebugUnitTest`
- Passed: `./gradlew :app:compileDebugKotlin`
- Passed: `./gradlew :app:lintDebug`
- Remaining warnings are non-blocking and unchanged in character: deprecated Gradle/Android options, Room schema export warning, deprecated telephony APIs, Kotlin cleanup warnings in `MqttManager`, and deprecated Compose auto-mirrored icons.

### Known Blockers

- Online push is still blocked by GitHub permissions for the current SSH identity: `Permission to virgil-av/VigSync.git denied to 24vlh`.

### Recommended Next Task

Continue hardening background survival: battery optimization UX, boot recovery, network recovery, and service state reporting.

## 2026-05-22 23:42 EEST - Harden background survival and service state

- Commit: `c56e739845f977cb8b1c699382f661943a23dec4`
- Objective: make Android service recovery, background survival, network recovery, and foreground-service state deterministic.
- Why: the service was previously restored heuristically from MQTT config, the dashboard polled static booleans after delays, and network callbacks could trigger duplicate reconnect work.

### Work Completed

- Added explicit persisted `serviceEnabled` intent alongside existing `syncEnabled`.
- Updated dashboard service/sync toggles so user intent is saved before boot or process recovery needs it.
- Reworked boot recovery to use a pure `BootRecoveryPolicy` and restore service-only or sync mode only when desired state and MQTT config allow it.
- Added `ServiceRuntimeState` and `MqttServiceRuntime` so foreground service state is reported as a `StateFlow`.
- Updated `MqttService` lifecycle, sync actions, observer state, network callbacks, and notification text from the runtime state.
- Added `NetworkRecoveryPolicy` to debounce duplicate reconnect callbacks and ignore networks without internet capability.
- Added `BatteryOptimizationPolicy`, manifest support for battery optimization exemption requests, and settings UX that prefers the Android exemption request with app-settings fallback.
- Added focused JVM tests for boot restore decisions, network recovery debounce, service state reduction, and battery optimization request selection.

### Files Changed

- `app/src/main/AndroidManifest.xml`
- `app/src/main/kotlin/com/vigsync/core/BootReceiver.kt`
- `app/src/main/kotlin/com/vigsync/core/BootRecoveryPolicy.kt`
- `app/src/main/kotlin/com/vigsync/core/BatteryOptimizationPolicy.kt`
- `app/src/main/kotlin/com/vigsync/core/mqtt/MqttService.kt`
- `app/src/main/kotlin/com/vigsync/core/mqtt/NetworkRecoveryPolicy.kt`
- `app/src/main/kotlin/com/vigsync/core/mqtt/ServiceRuntimeState.kt`
- `app/src/main/kotlin/com/vigsync/data/prefs/AppPreferences.kt`
- `app/src/main/kotlin/com/vigsync/feature/ui/screens/DashboardViewModel.kt`
- `app/src/main/kotlin/com/vigsync/feature/ui/screens/SettingsScreen.kt`
- `app/src/test/kotlin/com/vigsync/core/BootRecoveryPolicyTest.kt`
- `app/src/test/kotlin/com/vigsync/core/BatteryOptimizationPolicyTest.kt`
- `app/src/test/kotlin/com/vigsync/core/mqtt/NetworkRecoveryPolicyTest.kt`
- `app/src/test/kotlin/com/vigsync/core/mqtt/ServiceRuntimeStateTest.kt`
- `documentation_and_planning/backlog.md`
- `documentation_and_planning/branch_log.md`

### Verification

- Passed: `./gradlew :app:testDebugUnitTest`
- Passed: `./gradlew :app:compileDebugKotlin`
- Passed: `./gradlew :app:lintDebug`
- Remaining warnings are non-blocking and unchanged in character: deprecated Gradle/Android options, Room schema export warning, deprecated telephony APIs, Kotlin cleanup warnings in `MqttManager`, and deprecated Compose auto-mirrored icons.

### Known Blockers

- Online push is still blocked by GitHub permissions for the current SSH identity: `Permission to virgil-av/VigSync.git denied to 24vlh`.

### Recommended Next Task

Add a practical smoke-test checklist for connect, disconnect, reconnect, publish-while-offline, heartbeat/status, event receive, and notification display.
