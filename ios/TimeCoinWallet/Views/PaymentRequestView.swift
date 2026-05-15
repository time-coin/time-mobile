import SwiftUI

struct PaymentRequestView: View {
    @EnvironmentObject var wallet: WalletService
    @State private var payerAddress = ""
    @State private var amountText = ""
    @State private var memo = ""
    @State private var isLoading = false

    private var amountSats: Int64? {
        guard let d = Double(amountText), d > 0 else { return nil }
        return Int64(d * 100_000_000)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Request From") {
                    HStack {
                        TextField("Payer address (TIME1...)", text: $payerAddress)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                        Button {
                            wallet.screen = .paymentRequestQrScanner
                        } label: {
                            Image(systemName: "qrcode.viewfinder")
                        }
                    }
                }

                Section("Amount") {
                    HStack {
                        TextField("0.00000000", text: $amountText)
                            .keyboardType(.decimalPad)
                        Text("TIME").foregroundStyle(.secondary)
                    }
                }

                Section("Memo (optional)") {
                    TextField("What is this for?", text: $memo)
                }

                Section {
                    Button {
                        guard let amt = amountSats else { return }
                        isLoading = true
                        Task {
                            await wallet.sendPaymentRequest(payerAddress: payerAddress, amount: amt, memo: memo)
                            isLoading = false
                        }
                    } label: {
                        if isLoading { ProgressView() }
                        else { Text("Send Request").frame(maxWidth: .infinity).bold() }
                    }
                    .disabled(!Address.isValid(payerAddress) || amountSats == nil || isLoading)
                }
            }
            .navigationTitle("Payment Request")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { wallet.screen = .overview }
                }
            }
            .onAppear {
                if !wallet.scannedAddress.isEmpty {
                    payerAddress = wallet.scannedAddress
                    wallet.scannedAddress = ""
                }
            }
        }
    }
}

struct PaymentRequestReviewView: View {
    @EnvironmentObject var wallet: WalletService

    var body: some View {
        NavigationStack {
            if let req = wallet.incomingPaymentRequest {
                VStack(spacing: 24) {
                    Image(systemName: "creditcard.fill")
                        .font(.system(size: 60))
                        .foregroundStyle(.purple)

                    VStack(spacing: 8) {
                        Text("Payment Request")
                            .font(.title2.bold())
                        Text("from \(req.requesterName.isEmpty ? req.requesterAddress.prefix(20) + "..." : req.requesterName)")
                            .foregroundStyle(.secondary)
                    }

                    VStack(spacing: 4) {
                        Text("\(Int64(req.amountSats).timeDisplay) TIME")
                            .font(.system(size: 36, weight: .bold, design: .rounded))
                        if !req.memo.isEmpty {
                            Text(req.memo).foregroundStyle(.secondary)
                        }
                    }
                    .padding(24)
                    .background(Color(.secondarySystemBackground))
                    .cornerRadius(16)

                    HStack(spacing: 16) {
                        Button {
                            Task { await wallet.declinePaymentRequest(id: req.id) }
                        } label: {
                            Text("Decline")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .tint(.red)

                        Button {
                            Task { await wallet.acceptPaymentRequest(id: req.id) }
                        } label: {
                            Text("Pay")
                                .frame(maxWidth: .infinity)
                                .bold()
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .padding(.horizontal)

                    Spacer()
                }
                .padding()
                .navigationTitle("Incoming Request")
            }
        }
    }
}

struct PaymentRequestsView: View {
    @EnvironmentObject var wallet: WalletService

    var body: some View {
        NavigationStack {
            List {
                ForEach(wallet.paymentRequests) { pr in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(pr.isOutgoing ? "Requested from" : "Request from")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Spacer()
                            Text(pr.status.rawValue)
                                .font(.caption.bold())
                                .foregroundStyle(statusColor(pr.status))
                        }
                        Text("\(pr.amountSats.timeDisplay) TIME")
                            .font(.headline)
                        if !pr.memo.isEmpty {
                            Text(pr.memo).font(.caption).foregroundStyle(.secondary)
                        }
                        if !pr.paidTxid.isEmpty {
                            Text("Paid: \(pr.paidTxid.prefix(16))…")
                                .font(.caption2)
                                .foregroundStyle(.green)
                                .textSelection(.enabled)
                        }
                    }
                    .padding(.vertical, 4)
                    .swipeActions(edge: .trailing) {
                        if pr.isOutgoing && pr.status == .pending {
                            Button(role: .destructive) {
                                Task { await wallet.cancelPaymentRequest(id: pr.id) }
                            } label: {
                                Label("Cancel", systemImage: "xmark.circle")
                            }
                        } else {
                            Button(role: .destructive) {
                                wallet.deletePaymentRequest(id: pr.id)
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                        }
                    }
                }
            }
            .navigationTitle("Payment Requests")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { wallet.screen = .overview }
                }
            }
        }
    }

    private func statusColor(_ status: PaymentRequestStatus) -> Color {
        switch status {
        case .pending: return .orange
        case .accepted, .paid: return .green
        case .declined, .cancelled: return .red
        }
    }
}
