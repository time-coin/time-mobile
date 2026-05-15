import SwiftUI

struct WelcomeView: View {
    @EnvironmentObject var wallet: WalletService

    var body: some View {
        VStack(spacing: 32) {
            Spacer()
            if UIImage(named: "time_logo") != nil {
                Image("time_logo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 120, height: 120)
            } else {
                Image(systemName: "clock.fill")
                    .font(.system(size: 80))
                    .foregroundStyle(.blue)
            }

            VStack(spacing: 8) {
                Text("TIME Wallet")
                    .font(.largeTitle.bold())
                Text("Your TIME Coin wallet")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            VStack(spacing: 16) {
                Button {
                    wallet.restoreMode = false
                    wallet.screen = .mnemonicSetup
                } label: {
                    Text("Create New Wallet")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)

                Button {
                    wallet.restoreMode = true
                    wallet.screen = .mnemonicSetup
                } label: {
                    Text("Restore from Mnemonic")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)

                Button("Select Network") { wallet.screen = .networkSelect }
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 32)
            .padding(.bottom, 40)
        }
    }
}

struct NetworkSelectView: View {
    @EnvironmentObject var wallet: WalletService

    var body: some View {
        NavigationStack {
            List {
                Button("Mainnet") {
                    Task { await wallet.switchNetwork(toTestnet: false) }
                }
                Button("Testnet") {
                    Task { await wallet.switchNetwork(toTestnet: true) }
                }
            }
            .navigationTitle("Select Network")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { wallet.screen = .welcome }
                }
            }
        }
    }
}
