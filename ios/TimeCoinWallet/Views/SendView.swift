import SwiftUI

struct SendView: View {
    @EnvironmentObject var wallet: WalletService
    @State private var toAddress = ""
    @State private var amountText = ""
    @State private var isLoading = false
    @State private var showQr = false
    @State private var fromAddressIdx = 0

    private var amountSats: Int64? {
        guard let d = Double(amountText), d > 0 else { return nil }
        return Int64(d * 100_000_000)
    }

    private var fee: Int64 {
        guard let amt = amountSats else { return 0 }
        return FeeSchedule.default.calculateFee(sendAmount: amt)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Recipient") {
                    HStack {
                        TextField("TIME1...", text: $toAddress)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .font(.system(.body, design: .monospaced))
                        Button {
                            wallet.screen = .qrScanner
                        } label: {
                            Image(systemName: "qrcode.viewfinder")
                        }
                    }
                }

                if wallet.addresses.count > 1 {
                    Section("Send From") {
                        Picker("From Address", selection: $fromAddressIdx) {
                            ForEach(wallet.addresses.indices, id: \.self) { idx in
                                let addr = wallet.addresses[idx]
                                Text(addr.prefix(12) + "…" + addr.suffix(6))
                                    .font(.system(.caption, design: .monospaced))
                                    .tag(idx)
                            }
                        }
                        .labelsHidden()
                        .pickerStyle(.wheel)
                        .frame(height: 100)
                        .clipped()
                    }
                }

                Section("Amount") {
                    HStack {
                        TextField("0.00000000", text: $amountText)
                            .keyboardType(.decimalPad)
                        Text("TIME")
                            .foregroundStyle(.secondary)
                    }
                    if let amt = amountSats {
                        HStack {
                            Text("Fee")
                            Spacer()
                            Text("\(fee.timeDisplay) TIME")
                                .foregroundStyle(.secondary)
                        }
                        HStack {
                            Text("Total")
                                .bold()
                            Spacer()
                            Text("\((amt + fee).timeDisplay) TIME")
                                .bold()
                        }
                    }
                }

                Section {
                    Button {
                        guard let amt = amountSats else { return }
                        isLoading = true
                        let fromAddr = wallet.addresses.indices.contains(fromAddressIdx)
                            ? wallet.addresses[fromAddressIdx] : nil
                        Task {
                            await wallet.send(toAddress: toAddress, amount: amt, fromAddress: fromAddr)
                            isLoading = false
                            if wallet.error == nil { wallet.screen = .overview }
                        }
                    } label: {
                        if isLoading { ProgressView() }
                        else {
                            Text("Send")
                                .frame(maxWidth: .infinity)
                                .bold()
                        }
                    }
                    .disabled(!Address.isValid(toAddress) || amountSats == nil || isLoading)
                }
            }
            .navigationTitle("Send TIME")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { wallet.screen = .overview }
                }
            }
            .onAppear {
                if !wallet.scannedAddress.isEmpty {
                    toAddress = wallet.scannedAddress
                    wallet.scannedAddress = ""
                }
            }
        }
    }
}
