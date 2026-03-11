# TIME Coin iOS App

## Setup

1. **Install Xcode** (15.0 or newer)
2. **Install CocoaPods**: `sudo gem install cocoapods`
3. **Install dependencies**: `pod install`
4. **Open workspace**: `open TimeCoinWallet.xcworkspace`

## Build

### Debug Build
```bash
xcodebuild -workspace TimeCoinWallet.xcworkspace \
           -scheme TimeCoinWallet \
           -configuration Debug
```

### Release Build
```bash
xcodebuild -workspace TimeCoinWallet.xcworkspace \
           -scheme TimeCoinWallet \
           -configuration Release \
           archive -archivePath build/TimeCoinWallet.xcarchive
```

## Testing

```bash
xcodebuild test -workspace TimeCoinWallet.xcworkspace \
                -scheme TimeCoinWallet \
                -destination 'platform=iOS Simulator,name=iPhone 15'
```

## Project Structure

```
TimeCoinWallet/
├── App/
│   ├── TimeCoinWalletApp.swift
│   └── ContentView.swift
├── Network/
│   ├── TcpProtocolClient.swift
│   └── HttpApiClient.swift
├── Wallet/
│   ├── Wallet.swift
│   ├── Bip39.swift
│   └── AddressDerivation.swift
├── Storage/
│   ├── CoreDataStack.swift
│   └── KeychainManager.swift
├── Views/
│   ├── HomeView.swift
│   ├── SendView.swift
│   ├── ReceiveView.swift
│   └── HistoryView.swift
└── Notifications/
    └── NotificationService.swift
```

## Dependencies

- **BitcoinKit**: BIP-39/BIP-44 wallet implementation
- **SwiftSocket**: TCP networking
- **Alamofire**: HTTP networking
- **KeychainSwift**: Secure key storage

## Configuration

1. Configure signing in Xcode project settings
2. Add `GoogleService-Info.plist` for APNs/Firebase
3. Update masternode endpoints in `Network/Constants.swift`

See [IOS_QUICKSTART.md](../docs/IOS_QUICKSTART.md) for detailed instructions.
