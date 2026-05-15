import SwiftUI

struct MnemonicSetupView: View {
    @EnvironmentObject var wallet: WalletService
    @State private var mnemonic: String = ""
    @State private var isLoading = false
    @State private var wordCount = 12

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                if wallet.restoreMode {
                    restoreView
                } else {
                    generateView
                }
            }
            .padding()
            .navigationTitle(wallet.restoreMode ? "Restore Wallet" : "New Wallet")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Back") { wallet.screen = .welcome }
                }
            }
        }
        .onAppear {
            if !wallet.restoreMode {
                mnemonic = (try? MnemonicHelper.generate(wordCount: wordCount)) ?? ""
            }
        }
    }

    private var generateView: some View {
        VStack(spacing: 24) {
            Picker("Word Count", selection: $wordCount) {
                Text("12 words").tag(12)
                Text("18 words").tag(18)
                Text("24 words").tag(24)
            }
            .pickerStyle(.segmented)
            .onChange(of: wordCount) { count in
                mnemonic = (try? MnemonicHelper.generate(wordCount: count)) ?? ""
            }

            Text("Write down these \(wordCount) words in order. You will need them to restore your wallet.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            MnemonicGrid(words: mnemonic.components(separatedBy: " "))

            Spacer()

            Button {
                wallet.pendingMnemonic = mnemonic
                wallet.screen = .mnemonicConfirm
            } label: {
                Text("I've Written It Down")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(mnemonic.isEmpty)
        }
    }

    private var restoreView: some View {
        VStack(spacing: 24) {
            Text("Enter your 12–24 word mnemonic phrase, separated by spaces.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            TextEditor(text: $mnemonic)
                .frame(height: 120)
                .padding(8)
                .background(Color(.secondarySystemBackground))
                .cornerRadius(10)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)

            Spacer()

            Button {
                isLoading = true
                Task {
                    await wallet.createWallet(mnemonic: mnemonic.trimmingCharacters(in: .whitespaces), password: nil)
                    isLoading = false
                }
            } label: {
                if isLoading {
                    ProgressView()
                } else {
                    Text("Restore Wallet").frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(!MnemonicHelper.validate(mnemonic.trimmingCharacters(in: .whitespaces)) || isLoading)
        }
    }
}

struct MnemonicConfirmView: View {
    @EnvironmentObject var wallet: WalletService
    @State private var confirmation = ""
    @State private var isLoading = false
    @State private var mismatch = false

    private var normalised: String { confirmation.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var matchesPending: Bool {
        guard !wallet.pendingMnemonic.isEmpty else { return true }
        return normalised == wallet.pendingMnemonic
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Text("Re-enter your mnemonic to confirm you've saved it.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                TextEditor(text: $confirmation)
                    .frame(height: 120)
                    .padding(8)
                    .background(Color(.secondarySystemBackground))
                    .cornerRadius(10)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .overlay(
                        RoundedRectangle(cornerRadius: 10)
                            .stroke(mismatch ? Color.red : Color.clear, lineWidth: 1)
                    )

                if mismatch {
                    Text("Mnemonic does not match. Check your backup and try again.")
                        .font(.caption)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.center)
                }

                Spacer()

                Button {
                    guard matchesPending else { mismatch = true; return }
                    mismatch = false
                    isLoading = true
                    Task {
                        await wallet.createWallet(mnemonic: normalised, password: nil)
                        isLoading = false
                    }
                } label: {
                    if isLoading { ProgressView() }
                    else { Text("Create Wallet").frame(maxWidth: .infinity) }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(!MnemonicHelper.validate(normalised) || isLoading)
            }
            .padding()
            .navigationTitle("Confirm Mnemonic")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Back") { wallet.screen = .mnemonicSetup }
                }
            }
            .onChange(of: confirmation) { _ in mismatch = false }
        }
    }
}

struct MnemonicGrid: View {
    let words: [String]

    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
            ForEach(Array(words.enumerated()), id: \.offset) { index, word in
                HStack(spacing: 4) {
                    Text("\(index + 1).")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(width: 24, alignment: .trailing)
                    Text(word)
                        .font(.system(.body, design: .monospaced))
                }
                .padding(8)
                .background(Color(.secondarySystemBackground))
                .cornerRadius(8)
            }
        }
    }
}
