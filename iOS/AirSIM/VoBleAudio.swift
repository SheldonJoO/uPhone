import CoreBluetooth
import CryptoKit
import Foundation

// MARK: - G.711 µ-law 编解码（与安卓 MuLawCodec 逐字节一致）

/// 8kHz/16bit PCM ↔ 8bit µ-law（64 kbps，4:1 压缩）。
/// 解码用 ITU G.711 标准公式；编码表对解码表做最近邻合并生成，
/// 保证 encode/decode 往返一致，不依赖任何手写查表常数。
enum MuLawCodec {
    private static let decodeTable: [Int16] = buildDecodeTable()
    private static let encodeTable: [UInt8] = buildEncodeTable()

    static func decode(_ mulaw: Data) -> Data {
        guard !mulaw.isEmpty else { return Data() }
        var out = Data(repeating: 0, count: mulaw.count * 2)
        out.withUnsafeMutableBytes { ob in
            mulaw.withUnsafeBytes { mb in
                let m = mb.bindMemory(to: UInt8.self)
                let o = ob.bindMemory(to: UInt8.self)
                for index in 0 ..< mulaw.count {
                    let sample = decodeTable[Int(m[index])]
                    o[index * 2] = UInt8(sample & 0xff)
                    o[index * 2 + 1] = UInt8((sample >> 8) & 0xff)
                }
            }
        }
        return out
    }

    static func encode(_ pcm: Data) -> Data {
        let samples = pcm.count / 2
        guard samples > 0 else { return Data() }
        var out = Data(repeating: 0, count: samples)
        out.withUnsafeMutableBytes { ob in
            pcm.withUnsafeBytes { pb in
                let p = pb.bindMemory(to: UInt8.self)
                let o = ob.bindMemory(to: UInt8.self)
                for index in 0 ..< samples {
                    let lo = Int(p[index * 2]) & 0xff
                    let hi = Int(p[index * 2 + 1]) & 0xff
                    let bits = (lo | (hi << 8)) & 0xffff
                    o[index] = encodeTable[bits]
                }
            }
        }
        return out
    }

    private static func buildDecodeTable() -> [Int16] {
        var table = [Int16](repeating: 0, count: 256)
        for index in 0 ..< 256 {
            let value = (~index) & 0xff
            var magnitude = (((value & 0x0f) << 3) + 0x84)
            magnitude <<= (value & 0x70) >> 4
            let sample = ((value & 0x80) != 0) ? (0x84 - magnitude) : (magnitude - 0x84)
            table[index] = Int16(max(-32768, min(32767, sample)))
        }
        return table
    }

    private static func buildEncodeTable() -> [UInt8] {
        var table = [UInt8](repeating: 0, count: 65536)
        let dt = decodeTable
        var pos = 0x80
        for sample in 0 ... 32767 {
            while pos < 0xff, abs(Int(dt[pos + 1]) - sample) < abs(Int(dt[pos]) - sample) {
                pos += 1
            }
            table[sample] = UInt8(pos)
        }
        var neg = 0x00
        for magnitude in (1 ... 32768).reversed() {
            let sample = -magnitude
            while neg < 0x7f, abs(Int(dt[neg + 1]) - sample) < abs(Int(dt[neg]) - sample) {
                neg += 1
            }
            table[sample & 0xffff] = UInt8(neg)
        }
        return table
    }
}

// MARK: - VoBLE 客户端（三级链路中间档的 iOS 侧）

/// 发现并连接值守机的 VoBLE GATT 服务，承载通话控制与 µ-law 音频。
/// 鉴权与安卓 VoBleGateway 共用同一把 VoWLAN 配对密钥（HMAC，canonical `VOBLE1\nts\nnonce`）。
/// 非隔离类型：CoreBluetooth 以 `queue: nil` 在主线程派发回调，状态变更始终发生在主线程，
/// 因此可与 SwiftUI / @MainActor 的 AppModel 安全交互，同时允许实时音频线程直接调用发送。
final class VoBleClient: NSObject, ObservableObject {
    static let shared = VoBleClient()

    static let serviceUUID = CBUUID(string: "6E5F0005-B5A3-F393-E0A9-E50E24DCCA9E")
    static let audioUpUUID = CBUUID(string: "6E5F0006-B5A3-F393-E0A9-E50E24DCCA9E")
    static let audioDownUUID = CBUUID(string: "6E5F0007-B5A3-F393-E0A9-E50E24DCCA9E")
    static let controlUUID = CBUUID(string: "6E5F0008-B5A3-F393-E0A9-E50E24DCCA9E")

    @Published private(set) var isReady = false
    @Published private(set) var discoveredName: String?

    /// 安卓侧发起来电时经 CONTROL 通知上报（number, callerName）。
    var onIncomingCall: ((String, String) -> Void)?
    /// 下行 µ-law 帧（每 20ms 一帧），由音频传输层注入。
    var onDownlinkMulaw: (@Sendable (Data) -> Void)?

    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var audioDownChar: CBCharacteristic?
    private var controlChar: CBCharacteristic?
    private var sessionActive = false
    private var scanning = false
    private var sessionContinuation: CheckedContinuation<Bool, Never>?
    private var sessionTimer: DispatchWorkItem?

