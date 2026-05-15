import SwiftUI
import AVFoundation

struct QrScannerView: View {
    @EnvironmentObject var wallet: WalletService
    @State private var error: String? = nil
    // Capture which screen launched this scanner so we can return to the right place
    @State private var returnScreen: WalletService.Screen = .send

    var body: some View {
        NavigationStack {
            ZStack {
                QrCameraView { scanned in
                    handleScan(scanned)
                }
                .ignoresSafeArea()

                VStack {
                    Spacer()
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(Color.white, lineWidth: 2)
                        .frame(width: 240, height: 240)
                    Spacer()
                    Text("Scan a TIME address QR code")
                        .foregroundStyle(.white)
                        .padding()
                        .background(Color.black.opacity(0.5))
                        .cornerRadius(8)
                        .padding(.bottom, 40)
                }
            }
            .navigationTitle("Scan QR")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { wallet.screen = returnScreen }
                        .foregroundStyle(.white)
                }
            }
            .alert("Invalid QR", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                Button("OK") { error = nil }
            } message: { Text(error ?? "") }
            .onAppear {
                // Determine where to return based on which scanner was requested
                returnScreen = wallet.screen == .paymentRequestQrScanner ? .paymentRequest : .send
            }
        }
    }

    private func handleScan(_ text: String) {
        let addressPattern = #"TIME[01][1-9A-HJ-NP-Za-km-z]{30,}"#
        if let range = text.range(of: addressPattern, options: .regularExpression),
           Address.isValid(String(text[range])) {
            wallet.scannedAddress = String(text[range])
            wallet.screen = returnScreen
        } else {
            error = "Not a valid TIME address QR code"
        }
    }
}

struct QrCameraView: UIViewRepresentable {
    let onScan: (String) -> Void

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        let session = AVCaptureSession()

        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device) else { return view }

        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        session.addOutput(output)
        output.setMetadataObjectsDelegate(context.coordinator, queue: .main)
        output.metadataObjectTypes = [.qr]

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = UIScreen.main.bounds
        view.layer.addSublayer(preview)

        DispatchQueue.global(qos: .userInitiated).async { session.startRunning() }
        context.coordinator.session = session
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onScan: onScan) }

    class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        let onScan: (String) -> Void
        var session: AVCaptureSession?
        private var scanned = false

        init(onScan: @escaping (String) -> Void) { self.onScan = onScan }

        func metadataOutput(_ output: AVCaptureMetadataOutput,
                            didOutput objects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            guard !scanned,
                  let obj = objects.first as? AVMetadataMachineReadableCodeObject,
                  let str = obj.stringValue else { return }
            scanned = true
            session?.stopRunning()
            onScan(str)
        }
    }
}
