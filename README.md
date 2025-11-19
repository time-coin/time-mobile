# TIME Coin Mobile Wallet

Cross-platform mobile wallet for TIME Coin cryptocurrency.

## Features

- 📱 Native Android app (Phase 1)
- 🍎 Native iOS app (Phase 2 - planned)
- 🔐 Secure BIP-39/BIP-44 HD wallet
- ⚡ Real-time transaction notifications via TCP
- 🔔 Push notifications via FCM (background)
- 💰 Send/receive TIME coins
- 📊 Transaction history
- 🔒 Biometric authentication

## Project Status

- ✅ Android MVP: In Development
- ⏳ iOS: Planned

## Documentation

- [Android Quick Start](docs/ANDROID_QUICKSTART.md)
- [TCP Protocol](docs/TCP_PROTOCOL.md)
- [Architecture](docs/ARCHITECTURE.md)

## Requirements

### Android
- Android Studio Hedgehog (2023.1.1) or newer
- Minimum SDK: 26 (Android 8.0)
- Target SDK: 34 (Android 14)
- Kotlin 1.9+

### iOS (Future)
- Xcode 15+
- iOS 15+
- Swift 5.9+

## Quick Start

### Android Development

```bash
# Open Android project
cd android
# Open in Android Studio: File → Open → select android/
Build from Command Line
cd android
./gradlew assembleDebug
Architecture
┌─────────────────────────────────────────┐
│          Mobile App Architecture         │
├─────────────────────────────────────────┤
│                                           │
│  App Foreground → TCP (port 24100)      │
│    - Real-time notifications              │
│    - < 1 second latency                   │
│                                           │
│  App Background → FCM Push               │
│    - Wake app when closed                 │
│    - Battery efficient                    │
│                                           │
│  HTTP API Fallback                       │
│    - Wallet sync                          │
│    - Transaction submission               │
│                                           │
└─────────────────────────────────────────┘
Tech Stack
Android
Language: Kotlin
UI: Jetpack Compose
Crypto: BitcoinJ (BIP-39, BIP-44)
Network: OkHttp, Raw Sockets
Database: Room
Security: Android Keystore, Biometric
iOS (Planned)
Language: Swift
UI: SwiftUI
Crypto: BitcoinKit
Network: URLSession, Raw Sockets
Contributing
See CONTRIBUTING.md

Security
Report security issues to: security@time-coin.io

License
Licensed under the same terms as TIME Coin core:

MIT License: LICENSE-MIT
Apache License 2.0: LICENSE-APACHE
Links
TIME Coin Main Repository
Documentation
Website EOF
5. Create .gitignore
cat > .gitignore << 'EOF'

Android
android/.gradle/ android/build/ android/local.properties android/.idea/ android/*.iml android/app/build/ android/app/release/ android/.DS_Store

iOS
ios/Pods/ ios/.xcworkspace !ios/.xcworkspace/contents.xcworkspacedata ios/*.xcuserstate ios/DerivedData/ ios/.DS_Store

Secrets
*.keystore *.jks google-services.json GoogleService-Info.plist .env secrets/

IDE
.vscode/ .idea/

OS
.DS_Store Thumbs.db EOF

6. Create initial docs
cat > docs/ARCHITECTURE.md << 'EOF'

TIME Coin Mobile Architecture
Overview
The TIME Coin mobile app uses a hybrid notification strategy:

Foreground: TCP direct connection to masternode
Background: FCM push notifications
Fallback: HTTP API polling
Components
Wallet Layer
BIP-39 mnemonic generation
BIP-44 address derivation
Private key management (secure keystore)
Transaction signing
Network Layer
TCP protocol client (port 24100)
HTTP REST API client
FCM push notification handler
Storage Layer
Encrypted wallet data
Transaction history
Address book
User preferences
UI Layer
Balance display
Send/receive screens
Transaction history
QR scanner
Settings
Security Model
Private keys stored in OS secure keystore
Local data encrypted with AES-256
Biometric authentication for transactions
Certificate pinning for API calls
Root detection on startup
Data Flow
Send Transaction
User Input → Validate → Build TX → Sign (keystore) 
  → Submit (HTTP) → Monitor (TCP) → Update UI
Receive Transaction
Masternode → TCP Notification → Update Balance 
  → Show Notification → Update History
Background Notification
Masternode → FCM → OS Wakes App → Fetch Details 
  → Update State → Show Notification
Protocol
See TCP_PROTOCOL.md for details. EOF

7. Copy protocol docs from main repo
cat > docs/TCP_PROTOCOL.md << 'EOF'

TIME Coin TCP Protocol
Connection
Host: masternode IP/domain
Port: 24100 (testnet), 24101 (mainnet)
Protocol: Length-prefixed JSON over TCP
Message Format
[4-byte length (big-endian)][UTF-8 JSON message]
Messages
RegisterXpub (Client → Server)
{
  "RegisterXpub": {
    "xpub": "xpub6CUGRUonZSQ4..."
  }
}
XpubRegistered (Server → Client)
{
  "XpubRegistered": {
    "success": true,
    "message": "Monitoring 20 addresses"
  }
}
NewTransactionNotification (Server → Client)
{
  "NewTransactionNotification": {
    "transaction": {
      "tx_hash": "abc123...",
      "from_address": "TIME1...",
      "to_address": "TIME1...",
      "amount": 50000000,
      "timestamp": 1732034400,
      "block_height": 0,
      "confirmations": 0
    }
  }
}
UtxoUpdate (Server → Client)
{
  "UtxoUpdate": {
    "xpub": "xpub6CUGRUonZSQ4...",
    "utxos": [
      {
        "txid": "abc123...",
        "vout": 0,
        "address": "TIME1...",
        "amount": 100000000,
        "block_height": 1234,
        "confirmations": 5
      }
    ]
  }
}
See main TIME Coin repo for full protocol specification. EOF

8. Create Android project placeholder
cat > android/README.md << 'EOF'

TIME Coin Android App
Setup
Open Android Studio
File → Open → Select this android/ directory
Wait for Gradle sync
Run on emulator or device
Build
# Debug build
./gradlew assembleDebug

# Release build (requires signing key)
./gradlew assembleRelease
Testing
# Unit tests
./gradlew test

# Instrumentation tests
./gradlew connectedAndroidTest
Project Structure
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
