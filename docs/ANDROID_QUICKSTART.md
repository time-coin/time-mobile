# Android Quick Start Guide

## Prerequisites

- **Android Studio**: Hedgehog (2023.1.1) or newer
- **Java**: JDK 17 or newer
- **Android SDK**: Minimum API 26 (Android 8.0), Target API 35 (Android 15)

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
│   │   │   │   ├── crypto/
│   │   │   │   │   ├── Address.kt          # TIME1/TIME0 address encoding
│   │   │   │   │   ├── BiometricHelper.kt  # Fingerprint/face unlock
│   │   │   │   │   ├── Encryption.kt       # AES-256-GCM + Argon2id
│   │   │   │   │   ├── Keypair.kt          # Ed25519 key operations
│   │   │   │   │   └── Mnemonic.kt         # BIP-39 + SLIP-0010 derivation
│   │   │   │   ├── db/
│   │   │   │   │   └── WalletDatabase.kt   # Room DB (contacts, tx, settings)
│   │   │   │   ├── di/
│   │   │   │   │   └── DatabaseModule.kt   # Hilt dependency injection
│   │   │   │   ├── model/
│   │   │   │   │   ├── Models.kt           # UTXO, Balance, TransactionRecord
│   │   │   │   │   └── Transaction.kt      # Transaction building/signing
│   │   │   │   ├── network/
│   │   │   │   │   ├── ConfigManager.kt    # time.conf peer configuration
│   │   │   │   │   ├── MasternodeClient.kt # JSON-RPC client (Ktor)
│   │   │   │   │   ├── PeerDiscovery.kt    # Ping-based peer selection
│   │   │   │   │   └── WsNotificationClient.kt # WebSocket notifications
│   │   │   │   ├── service/
│   │   │   │   │   └── WalletService.kt    # Core service (wallet ↔ network ↔ UI)
│   │   │   │   ├── ui/
│   │   │   │   │   ├── MainActivity.kt     # Entry point + navigation
│   │   │   │   │   ├── component/
│   │   │   │   │   │   └── Formatting.kt   # Number/amount formatters
│   │   │   │   │   ├── screen/
│   │   │   │   │   │   ├── ConnectionsScreen.kt
│   │   │   │   │   │   ├── MnemonicScreen.kt
│   │   │   │   │   │   ├── OverviewScreen.kt
│   │   │   │   │   │   ├── PasswordUnlockScreen.kt
│   │   │   │   │   │   ├── PinEntryScreen.kt
│   │   │   │   │   │   ├── QrScannerScreen.kt
│   │   │   │   │   │   ├── ReceiveScreen.kt
│   │   │   │   │   │   ├── SendScreen.kt
│   │   │   │   │   │   ├── SettingsScreen.kt
│   │   │   │   │   │   ├── TransactionDetailScreen.kt
│   │   │   │   │   │   ├── TransactionHistoryScreen.kt
│   │   │   │   │   │   └── WelcomeScreen.kt
│   │   │   │   │   └── theme/
│   │   │   │   │       ├── Color.kt
│   │   │   │   │       ├── Theme.kt
│   │   │   │   │       └── Type.kt
│   │   │   │   ├── wallet/
│   │   │   │   │   └── WalletManager.kt    # Wallet file I/O
│   │   │   │   └── TimeCoinWalletApp.kt    # Hilt application class
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

### Emulator Black Screen
- Try switching GPU mode: AVD Manager → Edit → Emulated Performance → Graphics
- Set to "Software - GLES 2.0" if you see rendering issues
- Or edit `~/.android/avd/<name>.avd/config.ini` and set `hw.gpu.mode = swiftshader_indirect`
- Cold boot the emulator (AVD Manager → three-dot menu → Cold Boot Now)
- If the screen is black but content appears after pressing power: this is a known
  emulator GPU pipeline bug. Switching to software rendering resolves it.

### Emulator Issues
- Tools → AVD Manager
- Create new virtual device with API 26+
- Recommended: Pixel 6 with API 34 for best stability

## Next Steps

- Review [ARCHITECTURE.md](ARCHITECTURE.md) for app architecture
- Review [TCP_PROTOCOL.md](TCP_PROTOCOL.md) for network protocol
- Read [CONTRIBUTING.md](../CONTRIBUTING.md) for development guidelines
