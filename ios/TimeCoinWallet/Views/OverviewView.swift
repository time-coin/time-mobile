import SwiftUI

struct OverviewView: View {
    @EnvironmentObject var wallet: WalletService
    @State private var showCopied = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    balanceCard
                    if wallet.consolidating { consolidationCard }
                    actionGrid
                    statsRow
                    recentTransactions
                }
                .padding()
            }
            .navigationTitle("Overview")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    HStack(spacing: 4) {
                        Circle()
                            .fill(wallet.wsConnected ? Color.green : Color.red)
                            .frame(width: 8, height: 8)
                        Text(wallet.wsConnected ? "Live" : "Offline")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .refreshable {
                await wallet.refreshBalance()
                await wallet.refreshTransactions()
            }
        }
    }

    private var balanceCard: some View {
        VStack(spacing: 8) {
            Text("Total Balance")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            Text("\(wallet.balance.total.timeDisplay) TIME")
                .font(.system(size: 36, weight: .bold, design: .rounded))

            if wallet.balance.pending != 0 {
                Text("Pending: \(wallet.balance.pending.timeDisplay) TIME")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }

            if let addr = wallet.addresses.first {
                Button {
                    UIPasteboard.general.string = addr
                    showCopied = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) { showCopied = false }
                } label: {
                    HStack(spacing: 4) {
                        Text(addr.prefix(20) + "..." + addr.suffix(6))
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(.secondary)
                        Image(systemName: showCopied ? "checkmark" : "doc.on.doc")
                            .font(.caption)
                            .foregroundStyle(showCopied ? .green : .secondary)
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(24)
        .background(Color(.secondarySystemBackground))
        .cornerRadius(16)
    }

    private var consolidationCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label("Consolidating UTXOs", systemImage: "arrow.triangle.merge")
                    .font(.subheadline.bold())
                Spacer()
                Button("Cancel") { wallet.cancelConsolidation() }
                    .font(.caption)
                    .foregroundStyle(.red)
            }
            if !wallet.consolidationStatus.isEmpty {
                Text(wallet.consolidationStatus)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            ProgressView().progressViewStyle(.linear)
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .cornerRadius(12)
    }

    private var actionGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
            ActionButton(title: "Send", icon: "arrow.up.circle.fill", color: .blue) {
                wallet.screen = .send
            }
            ActionButton(title: "Receive", icon: "arrow.down.circle.fill", color: .green) {
                wallet.screen = .receive
            }
            ActionButton(title: "Request", icon: "creditcard.fill", color: .purple) {
                wallet.screen = .paymentRequest
            }
            ActionButton(title: "Requests", icon: "list.bullet.rectangle", color: .orange) {
                wallet.screen = .paymentRequests
            }
            ActionButton(title: "Consolidate", icon: "arrow.triangle.merge", color: .teal) {
                wallet.consolidateUtxos()
            }
            ActionButton(title: "History", icon: "clock.fill", color: .gray) {
                wallet.screen = .transactions
            }
        }
    }

    private var statsRow: some View {
        HStack(spacing: 12) {
            StatTile(label: "Confirmed", value: wallet.balance.confirmed.timeDisplay, color: .green)
            StatTile(label: "UTXOs", value: "\(wallet.utxos.count)", color: .blue)
            StatTile(label: "Received", value: wallet.transactions
                .filter { !$0.isSend }.reduce(0) { $0 + $1.amount }.timeDisplay, color: .purple)
        }
    }

    private var recentTransactions: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Recent Transactions")
                    .font(.headline)
                Spacer()
                Button("See All") { wallet.screen = .transactions }
                    .font(.subheadline)
            }

            if wallet.transactions.isEmpty {
                Text("No transactions yet")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
            } else {
                ForEach(wallet.transactions.prefix(5)) { tx in
                    TransactionRow(tx: tx)
                        .onTapGesture {
                            wallet.selectedTransaction = tx
                            wallet.screen = .transactionDetail
                        }
                }
            }
        }
    }
}

struct ActionButton: View {
    let title: String
    let icon: String
    let color: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 28))
                    .foregroundStyle(color)
                Text(title)
                    .font(.caption.bold())
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(Color(.secondarySystemBackground))
            .cornerRadius(12)
        }
        .buttonStyle(.plain)
    }
}

struct StatTile: View {
    let label: String
    let value: String
    let color: Color

    var body: some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.subheadline.bold())
                .foregroundStyle(color)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(Color(.secondarySystemBackground))
        .cornerRadius(10)
    }
}
