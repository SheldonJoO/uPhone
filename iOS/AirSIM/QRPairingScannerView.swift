import AVFoundation
import SwiftUI
import Network

/// Payload encoded in the Android pairing QR code.
/// {"v":1,"session":"...","key":"...","ip":"...","port":1234,"code":"123456","expires":...}
struct QRPairingPayload: Codable {
    let v: Int
    let session: String
    let key: String
    let ip: String
    let port: Int
    let code: String
    let expires: Double

    /// Build a SamsungPairingService for direct TCP pairing (bypasses Bonjour).
    func asService() -> SamsungPairingService? {
        guard v == 1,
              expires / 1_000 > Date().timeIntervalSince1970,
              let serverPublicKey = Data(base64URL: key),
              serverPublicKey.count == 32,
              code.range(of: #"^[0-9]{6}$"#, options: .regularExpression) != nil,
              (1...65535).contains(port)
        else { return nil }
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else { return nil }
        let endpoint = NWEndpoint.hostPort(host: NWEndpoint.Host(ip), port: nwPort)
        return SamsungPairingService(
            endpoint: endpoint,
            sessionID: session,
            serverPublicKey: serverPublicKey,
            expiresAt: Date(timeIntervalSince1970: expires / 1_000)
        )
    }
}

/// Camera QR scanner for pairing. Calls onScanned with the parsed service + code.
struct QRPairingScannerView: UIViewControllerRepresentable {
    var onScanned: (SamsungPairingService, String) -> Void
    var onError: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        ScannerController(onScanned: onScanned, onError: onError)
    }

    func updateUIViewController(_ uiViewController: ScannerController, context: Context) {}

    final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        private let onScanned: (SamsungPairingService, String) -> Void
        private let onError: (String) -> Void
        private var session: AVCaptureSession?
        private var handled = false

        init(onScanned: @escaping (SamsungPairingService, String) -> Void,
             onError: @escaping (String) -> Void) {
            self.onScanned = onScanned
            self.onError = onError
            super.init(nibName: nil, bundle: nil)
        }

        required init?(coder: NSCoder) { nil }

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            checkPermission()
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            session?.stopRunning()
        }

        private func checkPermission() {
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized:
                setupSession()
            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                    DispatchQueue.main.async {
                        granted ? self?.setupSession() : self?.onError("需要相机权限才能扫码配对")
                    }
                }
            default:
                onError("需要相机权限才能扫码配对，请在系统设置中开启")
            }
        }

        private func setupSession() {
            let session = AVCaptureSession()
            guard let device = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input)
            else {
                onError("相机初始化失败")
                return
            }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else {
                onError("相机初始化失败")
                return
            }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            output.metadataObjectTypes = [.qr]
            let preview = AVCaptureVideoPreviewLayer(session: session)
            preview.videoGravity = .resizeAspectFill
            preview.frame = view.bounds
            view.layer.addSublayer(preview)
            self.session = session
            session.startRunning()
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput,
                            didOutput metadataObjects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            guard !handled,
                  let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
                  let string = object.stringValue,
                  let data = string.data(using: .utf8),
                  let payload = try? JSONDecoder().decode(QRPairingPayload.self, from: data),
                  let service = payload.asService()
            else { return }
            handled = true
            session?.stopRunning()
            onScanned(service, payload.code)
        }
    }
}
