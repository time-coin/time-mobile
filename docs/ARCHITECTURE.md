# TIME Coin Mobile Architecture

## Overview

The TIME Coin mobile app is a thin client — all blockchain state lives on
masternodes. The wallet stores keys locally and queries masternodes via
JSON-RPC over HTTP, with real-time updates via WebSocket.

- **Foreground**: WebSocket connection to masternode for real-time transaction notifications
- **Background**: Planned — FCM push notifications (not yet implemented)
- **Fallback**: HTTP JSON-RPC polling

## Components

### Wallet Layer (`crypto/`, `wallet/`)
- BIP-39 mnemonic generation (kotlin-bip39)
- SLIP-0010 HD key derivation (Ed25519, path `m/44'/0'/account'/change'/index'`)
- Ed25519 signing via BouncyCastle
- AES-256-GCM encryption with Argon2id KDF (64 MB, 3 iterations)
- 4-digit PIN unlock with optional biometric authentication (AndroidX BiometricPrompt)
- Android Keystore integration for biometric key storage

### Network Layer (`network/`)
- JSON-RPC client via Ktor + OkHttp (ports 24001 mainnet / 24101 testnet)
- WebSocket notifications via OkHttp (auto-reconnect with exponential backoff)
- Peer discovery: API lookup → `time.conf` manual config → cached peers
- Ping-based peer selection (TCP connect timing, health checks, block height)

### Storage Layer (`db/`)
- Room database v3 (`wallet.db`) with tables: contacts, transactions, settings
- Encrypted wallet files (same format as desktop wallet for portability)

### UI Layer (`ui/`)
- Jetpack Compose with Material 3 theming
- Hilt dependency injection (`@AndroidEntryPoint`)
- Screens: Welcome, PIN Entry, Password Unlock, Overview, Send, Receive,
  Transaction History, Transaction Detail, QR Scanner, Connections, Settings,
  Mnemonic Backup

## Security Model

- Wallet encrypted with AES-256-GCM; key derived from 4-digit PIN via Argon2id
- Optional biometric unlock: PIN encrypted with Android Keystore key,
  decrypted via BiometricPrompt on unlock
- Mnemonic phrases never stored in plaintext after wallet creation
- Private keys never leave the device
- Report security issues privately to: **security@time-coin.io**

## Data Flow

### Send Transaction
```
User Input → Validate → Build TX → Sign (keystore) 
  → Submit (HTTP) → Monitor (TCP) → Update UI
```

### Receive Transaction
```
Masternode → TCP Notification → Update Balance 
  → Show Notification → Update History
```

### Background Notification
```
Masternode → FCM/APNs → OS Wakes App → Fetch Details 
  → Update State → Show Notification
```

## Protocol

See [TCP_PROTOCOL.md](TCP_PROTOCOL.md) for details.
