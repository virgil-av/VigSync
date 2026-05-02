# VigSync

**Secure Real-Time Android Event Synchronization**

VigSync is a high-performance Kotlin-based Android application designed to synchronize phone events—including SMS, phone calls, and app notifications (WhatsApp, Telegram, etc.)—across multiple devices in real-time. Built with a focus on privacy, security, and modern Android standards, VigSync ensures you never miss an alert, no matter which device you are holding.

## 🚀 Key Features

- **Real-Time Sync**: Instant synchronization of SMS, missed calls, and notifications using the MQTT protocol.
- **Universal Capture**: Robust support for messaging apps like WhatsApp and Telegram using advanced `MessagingStyle` notification parsing.
- **Two-Tier Storage**: Industrial-grade "Hot/Cold" storage architecture using Room Database ensures zero data loss even during network instability.
- **Live Monitoring**: Dashboard view showing paired devices' connection status and battery levels.
- **Modern UI**: Material 3 Jetpack Compose interface with a color-coded event timeline.
- **Battery Efficient**: 60-second heartbeat system with "Unrestricted" background support.
- **Future Proof**: Fully compliant with **Android 15's 16 KB page alignment** requirements.

## 🔒 Security Model

VigSync is designed with a "Privacy First" philosophy, utilizing multiple layers of protection:

1. **End-to-End Encryption (E2EE)**: Every event is encrypted locally using **AES-GCM (256-bit)** before it ever leaves your device. The shared key is exchanged securely via QR code pairing.
2. **Transport Layer Security (SSL/TLS)**: Supports secure connections to MQTT brokers, ensuring your metadata is protected from man-in-the-middle attacks.
3. **Private Cluster Support**: While it works out of the box with public brokers, VigSync allows you to connect to a **Private HiveMQ Cluster** with full username/password authentication.
4. **No Central Server**: Data flows directly through your chosen MQTT broker; no third-party server stores your plain-text messages.

## 🛠 Technical Details

- **Language**: 100% Kotlin
- **UI Framework**: Jetpack Compose
- **Concurrency**: Kotlin Coroutines & Flow
- **Local Database**: Room (SQLCipher compatible)
- **MQTT Client**: HiveMQ MQTT Client (v5 support)
- **Architecture**: Staged Event-Driven Pipeline (Network -> Hot Storage -> Processor -> Cold Storage -> UI)

## 📦 How to Build

### Prerequisites
- Android Studio Ladybug (2024.2.1) or newer
- JDK 17
- Android SDK 35 (Platform) and Build Tools 35.0.0+

### Build Steps
1. Clone the repository.
2. Open the project in Android Studio.
3. Allow Gradle to sync and download dependencies.
4. Build the APK via the terminal:
   ```bash
   ./gradlew assembleDebug
   ```
5. To verify 16 KB page alignment (Android 15+):
   ```bash
   zipalign -v -c -P 16 4 app-debug.apk
   ```

## 🤝 Pairing Guide
1. Install VigSync on two or more Android devices.
2. On Device A, go to the **Pair** tab and tap **Show My QR**.
3. On Device B, go to the **Pair** tab, tap **Scan QR**, and point the camera at Device A's screen.
4. Once the success dialog appears, your devices are synced!

## ⚖️ License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.
