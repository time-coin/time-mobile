# TIME Coin Android App

## Setup

1. Open Android Studio
2. File → Open → Select this `android/` directory
3. Wait for Gradle sync
4. Run on emulator or device

## Build

### Debug build
```bash
./gradlew assembleDebug
```

### Release build (requires signing key)
```bash
./gradlew assembleRelease
```

## Testing

### Unit tests
```bash
./gradlew test
```

### Instrumentation tests
```bash
./gradlew connectedAndroidTest
```

## Project Structure

```
app/
├── src/
│   ├── main/
│   │   ├── kotlin/com/timecoin/wallet/
│   │   │   ├── network/
│   │   │   │   ├── TcpProtocolClient.kt
│   │   │   │   └── HttpApiClient.kt
│   │   │   ├── wallet/
│   │   │   │   ├── Wallet.kt
│   │   │   │   ├── Bip39.kt
│   │   │   │   └── AddressDerivation.kt
│   │   │   ├── storage/
│   │   │   │   ├── WalletDatabase.kt
│   │   │   │   └── SecurePreferences.kt
│   │   │   ├── ui/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── SendScreen.kt
│   │   │   │   ├── ReceiveScreen.kt
│   │   │   │   └── HistoryScreen.kt
│   │   │   └── fcm/
│   │   │       └── TimeCoinMessagingService.kt
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   ├── test/
│   └── androidTest/
└── build.gradle.kts
```

## Dependencies

- **Jetpack Compose**: Modern UI toolkit
- **BitcoinJ**: BIP-39/BIP-44 wallet implementation
- **OkHttp**: HTTP/TCP networking
- **Room**: Local database
- **Hilt**: Dependency injection
- **Firebase**: Cloud messaging for push notifications

## Configuration

1. Add `google-services.json` to `app/` directory for FCM
2. Configure signing key in `app/build.gradle.kts` for release builds
3. Update masternode endpoints in `network/Constants.kt`

See [ANDROID_QUICKSTART.md](../docs/ANDROID_QUICKSTART.md) for detailed instructions.
