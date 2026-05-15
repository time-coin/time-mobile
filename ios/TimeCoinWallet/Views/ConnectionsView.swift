import SwiftUI

struct ConnectionsView: View {
    @EnvironmentObject var wallet: WalletService

    var body: some View {
        NavigationStack {
            List {
                Section("Status") {
                    HStack {
                        Label(wallet.wsConnected ? "Connected" : "Disconnected",
                              systemImage: wallet.wsConnected ? "wifi" : "wifi.slash")
                        Spacer()
                        Circle()
                            .fill(wallet.wsConnected ? Color.green : Color.red)
                            .frame(width: 10, height: 10)
                    }

                    if let peer = wallet.connectedPeer {
                        DetailRow(label: "Peer", value: peer.endpoint)
                        if let height = peer.blockHeight {
                            DetailRow(label: "Block Height", value: "\(height)")
                        }
                        if let version = peer.version {
                            DetailRow(label: "Version", value: version)
                        }
                        if let ping = peer.pingMs {
                            DetailRow(label: "Ping", value: "\(ping) ms")
                        }
                    }
                }

                if let health = wallet.health {
                    Section("Network") {
                        DetailRow(label: "Block Height", value: "\(health.blockHeight)")
                        DetailRow(label: "Peers", value: "\(health.peerCount)")
                        DetailRow(label: "Version", value: health.version)
                        if health.isSyncing {
                            HStack {
                                Text("Syncing")
                                Spacer()
                                ProgressView(value: health.syncProgress)
                                    .frame(width: 100)
                            }
                        }
                    }
                }

                if !wallet.peers.isEmpty {
                    Section("Discovered Peers (\(wallet.peers.count))") {
                        ForEach(wallet.peers, id: \.endpoint) { peer in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(peer.endpoint)
                                        .font(.system(.caption, design: .monospaced))
                                    if let height = peer.blockHeight {
                                        Text("Block \(height)")
                                            .font(.caption2)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                if wallet.connectedPeer?.endpoint == peer.endpoint {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(.green)
                                        .font(.caption)
                                } else if let ping = peer.pingMs {
                                    Text("\(ping) ms")
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }

                Section {
                    Button {
                        wallet.reconnect()
                    } label: {
                        Label("Reconnect", systemImage: "arrow.clockwise")
                    }
                }
            }
            .navigationTitle("Network")
            .refreshable {
                wallet.reconnect()
            }
        }
    }
}
