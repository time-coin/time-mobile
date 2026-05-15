import SwiftUI

struct ContentView: View {
    @EnvironmentObject var wallet: WalletService

    var body: some View {
        ZStack(alignment: .top) {
            Group {
                switch wallet.screen {
                case .welcome:             WelcomeView()
                case .networkSelect:       NetworkSelectView()
                case .mnemonicSetup:       MnemonicSetupView()
                case .mnemonicConfirm:     MnemonicConfirmView()
                case .pinSetup:            PinSetupView()
                case .pinUnlock:           PinUnlockView()
                case .passwordUnlock:      PasswordUnlockView()
                case .overview, .transactions, .connections, .settings:
                    MainTabView()
                case .send:                SendView()
                case .receive:             ReceiveView()
                case .qrScanner:           QrScannerView()
                case .transactionDetail:   TransactionDetailView()
                case .paymentRequest:      PaymentRequestView()
                case .paymentRequestReview: PaymentRequestReviewView()
                case .paymentRequests:     PaymentRequestsView()
                case .changePin:           ChangePinView()
                case .paymentRequestQrScanner: QrScannerView()
                }
            }
            .alert("Error", isPresented: Binding(
                get: { wallet.error != nil },
                set: { if !$0 { wallet.error = nil } }
            )) {
                Button("OK") { wallet.error = nil }
            } message: {
                Text(wallet.error ?? "")
            }

            if wallet.success != nil {
                SuccessBanner(message: wallet.success ?? "")
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .onAppear {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                            withAnimation { wallet.success = nil }
                        }
                    }
            }
        }
        .animation(.easeInOut(duration: 0.3), value: wallet.success != nil)
    }
}

struct SuccessBanner: View {
    let message: String

    var body: some View {
        Text(message)
            .font(.subheadline.bold())
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity)
            .background(Color.green)
            .cornerRadius(12)
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .shadow(color: .black.opacity(0.15), radius: 4, y: 2)
    }
}

struct MainTabView: View {
    @EnvironmentObject var wallet: WalletService

    var body: some View {
        TabView(selection: Binding(
            get: { tabIndex(for: wallet.screen) },
            set: { wallet.screen = screen(for: $0) }
        )) {
            OverviewView()
                .tabItem { Label("Overview", systemImage: "house") }
                .tag(0)
            TransactionsView()
                .tabItem { Label("History", systemImage: "list.bullet") }
                .tag(1)
            ConnectionsView()
                .tabItem { Label("Network", systemImage: "antenna.radiowaves.left.and.right") }
                .tag(2)
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gear") }
                .tag(3)
        }
    }

    private func tabIndex(for screen: WalletService.Screen) -> Int {
        switch screen {
        case .transactions: return 1
        case .connections: return 2
        case .settings: return 3
        default: return 0
        }
    }

    private func screen(for index: Int) -> WalletService.Screen {
        switch index {
        case 1: return .transactions
        case 2: return .connections
        case 3: return .settings
        default: return .overview
        }
    }
}
