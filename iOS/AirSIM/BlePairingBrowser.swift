import Foundation
import CoreBluetooth

/// BLE 配对候选：扫描阶段只有周边信息，连接后补齐会话材料。
struct BlePairingCandidate: Identifiable, Equatable {
    let id: UUID              // CBPeripheral.identifier
    var name: String
    var rssi: Int
    var sessionID: String = ""
    var serverPublicKey: Data = Data()
    var expiresAt: Date = .distantFuture
    var deviceIDHint: String = ""
    /// 安卓稳定身份（ANDROID_ID 派生）；本地模式配对无 relay id 时的同机重配依据。
    var androidAgentID: String = ""
    /// 安卓广播的设备名（如「TCL T508N」）。
    var androidName: String = ""

    var hasSessionMaterial: Bool { !sessionID.isEmpty && !serverPublicKey.isEmpty }

    var displayName: String { name.isEmpty ? "安卓手机（蓝牙）" : name }
}

/// 蓝牙配对通道：Wi-Fi 不可用（AP 隔离 / 只连热点 / NSD 被挡）时的替代握手。
/// 协议与 TCP 通道完全一致——同一份 X25519 + 6 位码密封的 claim，
/// 只是把字节改走 GATT 特征读写。媒体与信令仍走 VoWLAN / 云中继。
@MainActor
final class BlePairingBrowser: NSObject, ObservableObject {
    @Published private(set) var candidates: [BlePairingCandidate] = []
    @Published private(set) var stateText = "正在扫描蓝牙设备…"
    @Published private(set) var isScanning = false

    private var central: CBCentralManager?
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var connected: CBPeripheral?
    private var sessionCharacteristic: CBCharacteristic?
    private var claimCharacteristic: CBCharacteristic?
    private var responseCharacteristic: CBCharacteristic?

    static let serviceUUID = CBUUID(string: "6E5F0001-B5A3-F393-E0A9-E50E24DCCA9E")
    private static let sessionCharacteristicUUID = CBUUID(string: "6E5F0002-B5A3-F393-E0A9-E50E24DCCA9E")
    private static let claimCharacteristicUUID = CBUUID(string: "6E5F0003-B5A3-F393-E0A9-E50E24DCCA9E")
    private static let responseCharacteristicUUID = CBUUID(string: "6E5F0004-B5A3-F393-E0A9-E50E24DCCA9E")

    func start() {
        guard central == nil else { return }
        let manager = CBCentralManager(delegate: self, queue: nil)
        central = manager
    }

    func stop() {
        if let connected {
            central?.cancelPeripheralConnection(connected)
        }
        connected = nil
        sessionCharacteristic = nil
        claimCharacteristic = nil
        responseCharacteristic = nil
        central?.stopScan()
        central = nil
        peripherals.removeAll()
        candidates = []
        isScanning = false
    }

    private func beginScan() {
        guard let central, central.state == .poweredOn else {
            stateText = "蓝牙未就绪，请在系统设置中开启蓝牙"
            return
        }
        peripherals.removeAll()
        candidates = []
        central.scanForPeripherals(withServices: [Self.serviceUUID], options: [
            CBCentralManagerScanOptionAllowDuplicatesKey: false
        ])
        isScanning = true
        stateText = "正在扫描附近的安卓值守机…"
    }

