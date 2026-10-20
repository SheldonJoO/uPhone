import SwiftUI
import CoreBluetooth

struct SamsungPairingView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage(LocalModePreference.key) private var localModeEnabled = false
    @StateObject private var browser = SamsungPairingBrowser()
    @StateObject private var bleBrowser = BlePairingBrowser()
    @State private var code = ""
    @State private var bleCode = ""
    @State private var pairingServiceID: String?
    @State private var message = ""
    @State private var showingScanner = false
    @State private var connectingBLEID: UUID?

    var body: some View {
        Form {
            if localModeEnabled {
                Section {
                    Label("本地模式配对", systemImage: "wifi")
                        .foregroundStyle(.green)
                    Text("配对包只包含 VoWLAN 密钥，不检查或写入 Relay、APNs、PushKit 和 CallKit 参数。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            Section("连接步骤") {
                Label("让安卓与本机连接同一 Wi-Fi", systemImage: "wifi")
                Label("也可让本机连接安卓移动热点", systemImage: "personalhotspot")
                Label("在安卓 App 点击“开始一次性配对”", systemImage: "key")
            }

            Section {
                Button {
                    showingScanner = true
                } label: {
                    Label("扫码配对", systemImage: "qrcode.viewfinder")
                }
                .buttonStyle(.borderedProminent)
                Text("用相机扫描安卓屏幕上的二维码，免输配对码。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } header: {
                Text("快捷配对")
            }

            Section("蓝牙配对（Wi-Fi 不可用时）") {
                if bleBrowser.isScanning {
                    HStack {
                        ProgressView()
                        Text(bleBrowser.stateText).foregroundStyle(.secondary)
                    }
                } else {
                    Button {
                        bleBrowser.start()
                    } label: {
                        Label("扫描蓝牙设备", systemImage: "antenna.radiowaves.left.and.right")
                    }
                    .buttonStyle(.bordered)
                    Text(bleBrowser.stateText)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                ForEach(bleBrowser.candidates) { candidate in
                    VStack(alignment: .leading, spacing: 10) {
                        Label(candidate.displayName, systemImage: "dot.radiowaves.right")
                            .font(.headline)
                        if !candidate.deviceIDHint.isEmpty {
                            Text("设备 …\(candidate.deviceIDHint) · 信号 \(candidate.rssi) dBm")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        TextField("安卓显示的 6 位验证码", text: $bleCode)
                            .keyboardType(.numberPad)
                            .textContentType(.oneTimeCode)
                            .onChange(of: bleCode) { value in
                                bleCode = String(value.filter(\.isNumber).prefix(6))
                            }
                        Button("蓝牙配对", systemImage: "lock.shield") {
                            Task { await pairBLE(candidate) }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(bleCode.count != 6 || connectingBLEID != nil)
                    }
                    .padding(.vertical, 4)
                }
            }

            Section("发现的设备") {
                if browser.services.isEmpty {
                    HStack {
                        ProgressView()
                        Text(browser.stateText).foregroundStyle(.secondary)
                    }
                }
                ForEach(browser.services) { service in
                    VStack(alignment: .leading, spacing: 10) {
                        Label(service.displayName, systemImage: "iphone.radiowaves.left.and.right")
                            .font(.headline)
                        if !service.deviceIDHint.isEmpty {
                            Text("当前身份 …\(service.deviceIDHint)（重配将复用该身份）")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        TextField("安卓显示的 6 位验证码", text: $code)
                            .keyboardType(.numberPad)
                            .textContentType(.oneTimeCode)
                            .onChange(of: code) { value in
                                code = String(value.filter(\.isNumber).prefix(6))
                            }
                        Button("加密配对", systemImage: "lock.shield") {
                            Task { await pair(service) }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(code.count != 6 || pairingServiceID != nil)
                    }
                    .padding(.vertical, 4)
                }
            }

            if !message.isEmpty {
                Section("结果") { Text(message) }
            }
        }
        .navigationTitle("配对安卓开发机")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            browser.start()
        }
        .onDisappear {
            browser.stop()
            bleBrowser.stop()
        }
        .sheet(isPresented: $showingScanner) {
            NavigationStack {
                QRPairingScannerView(
                    onScanned: { service, scannedCode in
                        showingScanner = false
                        code = scannedCode
                        Task { await pair(service) }
                    },
                    onError: { error in
                        showingScanner = false
                        message = error
                    }
                )
                .navigationTitle("扫描配对二维码")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") { }
                    }
                }
            }
        }
    }

    // MARK: - 配对

    @MainActor
    private func pair(_ service: SamsungPairingService) async {
        pairingServiceID = service.id
        message = localModeEnabled
            ? "正在加密并写入 VoWLAN 本地密钥…"
            : "正在加密并写入 Android Agent…"
#if DEBUG
        print("[AirSIM Pair] claim_started session=\(service.id)")
#endif
        defer { pairingServiceID = nil }
        do {
            let registration = try pairingRegistration(for: service)
            message = try await SamsungPairingCompletionCoordinator.complete(
                performPairing: {
                    try await SamsungPairingClient.pair(
                        service: service,
                        code: code,
                        registration: registration
                    )
                },
                refreshVoWLAN: {
                    await model.vowlan.refreshAfterPairing()
                }
            )
            finishPairing(service: service, registration: registration)
        } catch {
            message = "配对失败：\(error.localizedDescription)"
        }
    }

    /// 蓝牙通道：先连接并读取会话材料，再走同一套 claim。
    @MainActor
    private func pairBLE(_ candidate: BlePairingCandidate) async {
        connectingBLEID = candidate.id
        message = "正在连接蓝牙设备 \(candidate.displayName)…"
        defer { connectingBLEID = nil }
        do {
            let filled = try await bleBrowser.connect(candidate)
            let service = SamsungPairingService(
                id: filled.sessionID,
                displayName: filled.displayName,
                transport: .ble(sessionID: filled.sessionID,
                                serverPublicKey: filled.serverPublicKey,
                                expiresAt: filled.expiresAt),
                deviceIDHint: filled.deviceIDHint
            )
            message = localModeEnabled
                ? "正在经蓝牙写入 VoWLAN 本地密钥…"
                : "正在经蓝牙写入 Android Agent…"
            let registration = try pairingRegistration(for: service)
            message = try await SamsungPairingCompletionCoordinator.complete(
                performPairing: {
                    try await SamsungPairingClient.pair(
                        service: service,
                        code: bleCode,
                        registration: registration,
                        bleBrowser: bleBrowser
                    )
                },
                refreshVoWLAN: {
                    await model.vowlan.refreshAfterPairing()
                }
            )
            finishPairing(service: service, registration: registration)
        } catch {
            message = "蓝牙配对失败：\(error.localizedDescription)"
        }
    }

    /// 配对成功后的登记逻辑。
    /// 同一台手机重配（did 匹配活跃设备）：复用身份，不归档、不产生 ghost。
    /// 真正的新设备：stage 新身份并归档旧设备。
    @MainActor
    private func finishPairing(service: SamsungPairingService, registration: AgentPushRegistration) {
        let isAdditionalDevice = !DeviceRegistry.allDevices().isEmpty
        let rePairSameDevice = isAdditionalDevice
            && !service.deviceIDHint.isEmpty
            && (DeviceRegistry.activeDeviceID()?.hasSuffix(service.deviceIDHint) ?? false)
        if isAdditionalDevice && !rePairSameDevice {
            if let device = DeviceRegistry.activateStagedDevice(
                deviceName: service.displayName,
                relayURL: registration.relayURL
            ) {
                Task { await VoIPPushController.shared.forceRegistrationSync() }
                model.clearIgnoredAgent(id: device.deviceID)
            } else {
                message = "配对已完成，但保存新设备凭证失败，请重试"
            }
        } else {
            // 首台设备 / 同机重配：登记进设备列表（幂等）
            DeviceRegistry.archiveActiveDeviceIfNeeded()
            if !registration.deviceID.isEmpty {
                model.clearIgnoredAgent(id: registration.deviceID)
            }
        }
        Task { await model.refreshPairedAndroidAgents() }
#if DEBUG
        print("[AirSIM Pair] claim_completed session=\(service.id)")
#endif
    }

    /// 按是否同一台手机重配选择注册体：
    /// 复用身份时用当前活跃密钥（不 stage 新的），从根上消除重复配对产生的 ghost。
    @MainActor
    private func pairingRegistration(for service: SamsungPairingService) throws -> AgentPushRegistration {
        let registryDevices = DeviceRegistry.allDevices()
        let isAdditionalDevice = !registryDevices.isEmpty
        let rePairSameDevice = isAdditionalDevice
            && !service.deviceIDHint.isEmpty
            && (DeviceRegistry.activeDeviceID()?.hasSuffix(service.deviceIDHint) ?? false)
        if rePairSameDevice || !isAdditionalDevice {
            guard let registration = VoIPPushController.shared.pairingRegistration() else {
                throw SamsungPairingClientError.registrationUnavailable
            }
            return registration
        }
        guard let registration = VoIPPushController.shared.pairingRegistrationForNewDevice() else {
            throw SamsungPairingClientError.registrationUnavailable
        }
        return registration
    }
}
