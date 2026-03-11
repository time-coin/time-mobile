# iOS Quick Start Guide

## Prerequisites

- **macOS**: 13.0 (Ventura) or newer
- **Xcode**: 15.0 or newer
- **iOS**: 15.0+ target
- **Swift**: 5.9+

## Setup

1. **Install Xcode**
   - Download from Mac App Store
   - Install command line tools: `xcode-select --install`

2. **Install CocoaPods**
   ```bash
   sudo gem install cocoapods
   ```

3. **Clone the Repository**
   ```bash
   git clone https://github.com/TimeCoinProject/time-coin-mobile.git
   cd time-coin-mobile/ios
   ```

4. **Install Dependencies**
   ```bash
   pod install
   ```

5. **Open in Xcode**
   ```bash
   open TimeCoinWallet.xcworkspace
   ```

## Build from Command Line

### Debug Build
```bash
cd ios
xcodebuild -workspace TimeCoinWallet.xcworkspace \
           -scheme TimeCoinWallet \
           -configuration Debug \
           -destination 'platform=iOS Simulator,name=iPhone 15'
```

### Release Build
```bash
xcodebuild -workspace TimeCoinWallet.xcworkspace \
           -scheme TimeCoinWallet \
           -configuration Release \
           archive -archivePath build/TimeCoinWallet.xcarchive
```

## Run on Device/Simulator

### Using Xcode
1. Select target device/simulator from dropdown
2. Click "Run" button (▶) or press ⌘+R

### Using Command Line
```bash
# Build and run on simulator
xcodebuild -workspace TimeCoinWallet.xcworkspace \
           -scheme TimeCoinWallet \
           -destination 'platform=iOS Simulator,name=iPhone 15' \
           build
```

## Testing

### Unit Tests
```bash
xcodebuild test -workspace TimeCoinWallet.xcworkspace \
                -scheme TimeCoinWallet \
                -destination 'platform=iOS Simulator,name=iPhone 15'
```

Or in Xcode: Product → Test (⌘+U)

## Project Structure

```
ios/
├── TimeCoinWallet/
│   ├── App/
│   │   ├── TimeCoinWalletApp.swift
│   │   └── ContentView.swift
│   ├── Network/
│   │   ├── TcpProtocolClient.swift
│   │   └── HttpApiClient.swift
│   ├── Wallet/
│   │   ├── Wallet.swift
│   │   ├── Bip39.swift
│   │   └── AddressDerivation.swift
│   ├── Storage/
│   │   ├── CoreDataStack.swift
│   │   └── KeychainManager.swift
│   ├── Views/
│   │   ├── HomeView.swift
│   │   ├── SendView.swift
│   │   ├── ReceiveView.swift
│   │   └── HistoryView.swift
│   └── Notifications/
│       └── NotificationService.swift
├── TimeCoinWalletTests/
├── Podfile
└── TimeCoinWallet.xcodeproj
```

## Code Signing

1. Open project in Xcode
2. Select project in navigator
3. Select "TimeCoinWallet" target
4. Go to "Signing & Capabilities"
5. Select your development team
6. Xcode will automatically manage provisioning

## Troubleshooting

### Pod Install Failed
```bash
pod repo update
pod install --repo-update
```

### Code Signing Issues
- Ensure you're logged into Xcode with Apple ID
- Create an iOS developer account if needed
- Use automatic code signing for development

### Simulator Not Found
- Xcode → Window → Devices and Simulators
- Add required iOS simulator versions

## Next Steps

- Review [ARCHITECTURE.md](ARCHITECTURE.md) for app architecture
- Review [TCP_PROTOCOL.md](TCP_PROTOCOL.md) for network protocol
- Read [CONTRIBUTING.md](../CONTRIBUTING.md) for development guidelines
