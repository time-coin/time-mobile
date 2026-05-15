import SwiftUI

@main
struct TimeCoinWalletApp: App {
    @StateObject private var walletService = WalletService()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(walletService)
                .onAppear { walletService.start() }
                .onOpenURL { url in
                    guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return }
                    // Handle timecoin://pay?address=TIME1...&amount=...&memo=...
                    if components.scheme == "timecoin" || components.scheme == "time" {
                        let params = Dictionary(uniqueKeysWithValues:
                            (components.queryItems ?? []).compactMap { item -> (String, String)? in
                                guard let v = item.value else { return nil }
                                return (item.name, v)
                            })
                        if let address = params["address"] ?? components.host, Address.isValid(address) {
                            walletService.scannedAddress = address
                            if walletService.screen != .welcome { walletService.screen = .send }
                        }
                    }
                }
        }
    }
}
