import SwiftUI
import CoreImage.CIFilterBuiltins

struct ReceiveView: View {
    @EnvironmentObject var wallet: WalletService
    @State private var selectedAddress: String = ""
    @State private var showCopied = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    if let qrImage = generateQR(from: selectedAddress) {
                        Image(uiImage: qrImage)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 220, height: 220)
                            .padding(16)
                            .background(Color.white)
                            .cornerRadius(16)
                    }

                    VStack(spacing: 8) {
                        Text(selectedAddress)
                            .font(.system(.caption, design: .monospaced))
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)

                        Button {
                            UIPasteboard.general.string = selectedAddress
                            showCopied = true
                            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { showCopied = false }
                        } label: {
                            Label(showCopied ? "Copied!" : "Copy Address",
                                  systemImage: showCopied ? "checkmark" : "doc.on.doc")
                        }
                        .buttonStyle(.bordered)
                    }

                    if wallet.addresses.count > 1 {
                        Picker("Address", selection: $selectedAddress) {
                            ForEach(wallet.addresses, id: \.self) { addr in
                                Text(addr.prefix(16) + "...").tag(addr)
                            }
                        }
                        .pickerStyle(.menu)
                        .padding(.horizontal)
                    }

                    Button {
                        Task { await wallet.generateAddress() }
                    } label: {
                        Label("Generate New Address", systemImage: "plus.circle")
                    }
                    .buttonStyle(.bordered)
                }
                .padding()
            }
            .navigationTitle("Receive")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { wallet.screen = .overview }
                }
            }
            .onAppear {
                selectedAddress = wallet.addresses.first ?? ""
            }
        }
    }

    private func generateQR(from string: String) -> UIImage? {
        guard !string.isEmpty else { return nil }
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage,
              let cgImage = context.createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