    /// 连接候选设备并读取一次性会话材料。
    func connect(_ candidate: BlePairingCandidate) async throws -> BlePairingCandidate {
        guard let central else { throw BlePairingError.bluetoothUnavailable }
        guard let peripheral = peripherals[candidate.id] ?? connected,
              peripheral.identifier == candidate.id else {
            throw BlePairingError.peripheralLost
        }
        connected = peripheral
        peripheral.delegate = self
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connectContinuation = continuation
            central.connect(peripheral, options: nil)
        }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            discoveryContinuation = continuation
            peripheral.discoverServices([Self.serviceUUID])
        }
        guard let sessionCharacteristic else { throw BlePairingError.serviceIncomplete }
        let sessionData = try await read(sessionCharacteristic)
        guard let session = try? JSONDecoder().decode(BleSessionInfo.self, from: sessionData) else {
            throw BlePairingError.invalidSession
        }
        guard session.expiresAt > Date() else { throw BlePairingError.expired }
        var filled = candidate
        filled.sessionID = session.session
        filled.serverPublicKey = session.serverPublicKey
        filled.expiresAt = session.expiresAt
        filled.deviceIDHint = session.did ?? ""
        filled.androidAgentID = session.aid ?? ""
        filled.androidName = session.name ?? ""
        if let index = candidates.firstIndex(where: { $0.id == filled.id }) {
            candidates[index] = filled
        }
        return filled
    }

    /// 写入加密 claim 并读取安卓的处理结果（响应经特征值 + 通知双路返回）。
    func exchange(claim: Data) async throws -> Data {
        guard let peripheral = connected, let claimCharacteristic else {
            throw BlePairingError.notConnected
        }
        pendingNotifiedResponse = nil
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            writeContinuation = continuation
            peripheral.writeValue(claim, for: claimCharacteristic, type: .withResponse)
        }
        // 响应可能先经 notify 到达；等一小段时间让两条路径都落地后再读。
        for _ in 0..<20 {
            if let pendingNotifiedResponse { return pendingNotifiedResponse }
            if let responseCharacteristic, let value = responseCharacteristic.value, !value.isEmpty {
                return value
            }
            try? await Task.sleep(for: .milliseconds(150))
        }
        guard let responseCharacteristic else { throw BlePairingError.serviceIncomplete }
        return try await read(responseCharacteristic)
    }

    private func read(_ characteristic: CBCharacteristic) async throws -> Data {
        guard let peripheral = connected else { throw BlePairingError.notConnected }
        if let value = characteristic.value, !value.isEmpty { return value }
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            readContinuation = continuation
            peripheral.readValue(for: characteristic)
        }
    }

    // MARK: - 异步桥接状态

    private var connectContinuation: CheckedContinuation<Void, Error>?
    private var discoveryContinuation: CheckedContinuation<Void, Error>?
    private var writeContinuation: CheckedContinuation<Void, Error>?
    private var readContinuation: CheckedContinuation<Data, Error>?
    /// notify 先行到达的响应：exchange 里优先兑现，省掉一次读取往返。
    private var pendingNotifiedResponse: Data?

    private func fail(_ error: Error) {
        connectContinuation?.resume(throwing: error)
        connectContinuation = nil
        discoveryContinuation?.resume(throwing: error)
        discoveryContinuation = nil
        writeContinuation?.resume(throwing: error)
        writeContinuation = nil
        readContinuation?.resume(throwing: error)
        readContinuation = nil
    }
}

private struct BleSessionInfo: Decodable {
    let v: Int
    let session: String
    let key: String
    let expires: Double
    let did: String?
    /// 安卓稳定身份（ANDROID_ID 派生），本地模式同机重配判定用。
    let aid: String?
    /// 安卓设备名（如「TCL T508N」）。
    let name: String?

    enum CodingKeys: String, CodingKey {
        case v, session, key, expires, did, aid, name
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        v = try values.decodeIfPresent(Int.self, forKey: .v) ?? 1
        session = try values.decode(String.self, forKey: .session)
        key = try values.decode(String.self, forKey: .key)
        expires = try values.decode(Double.self, forKey: .expires)
        did = try values.decodeIfPresent(String.self, forKey: .did)
        aid = try values.decodeIfPresent(String.self, forKey: .aid)
        name = try values.decodeIfPresent(String.self, forKey: .name)
    }

    var expiresAt: Date { Date(timeIntervalSince1970: expires / 1000) }
    var serverPublicKey: Data { Data(base64URL: key) ?? Data() }
}

enum BlePairingError: LocalizedError {
    case bluetoothUnavailable
    case peripheralLost
    case notConnected
    case serviceIncomplete
    case invalidSession
    case expired
    case exchangeFailed(String)

