import SwiftUI

struct TransactionsView: View {
    @EnvironmentObject var wallet: WalletService
    @State private var filter: TxFilter = .all

    enum TxFilter: String, CaseIterable {
        case all = "All"
        case sent = "Sent"
        case received = "Received"
    }

    var filtered: [TransactionRecord] {
        switch filter {
        case .all: return wallet.transactions
        case .sent: return wallet.transactions.filter { $0.isSend && !$0.isFee }
        case .received: return wallet.transactions.filter { !$0.isSend }
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("Filter", selection: $filter) {
                    ForEach(TxFilter.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding()

                List(filtered) { tx in
                    TransactionRow(tx: tx)
                        .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
                        .onTapGesture {
                            wallet.selectedTransaction = tx
                            wallet.screen = .transactionDetail
                        }
                }
                .listStyle(.plain)

                if wallet.hasMoreTransactions {
                    Button {
                        wallet.loadMoreTransactions()
                    } label: {
                        if wallet.loadingMore {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                                .padding()
                        } else {
                            Text("Load More")
                                .frame(maxWidth: .infinity)
                                .padding()
                        }
                    }
                    .disabled(wallet.loadingMore)
                }
            }
            .navigationTitle("Transactions")
            .refreshable {
                await wallet.refreshTransactions()
            }
        }
    }
}

struct TransactionRow: View {
    let tx: TransactionRecord

    private var date: String {
        let d = Date(timeIntervalSince1970: TimeInterval(tx.timestamp))
        let f = DateFormatter()
        f.dateStyle = .short; f.timeStyle = .short
        return f.string(from: d)
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: tx.isSend ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                .font(.title2)
                .foregroundStyle(tx.isSend ? .red : .green)

            VStack(alignment: .leading, spacing: 2) {
                Text(tx.isSend ? "Sent" : "Received")
                    .font(.subheadline.bold())
                Text(tx.address.prefix(16) + "...")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(date)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                Text("\(tx.isSend ? "-" : "+")\(tx.amount.timeDisplay)")
                    .font(.subheadline.bold())
                    .foregroundStyle(tx.isSend ? .red : .green)
                StatusBadge(status: tx.status)
            }
        }
        .padding(.vertical, 4)
    }
}

struct StatusBadge: View {
    let status: TransactionStatus

    var body: some View {
        Text(status.rawValue)
            .font(.caption2.bold())
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.15))
            .foregroundStyle(color)
            .cornerRadius(4)
    }

    private var color: Color {
        switch status {
        case .approved: return .green
        case .pending: return .orange
        case .declined: return .red
        }
    }
}

struct TransactionDetailView: View {
    @EnvironmentObject var wallet: WalletService

    var tx: TransactionRecord? { wallet.selectedTransaction }

    private var date: String {
        guard let tx else { return "" }
        let d = Date(timeIntervalSince1970: TimeInterval(tx.timestamp))
        let f = DateFormatter(); f.dateStyle = .long; f.timeStyle = .medium
        return f.string(from: d)
    }

    var body: some View {
        NavigationStack {
            if let tx {
                List {
                    Section {
                        DetailRow(label: "Type", value: tx.isSend ? "Sent" : "Received")
                        DetailRow(label: "Amount", value: "\(tx.amount.timeDisplay) TIME")
                        if tx.fee > 0 { DetailRow(label: "Fee", value: "\(tx.fee.timeDisplay) TIME") }
                        DetailRow(label: "Status", value: tx.status.rawValue)
                        DetailRow(label: "Date", value: date)
                    }
                    Section("Details") {
                        DetailRow(label: "Address", value: tx.address, mono: true)
                        DetailRow(label: "TXID", value: tx.txid, mono: true)
                        if tx.blockHeight > 0 { DetailRow(label: "Block", value: "\(tx.blockHeight)") }
                        if tx.confirmations > 0 { DetailRow(label: "Confirmations", value: "\(tx.confirmations)") }
                        if !tx.memo.isEmpty { DetailRow(label: "Memo", value: tx.memo) }
                    }
                }
                .navigationTitle("Transaction")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Done") { wallet.screen = .transactions }
                    }
                }
            } else {
                Text("No transaction selected")
            }
        }
    }
}

struct DetailRow: View {
    let label: String
    let value: String
    var mono: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(value)
                .font(mono ? .system(.caption, design: .monospaced) : .body)
                .textSelection(.enabled)
        }
    }
}
