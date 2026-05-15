import SwiftUI
import LocalAuthentication

struct PinSetupView: View {
    @EnvironmentObject var wallet: WalletService
    @State private var pin = ""
    @State private var confirm = ""
    @State private var step = 0  // 0 = enter, 1 = confirm

    var body: some View {
        NavigationStack {
            VStack(spacing: 32) {
                Text(step == 0 ? "Choose a 4-digit PIN" : "Confirm your PIN")
                    .font(.headline)

                PinDots(count: step == 0 ? pin.count : confirm.count)

                PinPad(onDigit: { d in
                    if step == 0 {
                        guard pin.count < 4 else { return }
                        pin += d
                        if pin.count == 4 { step = 1 }
                    } else {
                        guard confirm.count < 4 else { return }
                        confirm += d
                        if confirm.count == 4 { submitPin() }
                    }
                }, onDelete: {
                    if step == 0 { if !pin.isEmpty { pin.removeLast() } }
                    else { if !confirm.isEmpty { confirm.removeLast() } }
                })
            }
            .padding()
            .navigationTitle("Set PIN")
        }
    }

    private func submitPin() {
        if pin == confirm {
            wallet.savePin(pin)
            wallet.screen = .overview
        } else {
            pin = ""; confirm = ""; step = 0
            wallet.error = "PINs do not match"
        }
    }
}

struct PinUnlockView: View {
    @EnvironmentObject var wallet: WalletService
    @State private var pin = ""
    @State private var isLoading = false

    var body: some View {
        VStack(spacing: 32) {
            Spacer()
            Image(systemName: "lock.fill")
                .font(.system(size: 60))
                .foregroundStyle(.primary)

            Text("Enter PIN")
                .font(.headline)

            PinDots(count: pin.count)

            PinPad(onDigit: { d in
                guard pin.count < 4 else { return }
                pin += d
                if pin.count == 4 { submitPin() }
            }, onDelete: {
                if !pin.isEmpty { pin.removeLast() }
            })

            Spacer()

            Button("Use Biometrics") { authenticateWithBiometrics() }
                .font(.footnote)
        }
        .padding()
        .onAppear { authenticateWithBiometrics() }
    }

    private func submitPin() {
        isLoading = true
        Task {
            await wallet.unlockWithPin(pin)
            isLoading = false
            if wallet.screen == .pinUnlock { pin = "" }
        }
    }

    private func authenticateWithBiometrics() {
        let context = LAContext()
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else { return }
        context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: "Unlock TIME Wallet") { success, _ in
            if success {
                DispatchQueue.main.async {
                    Task { await wallet.unlockWithBiometrics() }
                }
            }
        }
    }
}

struct PasswordUnlockView: View {
    @EnvironmentObject var wallet: WalletService
    @State private var password = ""
    @State private var isLoading = false

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: "lock.fill").font(.system(size: 60))
            Text("Enter Password").font(.headline)
            SecureField("Password", text: $password)
                .textFieldStyle(.roundedBorder)
                .padding(.horizontal, 32)
            Button {
                isLoading = true
                Task { await wallet.unlockWithPassword(password); isLoading = false }
            } label: {
                if isLoading { ProgressView() }
                else { Text("Unlock").frame(maxWidth: .infinity) }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .padding(.horizontal, 32)
            .disabled(password.isEmpty || isLoading)
            Spacer()
        }
    }
}

// MARK: - Shared PIN components

struct PinDots: View {
    let count: Int

    var body: some View {
        HStack(spacing: 16) {
            ForEach(0..<4) { i in
                Circle()
                    .fill(i < count ? Color.primary : Color(.tertiarySystemFill))
                    .frame(width: 16, height: 16)
            }
        }
    }
}

struct PinPad: View {
    let onDigit: (String) -> Void
    let onDelete: () -> Void

    private let digits = [["1","2","3"],["4","5","6"],["7","8","9"],["","0","⌫"]]

    var body: some View {
        VStack(spacing: 12) {
            ForEach(digits, id: \.self) { row in
                HStack(spacing: 16) {
                    ForEach(row, id: \.self) { key in
                        if key.isEmpty {
                            Color.clear.frame(width: 72, height: 72)
                        } else {
                            Button {
                                if key == "⌫" { onDelete() } else { onDigit(key) }
                            } label: {
                                Text(key)
                                    .font(.title2.bold())
                                    .frame(width: 72, height: 72)
                                    .background(Color(.secondarySystemBackground))
                                    .cornerRadius(36)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
    }
}

struct ChangePinView: View {
    @EnvironmentObject var wallet: WalletService
    @State private var current = ""
    @State private var newPin = ""
    @State private var step = 0

    var body: some View {
        NavigationStack {
            VStack(spacing: 32) {
                Text(["Enter current PIN","Enter new PIN"][step])
                    .font(.headline)
                PinDots(count: step == 0 ? current.count : newPin.count)
                PinPad(onDigit: { d in
                    if step == 0 {
                        guard current.count < 4 else { return }
                        current += d
                        if current.count == 4 { step = 1 }
                    } else {
                        guard newPin.count < 4 else { return }
                        newPin += d
                        if newPin.count == 4 { changePin() }
                    }
                }, onDelete: {
                    if step == 0 { if !current.isEmpty { current.removeLast() } }
                    else { if !newPin.isEmpty { newPin.removeLast() } }
                })
            }
            .padding()
            .navigationTitle("Change PIN")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { wallet.screen = .settings }
                }
            }
        }
    }

    private func changePin() {
        wallet.savePin(newPin)
        wallet.success = "PIN updated"
        wallet.screen = .settings
    }
}