    var errorDescription: String? {
        switch self {
        case .bluetoothUnavailable: return "蓝牙未就绪，请在系统设置中开启蓝牙"
        case .peripheralLost: return "蓝牙设备已离开，请重新扫描"
        case .notConnected: return "蓝牙连接未建立"
        case .serviceIncomplete: return "安卓蓝牙配对服务不完整，请更新安卓端 uPhone"
        case .invalidSession: return "安卓返回的配对会话无效"
        case .expired: return "配对窗口已过期，请在安卓端重新开启"
        case let .exchangeFailed(message): return message
        }
    }
}

extension BlePairingBrowser: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            switch central.state {
            case .poweredOn: beginScan()
            case .unauthorized: stateText = "蓝牙未授权：请在系统设置中允许 uPhone 使用蓝牙"
            case .poweredOff: stateText = "蓝牙已关闭，请开启后重试"
            default: stateText = "蓝牙不可用"
            }
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        Task { @MainActor in
            peripherals[peripheral.identifier] = peripheral
            let name = peripheral.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? ""
            var candidate = BlePairingCandidate(id: peripheral.identifier, name: name, rssi: RSSI.intValue)
            if let index = candidates.firstIndex(where: { $0.id == candidate.id }) {
                candidate.sessionID = candidates[index].sessionID
                candidate.serverPublicKey = candidates[index].serverPublicKey
                candidate.expiresAt = candidates[index].expiresAt
                candidate.deviceIDHint = candidates[index].deviceIDHint
            }
            upsert(candidate)
            stateText = candidates.isEmpty ? "正在扫描附近的安卓值守机…" : "已发现 \(candidates.count) 台设备"
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            connectContinuation?.resume(returning: ())
            connectContinuation = nil
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        Task { @MainActor in fail(error ?? BlePairingError.notConnected) }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        Task { @MainActor in
            if connected?.identifier == peripheral.identifier {
                connected = nil
                sessionCharacteristic = nil
                claimCharacteristic = nil
                responseCharacteristic = nil
            }
        }
    }

    @MainActor private func upsert(_ candidate: BlePairingCandidate) {
        if let index = candidates.firstIndex(where: { $0.id == candidate.id }) {
            candidates[index] = candidate
        } else {
            candidates.append(candidate)
        }
        candidates.sort { $0.rssi > $1.rssi }
    }
}

extension BlePairingBrowser: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        Task { @MainActor in
            if let error { fail(error); return }
            guard let service = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else {
                fail(BlePairingError.serviceIncomplete)
                return
            }
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        Task { @MainActor in
            if let error { fail(error); return }
            sessionCharacteristic = service.characteristics?.first { $0.uuid == Self.sessionCharacteristicUUID }
            claimCharacteristic = service.characteristics?.first { $0.uuid == Self.claimCharacteristicUUID }
            responseCharacteristic = service.characteristics?.first { $0.uuid == Self.responseCharacteristicUUID }
            discoveryContinuation?.resume(returning: ())
            discoveryContinuation = nil
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        Task { @MainActor in
            if let error { fail(error); return }
            if characteristic.uuid == Self.responseCharacteristicUUID,
               let value = characteristic.value, !value.isEmpty {
                // notify 路径的响应：优先兑现，省掉后续读取。
                if let read = readContinuation {
                    readContinuation = nil
                    read.resume(returning: value)
                } else {
                    pendingNotifiedResponse = value
                }
            } else if characteristic.uuid == Self.sessionCharacteristicUUID {
                if let read = readContinuation {
                    readContinuation = nil
                    read.resume(returning: characteristic.value ?? Data())
                }
            } else {
                if let read = readContinuation {
                    readContinuation = nil
                    read.resume(returning: characteristic.value ?? Data())
                }
            }
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didWriteValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        Task { @MainActor in
            if let error { fail(error); return }
            writeContinuation?.resume(returning: ())
            writeContinuation = nil
        }
    }
}
