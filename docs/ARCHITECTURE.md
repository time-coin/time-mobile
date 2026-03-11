# TIME Coin Mobile Architecture

## Overview

The TIME Coin mobile app uses a hybrid notification strategy:

- **Foreground**: TCP direct connection to masternode
- **Background**: FCM/APNs push notifications  
- **Fallback**: HTTP API polling

## Components

### Wallet Layer
- BIP-39 mnemonic generation
- BIP-44 address derivation (m/44'/0'/0')
- Private key management (secure keystore)
- Transaction signing

### Network Layer
- TCP protocol client (port 24100)
- HTTP REST API client
- FCM/APNs push notification handler

### Storage Layer
- Encrypted wallet data
- Transaction history
- Address book
- User preferences

### UI Layer
- Balance display
- Send/receive screens
- Transaction history
- QR scanner
- Settings

## Security Model

- Private keys stored in OS secure keystore (Android Keystore / iOS Keychain)
- Local data encrypted with AES-256
- Biometric authentication for transactions
- Certificate pinning for API calls
- Root/Jailbreak detection on startup

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
