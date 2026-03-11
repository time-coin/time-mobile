# Copilot Instructions — TIME Coin Mobile Wallet

## Build & Test Commands

All Android commands run from the `android/` directory (or via npm scripts from the root).

```bash
# Build
cd android && ./gradlew assembleDebug        # or: npm run android:build
cd android && ./gradlew assembleRelease       # or: npm run android:release

# Test
cd android && ./gradlew test                  # all unit tests (or: npm run android:test)
cd android && ./gradlew :app:testDebugUnitTest --tests "com.timecoin.wallet.SomeTest"  # single test class
cd android && ./gradlew connectedAndroidTest  # instrumentation tests (requires emulator/device)

# Clean
cd android && ./gradlew clean                 # or: npm run android:clean
```

Formatting uses **ktlint**. No CI pipeline exists yet.

## Architecture

This is a cryptocurrency wallet app for TIME Coin with a hybrid notification strategy:

- **Foreground** — persistent TCP connection to a masternode (port 24100 testnet / 24101 mainnet) for real-time transaction notifications with sub-second latency.
- **Background** — Firebase Cloud Messaging (FCM) push notifications wake the app when closed.
- **Fallback** — HTTP REST API for wallet sync and transaction submission.

### Wallet & Crypto

Uses **BitcoinJ** for BIP-39 mnemonic generation and BIP-44 HD address derivation. Private keys are stored in the Android Keystore (never in app storage). Local data is encrypted with AES-256. Transactions are signed on-device and submitted via HTTP.

### TCP Protocol

Messages are length-prefixed JSON: `[4-byte big-endian length][UTF-8 JSON]`. Key message types: `RegisterXpub`, `XpubRegistered`, `NewTransactionNotification`, `UtxoUpdate`. See `docs/TCP_PROTOCOL.md` for the full spec.

### Data Flow

- **Send:** User input → validate → build TX → sign (Keystore) → submit (HTTP) → monitor (TCP) → update UI
- **Receive:** Masternode → TCP notification → update balance → show notification → update history
- **Background:** Masternode → FCM → OS wakes app → fetch details → update state → show notification

## Android Project Conventions

- **Language:** Kotlin 1.9+ with JDK 17
- **UI:** Jetpack Compose with Material 3 theming (`ui/theme/`)
- **DI:** Hilt — the `Application` class and `MainActivity` are both `@AndroidEntryPoint`/`@HiltAndroidApp` annotated
- **Database:** Room
- **Networking:** OkHttp + Retrofit for HTTP; raw sockets for TCP
- **QR Codes:** ZXing
- **Min SDK:** 26 (Android 8.0) / **Target SDK:** 34 (Android 14)

### Package Structure

All Kotlin sources live under `com.timecoin.wallet`:

```
wallet/              # Wallet & crypto logic (BIP-39, BIP-44, key management)
network/             # TCP protocol client, HTTP API client
storage/             # Room database, encrypted preferences
ui/                  # Compose screens (MainActivity, Send, Receive, History)
ui/theme/            # Material 3 colors, typography, theme composables
fcm/                 # Firebase Cloud Messaging service
```

### Security Considerations

Any code touching private keys, mnemonics, or signing must use the Android Keystore — never store secrets in SharedPreferences or Room directly. Biometric authentication is required for transactions. Certificate pinning is expected for API calls. Report security issues privately to `security@time-coin.io`.

## iOS (Phase 2 — Planned)

Swift 5.9+ / SwiftUI / iOS 15+. The `ios/` directory has a skeletal structure and Podfile but no implementation yet.
