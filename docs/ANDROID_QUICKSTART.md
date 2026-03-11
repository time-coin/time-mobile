# Android Quick Start Guide

## Prerequisites

- **Android Studio**: Hedgehog (2023.1.1) or newer
- **Java**: JDK 17 or newer
- **Android SDK**: Minimum API 26 (Android 8.0), Target API 34 (Android 14)

## Setup

1. **Install Android Studio**
   - Download from [developer.android.com](https://developer.android.com/studio)
   - Install Android SDK and build tools

2. **Clone the Repository**
   ```bash
   git clone https://github.com/TimeCoinProject/time-coin-mobile.git
   cd time-coin-mobile
   ```

3. **Open in Android Studio**
   - Launch Android Studio
   - File → Open
   - Navigate to `time-coin-mobile/android/` directory
   - Click "OK" and wait for Gradle sync

## Build from Command Line

### Debug Build
```bash
cd android
./gradlew assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`

### Release Build
```bash
cd android
./gradlew assembleRelease
```

Requires signing configuration in `android/app/build.gradle.kts`

## Run on Device/Emulator

### Using Android Studio
1. Connect device via USB or start emulator
2. Click the "Run" button (green triangle)
3. Select target device

### Using Command Line
```bash
cd android
./gradlew installDebug
```

## Testing

### Unit Tests
```bash
./gradlew test
```

### Instrumentation Tests
```bash
./gradlew connectedAndroidTest
```

## Project Structure

```
android/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── kotlin/com/timecoin/wallet/
│   │   │   │   ├── network/
│   │   │   │   │   ├── TcpProtocolClient.kt
│   │   │   │   │   └── HttpApiClient.kt
│   │   │   │   ├── wallet/
│   │   │   │   │   ├── Wallet.kt
│   │   │   │   │   ├── Bip39.kt
│   │   │   │   │   └── AddressDerivation.kt
│   │   │   │   ├── storage/
│   │   │   │   │   ├── WalletDatabase.kt
│   │   │   │   │   └── SecurePreferences.kt
│   │   │   │   ├── ui/
│   │   │   │   │   ├── MainActivity.kt
│   │   │   │   │   ├── SendScreen.kt
│   │   │   │   │   ├── ReceiveScreen.kt
│   │   │   │   │   └── HistoryScreen.kt
│   │   │   │   └── fcm/
│   │   │   │       └── TimeCoinMessagingService.kt
│   │   │   ├── res/
│   │   │   └── AndroidManifest.xml
│   │   ├── test/
│   │   └── androidTest/
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Troubleshooting

### Gradle Sync Failed
- File → Invalidate Caches / Restart
- Check internet connection for dependency downloads

### SDK Not Found
- Tools → SDK Manager
- Install required SDK platforms and build tools

### Emulator Issues
- Tools → AVD Manager
- Create new virtual device with API 26+

## Next Steps

- Review [ARCHITECTURE.md](ARCHITECTURE.md) for app architecture
- Review [TCP_PROTOCOL.md](TCP_PROTOCOL.md) for network protocol
- Read [CONTRIBUTING.md](../CONTRIBUTING.md) for development guidelines
