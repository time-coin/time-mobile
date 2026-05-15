import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var wallet: WalletService
    @State private var showDeleteAlert = false
    @State private var configText = ""
    @State private var editingConfig = false
    @State private var biometricLoading = false

    var body: some View {
        NavigationStack {
            List {
                Section("Notifications") {
                    Toggle("Sound & Notifications", isOn: Binding(
                        get: { wallet.notificationsEnabled },
                        set: { wallet.setNotificationsEnabled($0) }
                    ))
                }

                Section("Security") {
                    Button("Change PIN") { wallet.screen = .changePin }
                    Button("Lock Wallet") { wallet.lockWallet() }

                    HStack {
                        Text("Enable Biometrics")
                        Spacer()
                        if biometricLoading {
                            ProgressView()
                        } else {
                            Button("Enroll") {
                                biometricLoading = true
                                Task {
                                    let ok = await wallet.enrollBiometric()
                                    biometricLoading = false
                                    if ok { wallet.success = "Biometric authentication enabled." }
                                    else { wallet.error = "Biometric enrollment failed." }
                                }
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                }

                Section("Display") {
                    Picker("Decimal Places", selection: Binding(
                        get: { wallet.decimalPlaces },
                        set: { wallet.setDecimalPlaces($0) }
                    )) {
                        ForEach([2, 4, 6, 8], id: \.self) { Text("\($0)").tag($0) }
                    }
                }

                Section("Network") {
                    Toggle("Testnet", isOn: Binding(
                        get: { wallet.isTestnet },
                        set: { val in Task { await wallet.switchNetwork(toTestnet: val) } }
                    ))

                    Button("Edit Node Config") {
                        configText = wallet.getConfigRaw()
                        editingConfig = true
                    }
                }

                Section("Addresses") {
                    ForEach(wallet.addresses, id: \.self) { addr in
                        Text(addr)
                            .font(.system(.caption, design: .monospaced))
                            .textSelection(.enabled)
                    }
                    Button {
                        Task { await wallet.generateAddress() }
                    } label: {
                        Label("New Address", systemImage: "plus")
                    }
                }

                Section {
                    Button("Delete Wallet", role: .destructive) { showDeleteAlert = true }
                }
            }
            .navigationTitle("Settings")
            .alert("Delete Wallet?", isPresented: $showDeleteAlert) {
                Button("Delete", role: .destructive) { wallet.deleteWallet() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This will delete your wallet. Make sure you have your mnemonic backed up.")
            }
            .sheet(isPresented: $editingConfig) {
                NodeConfigEditor(text: $configText, isTestnet: wallet.isTestnet) {
                    wallet.saveConfigRaw(configText)
                }
            }
        }
    }
}

struct NodeConfigEditor: View {
    @Binding var text: String
    let isTestnet: Bool
    let onSave: () -> Void
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationStack {
            TextEditor(text: $text)
                .font(.system(.body, design: .monospaced))
                .padding()
                .navigationTitle("\(isTestnet ? "Testnet" : "Mainnet") Config")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") { onSave(); dismiss() }
                    }
                }
        }
    }
}