    private override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: nil)
    }

    func ensureScanning() {
        guard let manager = central else { return }
        guard manager.state == .poweredOn, !scanning else { return }
        scanning = true
        manager.scanForPeripherals(
            withServices: [Self.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
    }

    func stopScan() {
        guard let manager = central, scanning else { return }
        scanning = false
        manager.stopScan()
    }

    // MARK: 会话

    /// 经 CONTROL 写 start + HMAC，等待安卓回 ready 通知。
    func beginSession(credential: VoWLANCredential) async -> Bool {
        guard let peripheral, let controlChar else { return false }
        if sessionActive { return true }
        let timestamp = Int64(Date().timeIntervalSince1970)
        let nonce = VoWLANRequestSigner.makeNonce()
        let hmac = voBLEHMAC(secret: credential.secret, timestamp: timestamp, nonce: nonce)
        let payload: [String: Any] = [
            "action": "start",
            "timestamp": timestamp,
            "nonce": nonce,
            "hmac": hmac,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return false }
        return await withCheckedContinuation { continuation in
            sessionContinuation = continuation
            sessionTimer?.cancel()
            let timer = DispatchWorkItem { [weak self] in
                self?.failSession()
            }
            sessionTimer = timer
            DispatchQueue.main.asyncAfter(deadline: .now() + 6, execute: timer)
            peripheral.writeValue(data, for: controlChar, type: .withResponse)
        }
    }

    func sendUplink(_ mulaw: Data) {
        guard sessionActive, let peripheral, let audioUpChar = audioUpChar else { return }
        peripheral.writeValue(mulaw, for: audioUpChar, type: .withoutResponse)
    }

    func sendControl(_ payload: [String: Any]) {
        guard let peripheral, let controlChar else { return }
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return }
        peripheral.writeValue(data, for: controlChar, type: .withResponse)
    }

    func endSession() {
        sendControl(["action": "end"])
        sessionActive = false
    }

    private var audioUpChar: CBCharacteristic? {
        peripheral?.services?
            .first(where: { $0.uuid == Self.serviceUUID })?
            .characteristics?
            .first(where: { $0.uuid == Self.audioUpUUID })
    }

    private func failSession() {
        sessionTimer?.cancel()
        sessionTimer = nil
        if let continuation = sessionContinuation {
            sessionContinuation = nil
            continuation.resume(returning: false)
        }
    }

    private func voBLEHMAC(secret: Data, timestamp: Int64, nonce: String) -> String {
        let canonical = "VOBLE1\n\(timestamp)\n\(nonce)"
        let key = SymmetricKey(data: secret)
        let mac = HMAC<SHA256>.authenticationCode(for: Data(canonical.utf8), using: key)
        return Data(mac).base64URL
    }
}

extension VoBleClient: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            ensureScanning()
        } else {
            isReady = false
            discoveredName = nil
            peripheral = nil
        }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi: NSNumber?) {
        self.peripheral = peripheral
        peripheral.delegate = self
        central.stopScan()
        scanning = false
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([Self.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        isReady = false
        ensureScanning()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        isReady = false
        sessionActive = false
        discoveredName = nil
        ensureScanning()
    }
}

extension VoBleClient: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil,
              let service = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else {
            return
        }
        peripheral.discoverCharacteristics(
            [Self.audioUpUUID, Self.audioDownUUID, Self.controlUUID],
            for: service
        )
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil else { return }
        for characteristic in service.characteristics ?? [] {
            if characteristic.uuid == Self.audioDownUUID {
                audioDownChar = characteristic
                peripheral.setNotifyValue(true, for: characteristic)
            } else if characteristic.uuid == Self.controlUUID {
                controlChar = characteristic
            }
        }
        if audioDownChar != nil, controlChar != nil {
            discoveredName = peripheral.name
            isReady = true
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let value = characteristic.value else { return }
        if characteristic.uuid == Self.audioDownUUID {
            onDownlinkMulaw?(value)
        } else if characteristic.uuid == Self.controlUUID {
            handleControlNotify(value)
        }
    }

    private func handleControlNotify(_ value: Data) {
        guard let json = try? JSONSerialization.jsonObject(with: value) as? [String: Any] else { return }
        if let status = json["status"] as? String, status == "ready" {
            sessionTimer?.cancel()
            sessionTimer = nil
            sessionActive = true
            if let continuation = sessionContinuation {
                sessionContinuation = nil
                continuation.resume(returning: true)
            }
            return
        }
        if let action = json["action"] as? String, action == "incoming",
           let number = json["number"] as? String {
            let callerName = (json["caller_name"] as? String) ?? ""
            onIncomingCall?(number, callerName)
        }
    }
}

// MARK: - VoBLE 音频传输层（接入 AudioSessionController）

/// 把 BLE µ-law 上下行桥接到 AudioSessionController 的 320 字节 PCM 帧回调，
/// 与 NetworkPCMTransport 同形，使三级路由（VoWLAN › VoBLE › 云）可无缝切换。
final class VoBlePCMTransport: PCMTransport {
    private let onPCM: @Sendable (Data) -> Void
    private let onEvent: @Sendable (NetworkPCMTransport.Event) -> Void

    init(
        onPCM: @escaping @Sendable (Data) -> Void,
        onEvent: @escaping @Sendable (NetworkPCMTransport.Event) -> Void
    ) {
        self.onPCM = onPCM
        self.onEvent = onEvent
        // 下行 µ-law → PCM 直接喂给音频引擎；不捕获 self，避免 @Sendable 隔离冲突。
        VoBleClient.shared.onDownlinkMulaw = { [downlink = onPCM] mulaw in
            downlink(MuLawCodec.decode(mulaw))
        }
    }

    func start() {
        onEvent(.status("正在通过 VoBLE 连接语音…"))
        onEvent(.connected)
        onEvent(.status("VoBLE PCM 已握手，等待语音数据"))
    }

    func stop() {
        VoBleClient.shared.endSession()
        onEvent(.closed)
    }

    func sendPCM(_ pcm: Data) {
        guard !pcm.isEmpty else { return }
        VoBleClient.shared.sendUplink(MuLawCodec.encode(pcm))
    }
}
