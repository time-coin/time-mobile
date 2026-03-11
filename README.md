# TIME Coin Mobile Wallet

Android wallet for the TIME Coin cryptocurrency, ported from the
[desktop wallet](https://github.com/user/time-wallet) (Rust/egui) to
Kotlin + Jetpack Compose.

## Features

- 🔐 BIP-39 mnemonic + SLIP-0010 (Ed25519) HD wallet — same key derivation as the desktop wallet
- 💰 Send / receive TIME coins with UTXO-based transactions
- 📊 Transaction history with instant-finality status
- 📷 QR code scanning to fill send addresses
- ⚡ Real-time notifications via WebSocket (masternode connection)
- 🌐 Mainnet / testnet switching
- 🔒 AES-256-GCM encrypted wallet storage (Argon2id KDF)
- 📡 Automatic peer discovery from `time-coin.io` API
- 📒 Address book / contacts

## Requirements

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17+
- Minimum SDK 26 (Android 8.0)
- Target SDK 34 (Android 14)

## Quick Start

```bash
cd android

# Debug build
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run a single test class
./gradlew :app:testDebugUnitTest --tests "com.timecoin.wallet.crypto.AddressTest"

# Instrumentation tests (requires emulator or device)
./gradlew connectedAndroidTest

# Clean
./gradlew clean
```

Or from the repo root via npm:

```bash
npm run android:build    # assembleDebug
npm run android:test     # unit tests
npm run android:clean    # clean
```

## Architecture

The app is a **thin client** — all blockchain state lives on masternodes.
The wallet only stores keys locally and queries masternodes via JSON-RPC.

```
┌──────────────────────────────────────────────┐
│                  Android App                  │
├──────────────────────────────────────────────┤
│  UI Layer          Jetpack Compose screens    │
│  ViewModel         State + event handling     │
│  Service Layer     Masternode polling + WS    │
├──────────────────────────────────────────────┤
│  Wallet            Keys, signing, HD derive   │
│  Crypto            Ed25519, SLIP-0010, AES    │
│  Network           JSON-RPC + WebSocket       │
│  Database          Room (contacts, tx cache)  │
└──────────────────────────────────────────────┘
          │                         │
     JSON-RPC (HTTP)          WebSocket
   (port 24001/24101)      (real-time tx)
          │                         │
     ┌────┴─────────────────────────┴────┐
     │           Masternodes              │
     └────────────────────────────────────┘
```

### Key Design Decisions

| Area | Desktop (Rust) | Mobile (Kotlin) |
|------|---------------|-----------------|
| Crypto | ed25519-dalek | BouncyCastle Ed25519 |
| HD derivation | SLIP-0010 (HMAC-SHA512) | Same algorithm, BouncyCastle |
| Address format | TIME1/TIME0 + Base58 | Same format (portable) |
| Encryption | AES-256-GCM + Argon2 | BouncyCastle AES-GCM + Argon2 |
| HTTP client | reqwest | Ktor |
| WebSocket | tokio-tungstenite | OkHttp WebSocket |
| Database | sled | Room |
| UI | egui | Jetpack Compose |

### Network Ports

| Network | JSON-RPC | WebSocket |
|---------|----------|-----------|
| Mainnet | 24001 | ws://{ip}:24001/ws |
| Testnet | 24101 | ws://{ip}:24101/ws |

## Project Structure

```
android/app/src/main/kotlin/com/timecoin/wallet/
├── crypto/          # Ed25519, SLIP-0010, BIP-39, address encoding
├── model/           # Data classes (UTXO, Transaction, Balance, etc.)
├── network/         # Masternode JSON-RPC client, WebSocket, peer discovery
├── wallet/          # Wallet manager, encryption, wallet file I/O
├── db/              # Room database, DAOs, entities
├── service/         # Background service connecting wallet ↔ network ↔ UI
├── ui/              # Compose screens and navigation
│   ├── theme/       # Material 3 colors, typography
│   ├── screen/      # Individual screens (Overview, Send, Receive, etc.)
│   └── component/   # Reusable composables
└── di/              # Hilt dependency injection modules
```

## Security

- Private keys are encrypted with AES-256-GCM; the encryption key is derived
  from the user's password via Argon2id.
- Mnemonic phrases are never stored in plaintext after initial setup.
- Wallet files use the same format as the desktop wallet for portability.
- Report security issues privately to: **security@time-coin.io**

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Licensed under the same terms as TIME Coin core:
MIT License and Apache License 2.0.
