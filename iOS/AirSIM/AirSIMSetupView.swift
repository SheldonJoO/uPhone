import SwiftUI
import Foundation

/// AirSIM 只引导安卓配对；不检查旧模块 USB ECM、AT 或 QDC507 固件。
struct AirSIMFirstConnectionView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage("airsim.first-connection-complete") private var completed = false
    @AppStorage(LocalModePreference.key) private var localModeEnabled = false
    @AppStorage(CloudModePreference.key) private var cloudModeEnabled = true
    @State private var showingPairing = false

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 24) {
                Image(systemName: "iphone.radiowaves.left.and.right")
                    .font(.system(size: 42, weight: .medium))
                    .foregroundStyle(.blue)
                    .frame(width: 76, height: 76)
                    .background(.blue.opacity(0.1), in: RoundedRectangle(cornerRadius: 22))

                VStack(alignment: .leading, spacing: 8) {
                    Text("连接安卓手机")
                        .font(.largeTitle.bold())
                    Text("üMobile Kit 通过同一 Wi-Fi 或安卓热点建立直连；离开局域网时可使用独立云中继。")
                        .foregroundStyle(.secondary)
                }

                VStack(alignment: .leading, spacing: 18) {
                    Label("在安卓手机启动 uPhone 和音频桥", systemImage: "1.circle.fill")
                    Label("将两台设备连接到同一局域网", systemImage: "2.circle.fill")
                    Label("输入安卓显示的六位配对码", systemImage: "3.circle.fill")
                }
                .font(.body.weight(.medium))
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24))

                Toggle(isOn: $localModeEnabled) {
                    VStack(alignment: .leading, spacing: 3) {
                        Label("仅本地模式", systemImage: "wifi")
                            .font(.headline)
                        Text("只使用 VoWLAN，不需要 Relay、PushKit 或 CallKit")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .onChange(of: localModeEnabled) { enabled in
                    if enabled { cloudModeEnabled = false }
                    model.setLocalModeEnabled(enabled)
                }

                Spacer()

                Button("开始配对", systemImage: "lock.shield") {
                    showingPairing = true
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(maxWidth: .infinity)

                Button("稍后在设置中配对") { completed = true }
                    .frame(maxWidth: .infinity)
            }
            .padding(24)
            .navigationTitle("üMobile Kit")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $showingPairing, onDismiss: {
                if model.vowlan.availability.isOnlineForDisplay { completed = true }
            }) {
                NavigationStack {
                    SamsungPairingView()
                        .environmentObject(model)
                        .toolbar {
                            ToolbarItem(placement: .confirmationAction) {
                                Button("完成") { showingPairing = false }
                            }
                        }
                }
            }
        }
    }
}

// MARK: - 短信铃声选项
enum SMSRingtoneOption: String, CaseIterable, Identifiable {
    case followSystem = "follow_system"
    case `default` = "default"
    case silent = "silent"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .followSystem: return "跟随系统"
        case .default: return "默认"
        case .silent: return "静音"
        }
    }
}

struct AirSIMSettingsView: View {
    private enum SelfTestPhase {
        case idle
        case running
        case completed
    }

    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    @AppStorage(LocalModePreference.key) private var localModeEnabled = false
    @AppStorage(CloudModePreference.key) private var cloudModeEnabled = true
    @AppStorage("smsRingtoneOption") private var smsRingtone: SMSRingtoneOption = .followSystem
    @State private var relayURL = RelayConfiguration.effectiveURL(
        stored: UserDefaults.standard.string(forKey: "airsim.push-relay-url"),
        buildSetting: Bundle.main.object(forInfoDictionaryKey: "AirSIMPushRelayURL") as? String,
        bundleID: Bundle.main.bundleIdentifier
    )
    @State private var message: String?
    @State private var selfTestPhase: SelfTestPhase = .idle
    @State private var selfTestReport: CloudSelfTestReport?
    @State private var selfTestTask: Task<Void, Never>?
    @AppStorage("sideloadSkipPushCredentials") private var skipPushForSelfTest = false
    @State private var localSelfTestPhase: SelfTestPhase = .idle
    @State private var localSelfTestReport: CloudSelfTestReport?
    @State private var localSelfTestTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    GuardDeviceRows()
                } header: {
                    Text("值守机")
                } footer: {
                    Text("本地和云端不再是两种设备，而是一台设备的两条线路：同一 Wi-Fi 下优先 Wi-Fi 直连，在外地时经云中继。点选切换当前值守机。")
                }

                Section {
                    LabeledContent("Wi-Fi 直连", value: VoWLANStatusCopy.text(for: model.vowlan.availability))
                    Button("重新发现", systemImage: "arrow.clockwise") {
                        model.vowlan.restartBrowsing()
                        Task { await model.vowlan.probeNow() }
                    }
                    Toggle(isOn: $cloudModeEnabled) {
                        Label("远程通话与短信", systemImage: "cloud")
                    }
                    .disabled(localModeEnabled)
                    .onChange(of: cloudModeEnabled) { enabled in
                        selfTestTask?.cancel()
                        selfTestPhase = .idle
                        selfTestReport = nil
                        Task {
                            if let (api, _) = await model.readyVoWLANRoute() {
                                await VoIPPushController.shared.setCloudModeEnabled(enabled, with: api)
                            } else {
                                await VoIPPushController.shared.setCloudModeEnabled(enabled)
                            }
                            await model.refreshCloudStatusForDiagnostics()
                        }
                    }
                    Toggle(isOn: $localModeEnabled) {
                        Label("仅 Wi-Fi 直连模式", systemImage: "wifi")
                    }
                    .onChange(of: localModeEnabled) { enabled in
                        selfTestTask?.cancel()
                        selfTestPhase = .idle
                        selfTestReport = nil
                        if enabled { cloudModeEnabled = false }
                        model.setLocalModeEnabled(enabled)
                    }

                    TextField("https://relay.example.com", text: $relayURL)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(localModeEnabled)
                    Button("保存中继地址") {
                        VoIPPushController.shared.updateRelayURL(relayURL)
                        selfTestTask?.cancel()
                        selfTestPhase = .idle
                        selfTestReport = nil
                        Task {
                            if let (api, _) = await model.readyVoWLANRoute() {
                                await VoIPPushController.shared.syncRegistration(with: api)
                            }
                            await model.refreshCloudStatusForDiagnostics()
                        }
                        message = "地址已保存；配对后自动注册。"
                    }
                    .disabled(localModeEnabled)
                    if let message { Text(message).foregroundStyle(.secondary) }
                    if let connectionMessage = model.connectionMessage, !connectionMessage.isEmpty {
                        Text(connectionMessage)
                            .font(.footnote)
                            .foregroundStyle(.orange)
                    }
                } header: {
                    Text("连接方式")
                } footer: {
                    Text("云中继关闭后，只有同一 Wi-Fi 下的值守机可用；人在外地时请保持云中继开启。")
                }

                Section {
                    NavigationLink {
                        HardwareModuleSettingsView()
                    } label: {
                        Label("硬件值守模块（大疆 / 移远）", systemImage: "cpu")
                    }
                } header: {
                    Text("硬件值守模块")
                } footer: {
                    Text("可选：用大疆 DJI 或移远 Quectel 4G 模块代替安卓手机做值守后端（Mac / NAS 常驻场景）。当前为预留配置入口，选定后按界面指引接入。")
                }

                Section {
                    if localModeEnabled {
                        Button {
                            startLocalSelfTest()
                        } label: {
                            HStack {
                                Label("运行本地连接自检", systemImage: "stethoscope")
                                Spacer()
                                if localSelfTestPhase == .running {
                                    ProgressView()
                                }
                            }
                        }
                        .disabled(localSelfTestPhase == .running)

                        if let report = localSelfTestReport {
                            ForEach(report.steps) { step in
                                HStack(alignment: .top, spacing: 12) {
                                    Image(systemName: selfTestSymbol(for: step.state))
                                        .foregroundStyle(selfTestColor(for: step.state))
                                        .frame(width: 20)
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(step.title)
                                        Text(step.detail)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
                            if localSelfTestPhase == .completed {
                                Text(report.allPassed
                                     ? "本地连接正常：可经同一 Wi-Fi 收发来电与短信"
                                     : "本地自检未通过，请按失败步骤修复后重试")
                                    .font(.footnote.weight(.medium))
                                    .foregroundStyle(report.allPassed ? .green : .orange)
                            }
                        }
                    } else {
                        Button {
                            startCloudSelfTest()
                        } label: {
                            HStack {
                                Label("运行云端模式自检", systemImage: "stethoscope")
                                Spacer()
                                if selfTestPhase == .running {
                                    ProgressView()
                                }
                            }
                        }
                        .disabled(localModeEnabled || selfTestPhase == .running)

                        Toggle("侧载测试：跳过 Push 凭据检查", isOn: $skipPushForSelfTest)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .disabled(localModeEnabled || selfTestPhase == .running)

                        if let report = selfTestReport {
                            ForEach(report.steps) { step in
                                HStack(alignment: .top, spacing: 12) {
                                    Image(systemName: selfTestSymbol(for: step.state))
                                        .foregroundStyle(selfTestColor(for: step.state))
                                        .frame(width: 20)
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(step.title)
                                        Text(step.detail)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
                            if selfTestPhase == .completed {
                                Text(report.allPassed ? "云端模式自检通过" : "自检未通过，请按失败步骤修复后重试")
                                    .font(.footnote.weight(.medium))
                                    .foregroundStyle(report.allPassed ? .green : .orange)
                            }
                        }
                    }
                } header: {
                    Text(localModeEnabled ? "本地连接自检" : "云端模式自检")
                } footer: {
                    Text(localModeEnabled
                        ? "验证本地 VoWLAN 配对凭据与局域网信令可达；不会拨号或发送短信。若显示未连接，请确认 iPhone 与安卓处于同一 Wi-Fi，且在安卓端已开启 uPhone 与音频桥。"
                        : "依次验证本机 Push 凭据、uPhone Relay 身份、设备注册和 Agent 最近 90 秒心跳；不会拨号或发送短信。免费侧载无 PushKit 能力时可打开上方跳过开关。若只在局域网使用，请在上方开启「仅 Wi-Fi 直连模式」后改用本地连接自检。")
                }

                Section("来电策略") {
                    NavigationLink {
                        CallPolicyView()
                    } label: {
                        Label("黑白名单与静默时段", systemImage: "phone.badge.waveform")
                    }
                    Text("黑名单自动拒接；白名单模式下非名单号码一律拒接；静默时段内来电不响铃直接挂断，事件照常记录。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("声音") {
                    Button {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    } label: {
                        LabeledContent {
                            Text("跟随系统")
                                .foregroundStyle(.secondary)
                        } label: {
                            Label("来电铃声", systemImage: "bell")
                        }
                    }
                    .buttonStyle(.plain)
                    Text("系统来电界面播放 iPhone 电话铃声（前往 iPhone 设置 > 声音与触感 > 电话铃声更改）；App 内来电界面播放应用内铃声，并跟随静音开关。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Picker(selection: $smsRingtone) {
                        ForEach(SMSRingtoneOption.allCases) { option in
                            Text(option.title).tag(option)
                        }
                    } label: {
                        Label("短信铃声", systemImage: "message.badge")
                    }
                    .pickerStyle(.navigationLink)
                }

                Section("关于") {
                    LabeledContent("项目", value: "üMobile Kit · 安卓")
                    Picker("外观", selection: $settings.appearance) {
                        ForEach(AppAppearance.allCases) { appearance in
                            Text(appearance.title).tag(appearance)
                        }
                    }
                }

                Section {
                    NavigationLink {
                        AIAssistantSettingsView()
                    } label: {
                        Label {
                            Text("AI 电话助理")
                        } icon: {
                            Image(systemName: "sparkles")
                        }
                    }
                } header: {
                    Text("智能扩展")
                } footer: {
                    Text("预留接口：未来可接入 Muse、豆包、Grok 等 AI 助手，用自然语言管理电话与短信。")
                }
            }
            .navigationTitle("设置")
            .task {
                if !localModeEnabled {
                    await model.refreshCloudStatusForDiagnostics()
                }
            }
            .onDisappear {
                selfTestTask?.cancel()
                selfTestTask = nil
                if selfTestPhase == .running { selfTestPhase = .idle }
            }
        }
    }

    private func startCloudSelfTest() {
        selfTestTask?.cancel()
        selfTestPhase = .running
        selfTestReport = .initial
        selfTestTask = Task { @MainActor in
            let skipPush = skipPushForSelfTest
            let report = await VoIPPushController.shared.runCloudSelfTest(skipPushCredentials: skipPush) { update in
                guard !Task.isCancelled else { return }
                selfTestReport = update
            }
            guard !Task.isCancelled else { return }
            selfTestReport = report
            selfTestPhase = .completed
            await model.refreshCloudStatusForDiagnostics()
        }
    }

    /// 本地（VoWLAN）自检：仅在「仅 Wi-Fi 直连模式」开启时使用。
    /// 验证本地配对凭据与局域网信令可达，不再依赖云端 Relay / PushKit / CallKit。
    private func startLocalSelfTest() {
        localSelfTestTask?.cancel()
        localSelfTestPhase = .running
        let seed: [CloudSelfTestStep] = [
            CloudSelfTestStep(id: .configuration, title: "本地配对凭据", state: .pending, detail: "等待检查"),
            CloudSelfTestStep(id: .agentHeartbeat, title: "VoWLAN 局域网连接", state: .pending, detail: "等待检查")
        ]
        localSelfTestReport = CloudSelfTestReport(steps: seed)
        localSelfTestTask = Task { @MainActor in
            var report = CloudSelfTestReport(steps: seed)
            // 步骤一：本地 VoWLAN 配对凭据是否存在。
            if VoWLANCredentialStore.load() == nil {
                report.update(.configuration, state: .failed,
                              detail: "尚未配对本地 VoWLAN，请先在「连接安卓手机」中完成配对")
                report.completedAt = Date()
                localSelfTestReport = report
                localSelfTestPhase = .completed
                return
            }
            report.update(.configuration, state: .passed, detail: "本地 VoWLAN 配对凭据已就绪")
            // 步骤二：重新发现并验证安卓 VoWLAN 局域网信令。
            report.update(.agentHeartbeat, state: .running, detail: "正在发现并验证安卓 VoWLAN")
            localSelfTestReport = report
            model.vowlan.restartBrowsing()
            await model.vowlan.probeNow()
            let availability = model.vowlan.availability
            switch availability {
            case .verified:
                var detail = "VoWLAN 已连接，信令可达"
                // VoWLAN 通了不代表 Agent 健康：顺手查一次安卓的真实心跳状态，
                // 免得「心跳一直测不到」在本地模式下无任何入口可查。
                if let endpoint = model.vowlan.availability.endpoint,
                   let credential = VoWLANCredentialStore.load() {
                    let api = AirSIMAPI(route: .vowlan(endpoint: endpoint, credential: credential))
                    if let status = try? await api.pushStatus() {
                        if let age = status.heartbeatAgeSeconds {
                            detail += status.heartbeatFresh
                                ? " · Agent 心跳正常（\(age) 秒前）"
                                : " · Agent 云端心跳已停（\(age) 秒前）"
                        } else {
                            detail += " · 云端心跳未启用（纯本地模式）"
                        }
                        if let lastError = status.lastError, !lastError.isEmpty {
                            detail += " · 最近错误：\(lastError)"
                        }
                    }
                }
                report.update(.agentHeartbeat, state: .passed, detail: detail)
            case .unavailable(let reason):
                report.update(.agentHeartbeat, state: .failed, detail: reason)
            case .unpaired:
                report.update(.agentHeartbeat, state: .failed, detail: "未配对 VoWLAN")
            default:
                report.update(.agentHeartbeat, state: .failed,
                              detail: "未发现安卓 VoWLAN：请确认两台设备同一 Wi-Fi，且安卓已启动 uPhone 与音频桥")
            }
            report.completedAt = Date()
            localSelfTestReport = report
            localSelfTestPhase = .completed
        }
    }

    private func selfTestSymbol(for state: CloudSelfTestStepState) -> String {
        switch state {
        case .pending: return "circle"
        case .running: return "arrow.triangle.2.circlepath"
        case .passed: return "checkmark.circle.fill"
        case .failed: return "xmark.circle.fill"
        }
    }

    private func selfTestColor(for state: CloudSelfTestStepState) -> Color {
        switch state {
        case .pending: return .secondary
        case .running: return .blue
        case .passed: return .green
        case .failed: return .red
        }
    }
}

/// 多设备管理：配对过的安卓值守机列表，点选切换当前设备。
/// 线路徽章：Wi-Fi 直连 / 云中继 / 离线；两条线路同时在线时并列显示。
private struct DeviceLinkBadge: View {
    let kinds: [DeviceLinkKind]
    let title: String

    private var color: Color {
        if kinds.contains(.cloud) && kinds.contains(.wifiDirect) { return .green }
        switch kinds.first {
        case .wifiDirect: return .blue
        case .cloud: return .green
        default: return .secondary
        }
    }

    var body: some View {
        HStack(spacing: 4) {
            ForEach(kinds, id: \.self) { kind in
                Circle().fill(kind == .cloud ? Color.green : Color.blue)
                    .frame(width: 6, height: 6)
            }
            Text(title).font(.caption2.weight(.medium))
        }
        .foregroundStyle(color)
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(color.opacity(0.1), in: Capsule())
    }
}

/// 统一的值守机列表：拨号页状态弹窗和"设置-值守机"共用同一套。
/// 本地 / 云端不再是两种设备，而是一台设备的两条线路。
struct GuardDeviceRows: View {
    @EnvironmentObject private var model: AppModel
    @State private var renamingDevice: AndroidAgentPresentation?
    @State private var renameText = ""
    @State private var switching = false
    /// 出号卡变化时刷新各行的选中态。
    @State private var selectionTick = 0

    private var agents: [AndroidAgentPresentation] { model.pairedAndroidAgents }

    private func selectedSimID(for agent: AndroidAgentPresentation) -> Int? {
        _ = selectionTick
        return SimSelectionStore.selectedSubscriptionID(for: agent.id)
    }

    var body: some View {
        Group {
            if agents.isEmpty {
                // 空状态做成一行（而不是 overlay）：ForEach 为空时没有尺寸，overlay 显示不出来。
                VStack(spacing: 10) {
                    Image(systemName: "smartphone.slash")
                        .font(.system(size: 34))
                        .foregroundStyle(.secondary)
                    Text("暂无值守机").font(.headline)
                    Text("添加一台放在国内的值守手机")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
            } else {
                ForEach(agents) { agent in
                    VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(agent.deviceName).font(.headline)
                            // 每张卡都可点：点选即设为该设备的出号卡（拨号/短信默认走它）。
                            ForEach(agent.simCards) { sim in
                                let isSelected = selectedSimID(for: agent) == sim.subscriptionID
                                Button {
                                    let newID = isSelected ? nil : sim.subscriptionID
                                    SimSelectionStore.select(subscriptionID: newID, for: agent.id)
                                } label: {
                                    HStack(spacing: 6) {
                                        Text(sim.fullLabel)
                                            .font(.subheadline)
                                            .foregroundStyle(.secondary)
                                        if isSelected {
                                            Image(systemName: "checkmark.circle.fill")
                                                .font(.caption)
                                                .foregroundStyle(.green)
                                        }
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                            if agent.simCards.isEmpty {
                                Text("双卡未设置 · 在值守机「双卡设置」里填写号码")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        if agent.isCurrent {
                            Label("当前", systemImage: "checkmark.circle.fill")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.green)
                        }
                    }
                    HStack(spacing: 8) {
                        DeviceLinkBadge(kinds: agent.activeLinkKinds, title: agent.linkTitle)
                        Spacer()
                        Text(agent.agentKindTitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 5)
                .contentShape(Rectangle())
                .onTapGesture { switchTo(agent) }
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button("改名") {
                        renamingDevice = agent
                        renameText = agent.deviceName
                    }.tint(.blue)
                    Button(role: .destructive) {
                        Task { await model.unpairAndroidAgent(id: agent.id) }
                    } label: {
                        Label("解散配对", systemImage: "trash.fill")
                    }
                }
                .contextMenu {
                    if !agent.isCurrent {
                        Button {
                            switchTo(agent)
                        } label: {
                            Label("设为当前设备", systemImage: "checkmark.circle")
                        }
                    }
                    Button(role: .destructive) {
                        Task { await model.unpairAndroidAgent(id: agent.id) }
                    } label: {
                        Label("解散配对", systemImage: "trash")
                    }
                }
            }
            }
            NavigationLink {
                SamsungPairingView()
            } label: {
                Label("添加值守机", systemImage: "plus.circle")
            }
        }
        .onAppear {
            // 老版本单设备配对只写旧槽位，第一次打开时迁移进注册表，保证列表完整。
            DeviceRegistry.archiveActiveDeviceIfNeeded()
        }
        .onReceive(NotificationCenter.default.publisher(for: .simSelectionChanged)) { _ in
            selectionTick += 1
        }
        .alert("重命名设备", isPresented: renamePresented) {
            TextField("设备名称，如 TCL-深圳", text: $renameText)
            Button("取消", role: .cancel) {}
            Button("保存") {
                if let device = renamingDevice {
                    DeviceRegistry.rename(deviceID: device.id, to: renameText)
                    Task { await model.refreshPairedAndroidAgents() }
                }
            }
        }
    }

    private var renamePresented: Binding<Bool> {
        Binding(
            get: { renamingDevice != nil },
            set: { if !$0 { renamingDevice = nil } }
        )
    }

    private func switchTo(_ agent: AndroidAgentPresentation) {
        guard !agent.isCurrent, !switching else { return }
        switching = true
        Task {
            model.selectAndroidAgent(id: agent.id)
            await MainActor.run { switching = false }
        }
    }
}

/// 来电策略管理：黑白名单、静默时段、拒接短信通知、拦截记录。
struct CallPolicyView: View {
    @ObservedObject private var store = CallPolicyStore.shared
    @EnvironmentObject private var model: AppModel
    @State private var newBlockNumber = ""
    @State private var newWhiteNumber = ""
    @State private var showingRuleEditor = false
    /// 添加黑名单号码时的适用 SIM（nil = 全部）
    @State private var blockSimSlot: Int? = nil
    /// 添加白名单号码时的适用 SIM（nil = 全部）
    @State private var whiteSimSlot: Int? = nil

    /// 当前值守设备的 SIM 卡；双卡时才显示按 SIM 选择。
    private var simCards: [SimCard] { model.currentAndroidAgent?.simCards ?? [] }
    private var showSimPicker: Bool { simCards.count > 1 }

    /// SIM 选择器（全部 / SIM 1 / SIM 2）
    @ViewBuilder
    private func simSlotPicker(selection: Binding<Int?>) -> some View {
        Picker("适用 SIM 卡", selection: selection) {
            Text("全部").tag(nil as Int?)
            ForEach(simCards) { sim in
                Text(sim.shortLabel).tag(sim.slotIndex as Int?)
            }
        }
        .pickerStyle(.segmented)
    }

    var body: some View {
        Form {
            Section {
                Picker("模式", selection: $store.policy.mode) {
                    ForEach(CallPolicy.Mode.allCases, id: \.self) { mode in
                        Text(mode.title).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
                Text(modeHint)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: {
                Text("黑白名单")
            }

            Section {
                ForEach(store.policy.blacklist, id: \.self) { number in
                    Text(number).monospacedDigit()
                }
                .onDelete { store.policy.blacklist.remove(atOffsets: $0) }
                // 按 SIM 卡隔离的黑名单
                ForEach(store.policy.blacklistBySlot.keys.sorted(), id: \.self) { slot in
                    if let numbers = store.policy.blacklistBySlot[slot], !numbers.isEmpty {
                        Text("仅 SIM \(slot + 1)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        ForEach(numbers, id: \.self) { number in
                            Text(number).monospacedDigit()
                        }
                        .onDelete { deleteSlotNumbers(at: $0, slot: slot, blacklist: true) }
                    }
                }
                HStack {
                    TextField("输入号码后添加", text: $newBlockNumber)
                        .keyboardType(.phonePad)
                        .monospacedDigit()
                    Button("添加") { addNumber($newBlockNumber, slot: blockSimSlot, blacklist: true) }
                        .disabled(CallPolicyStore.normalizedNumber(newBlockNumber).isEmpty)
                }
                if showSimPicker {
                    simSlotPicker(selection: $blockSimSlot)
                }
            } header: {
                Text("黑名单（\(blacklistTotalCount)）")
            } footer: {
                Text("名单内号码来电将自动拒接。选择 SIM 卡可只对该卡生效。")
                    .font(.caption)
            }

            Section {
                Toggle("包含通讯录全部号码", isOn: $store.policy.whitelistIncludesContacts)
                ForEach(store.policy.whitelist, id: \.self) { number in
                    Text(number).monospacedDigit()
                }
                .onDelete { store.policy.whitelist.remove(atOffsets: $0) }
                // 按 SIM 卡隔离的白名单
                ForEach(store.policy.whitelistBySlot.keys.sorted(), id: \.self) { slot in
                    if let numbers = store.policy.whitelistBySlot[slot], !numbers.isEmpty {
                        Text("仅 SIM \(slot + 1)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        ForEach(numbers, id: \.self) { number in
                            Text(number).monospacedDigit()
                        }
                        .onDelete { deleteSlotNumbers(at: $0, slot: slot, blacklist: false) }
                    }
                }
                HStack {
                    TextField("输入号码后添加", text: $newWhiteNumber)
                        .keyboardType(.phonePad)
                        .monospacedDigit()
                    Button("添加") { addNumber($newWhiteNumber, slot: whiteSimSlot, blacklist: false) }
                        .disabled(CallPolicyStore.normalizedNumber(newWhiteNumber).isEmpty)
                }
                if showSimPicker {
                    simSlotPicker(selection: $whiteSimSlot)
                }
            } header: {
                Text("白名单（\(whitelistTotalCount)）")
            } footer: {
                Text("白名单模式下，非名单号码（含未知号码）一律自动拒接。打开包含通讯录开关后，通讯录里的号码自动放行。选择 SIM 卡可只对该卡生效。")
                    .font(.caption)
            }

            Section {
                if store.policy.quietRules.isEmpty {
                    Text("暂无静默时段")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach($store.policy.quietRules) { $rule in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(rule.timeRangeText)
                                    .font(.headline.monospacedDigit())
                                Text(ruleScopeName(for: rule))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Toggle("", isOn: $rule.enabled)
                                .labelsHidden()
                        }
                    }
                    .onDelete { store.policy.quietRules.remove(atOffsets: $0) }
                }
                Button {
                    showingRuleEditor = true
                } label: {
                    Label("添加静默时段", systemImage: "plus.circle")
                }
            } header: {
                Text("分时段静默")
            } footer: {
                Text("静默时段内来电不响铃、自动挂断，事件照常记录。白名单号码不受静默限制。按设备设置；单卡设备等同按 SIM 卡。")
                    .font(.caption)
            }

            Section {
                Toggle("拒接时发送短信通知", isOn: $store.policy.blockSMSEnabled)
                if store.policy.blockSMSEnabled {
                    TextField("短信内容", text: $store.policy.blockSMSText, axis: .vertical)
                        .lineLimit(2...4)
                }
            } header: {
                Text("拒接通知")
            } footer: {
                Text("拦截来电后自动给对方发一条短信说明（走当前值守机发送）。")
                    .font(.caption)
            }

            Section {
                Toggle("未接来电自动回复短信", isOn: $store.policy.missedReplyEnabled)
                if store.policy.missedReplyEnabled {
                    TextField("回复内容", text: $store.policy.missedReplyText, axis: .vertical)
                        .lineLimit(2...4)
                    Toggle("仅回复通讯录联系人", isOn: $store.policy.missedReplyContactsOnly)
                }
            } header: {
                Text("未接回复")
            } footer: {
                Text("漏接电话后自动回复一条短信。被策略拦截的来电不重复发送；建议仅回复联系人，避免向骚扰号码确认本机活跃。")
                    .font(.caption)
            }

            Section {
                if store.blockedLog.isEmpty {
                    Text("暂无拦截记录")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(store.blockedLog) { event in
                        VStack(alignment: .leading, spacing: 2) {
                            HStack {
                                Text(event.number ?? "未知号码")
                                    .font(.headline.monospacedDigit())
                                Spacer()
                                Text(event.reason)
                                    .font(.caption)
                                    .foregroundStyle(.orange)
                            }
                            HStack {
                                if let device = store.deviceName(for: event.deviceID) {
                                    Text(device)
                                }
                                Text(event.date, style: .relative)
                            }
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        }
                    }
                    Button("清空记录", role: .destructive) {
                        store.clearBlockedLog()
                    }
                }
            } header: {
                Text("拦截记录")
            }
        }
        .navigationTitle("来电策略")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showingRuleEditor) {
            QuietRuleEditor(
                onSave: { rule in
                    store.policy.quietRules.append(rule)
                },
                simCards: simCards
            )
            .presentationDetents([.medium])
        }
    }

    private var modeHint: String {
        switch store.policy.mode {
        case .off: return "策略关闭，所有来电正常响铃。"
        case .blacklist: return "黑名单号码来电自动拒接。"
        case .whitelist: return "仅白名单号码能打进来，其余自动拒接。"
        }
    }

    private func addNumber(_ input: Binding<String>, slot: Int?, blacklist: Bool) {
        let normalized = CallPolicyStore.normalizedNumber(input.wrappedValue)
        guard !normalized.isEmpty else { return }
        if blacklist {
            if let slot {
                var list = store.policy.blacklistBySlot[slot] ?? []
                if !list.contains(normalized) { list.append(normalized) }
                store.policy.blacklistBySlot[slot] = list
            } else {
                if !store.policy.blacklist.contains(normalized) {
                    store.policy.blacklist.append(normalized)
                }
            }
        } else {
            if let slot {
                var list = store.policy.whitelistBySlot[slot] ?? []
                if !list.contains(normalized) { list.append(normalized) }
                store.policy.whitelistBySlot[slot] = list
            } else {
                if !store.policy.whitelist.contains(normalized) {
                    store.policy.whitelist.append(normalized)
                }
            }
        }
        // 加号码即启用对应模式：mode 默认为 .off，不切的话名单加了也拦不住。
        if store.policy.mode == .off {
            store.policy.mode = blacklist ? .blacklist : .whitelist
        }
        input.wrappedValue = ""
    }

    /// 删除按 SIM 隔离名单中的号码。
    private func deleteSlotNumbers(at offsets: IndexSet, slot: Int, blacklist: Bool) {
        if blacklist {
            var list = store.policy.blacklistBySlot[slot] ?? []
            list.remove(atOffsets: offsets)
            if list.isEmpty {
                store.policy.blacklistBySlot.removeValue(forKey: slot)
            } else {
                store.policy.blacklistBySlot[slot] = list
            }
        } else {
            var list = store.policy.whitelistBySlot[slot] ?? []
            list.remove(atOffsets: offsets)
            if list.isEmpty {
                store.policy.whitelistBySlot.removeValue(forKey: slot)
            } else {
                store.policy.whitelistBySlot[slot] = list
            }
        }
    }

    private var blacklistTotalCount: Int {
        store.policy.blacklist.count + store.policy.blacklistBySlot.values.reduce(0) { $0 + $1.count }
    }

    private var whitelistTotalCount: Int {
        store.policy.whitelist.count + store.policy.whitelistBySlot.values.reduce(0) { $0 + $1.count }
    }

    /// 静默规则的作用范围：设备 + SIM 卡。
    private func ruleScopeName(for rule: QuietRule) -> String {
        var parts: [String] = []
        if rule.deviceID.isEmpty {
            parts.append("全部设备")
        } else {
            parts.append(store.deviceName(for: rule.deviceID) ?? "未知设备")
        }
        if let slot = rule.slotIndex {
            parts.append("SIM \(slot + 1)")
        }
        return parts.joined(separator: " · ")
    }
}

/// 新增静默时段：选设备 + SIM 卡 + 起止时间。
private struct QuietRuleEditor: View {
    @Environment(\.dismiss) private var dismiss
    var onSave: (QuietRule) -> Void
    var simCards: [SimCard] = []

    @State private var deviceID = ""
    @State private var slotIndex: Int? = nil
    @State private var start = Calendar.current.date(bySettingHour: 23, minute: 0, second: 0, of: Date()) ?? Date()
    @State private var end = Calendar.current.date(bySettingHour: 7, minute: 0, second: 0, of: Date()) ?? Date()
    private var devices: [PairedDevice] { DeviceRegistry.allDevices() }

    var body: some View {
        NavigationStack {
            Form {
                Picker("设备", selection: $deviceID) {
                    Text("全部设备").tag("")
                    ForEach(devices) { device in
                        Text(device.displayName).tag(device.deviceID)
                    }
                }
                if simCards.count > 1 {
                    Picker("适用 SIM 卡", selection: $slotIndex) {
                        Text("全部").tag(nil as Int?)
                        ForEach(simCards) { sim in
                            Text(sim.shortLabel).tag(sim.slotIndex as Int?)
                        }
                    }
                    .pickerStyle(.segmented)
                }
                DatePicker("开始", selection: $start, displayedComponents: .hourAndMinute)
                DatePicker("结束", selection: $end, displayedComponents: .hourAndMinute)
                Text("开始晚于结束表示跨天，如 23:00–07:00。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .navigationTitle("添加静默时段")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        let cal = Calendar.current
                        onSave(QuietRule(
                            deviceID: deviceID,
                            slotIndex: slotIndex,
                            startMinutes: cal.component(.hour, from: start) * 60 + cal.component(.minute, from: start),
                            endMinutes: cal.component(.hour, from: end) * 60 + cal.component(.minute, from: end)
                        ))
                        dismiss()
                    }
                }
            }
        }
    }
}

// MARK: - 硬件值守模块（大疆 / 移远）预留配置
// 参照 Sheldon 的大疆 4G 模块项目（dontmove-kit / cellbridge-mac / DJiPhone-Kit：
// DJI QDC507 / EG25-G 模块 + AT 指令 + SIP 网关）预留接入入口。
// 当前版本仅保存配置参数，真正的 AT/SIP 桥接将在 Mac / NAS 值守版本接入。

enum HardwareModuleKind: String, CaseIterable, Identifiable {
    case none
    case dji
    case quectel

    var id: String { rawValue }

    var title: String {
        switch self {
        case .none: return "不使用（安卓手机值守）"
        case .dji: return "大疆 DJI 模块"
        case .quectel: return "移远 Quectel 模块"
        }
    }

    var subtitle: String {
        switch self {
        case .none: return "当前默认：用安卓手机做值守机"
        case .dji: return "QDC507 / EG25-G，USB 或网络，AT + SIP"
        case .quectel: return "EG25-G / EG912Y / EC200N，AT + SIP"
        }
    }

    var models: [String] {
        switch self {
        case .none: return []
        case .dji: return ["QDC507", "EG25-G"]
        case .quectel: return ["EG25-G", "EG912Y-IE", "EC200N-CN"]
        }
    }
}

struct HardwareModuleSettingsView: View {
    @AppStorage("hardwareModuleKind") private var kindRaw: String = HardwareModuleKind.none.rawValue
    @AppStorage("hardwareModuleModel") private var moduleModel: String = ""
    @AppStorage("hardwareModuleTransport") private var transport: String = "usb"
    @AppStorage("hardwareModuleHost") private var host: String = ""
    @AppStorage("hardwareModulePort") private var port: String = ""
    @AppStorage("hardwareModuleATPath") private var atPath: String = ""
    @AppStorage("hardwareModuleSIPUser") private var sipUser: String = ""
    @AppStorage("hardwareModuleSIPPassword") private var sipPassword: String = ""
    @AppStorage("hardwareModuleSIPDomain") private var sipDomain: String = ""
    @AppStorage(ModuleBackendKeys.host) private var backendHost: String = ""
    @AppStorage(ModuleBackendKeys.port) private var backendPort: String = "8799"
    @AppStorage(ModuleBackendKeys.sipServer) private var sipServer: String = ""
    @AppStorage(ModuleBackendKeys.sipPort) private var sipPort: String = "5060"
    @AppStorage(ModuleBackendKeys.sipCodec) private var sipCodec: String = "PCMU"
    @AppStorage(ModuleBackendKeys.sipTransport) private var sipTransport: String = "UDP"

    private var kind: HardwareModuleKind {
        HardwareModuleKind(rawValue: kindRaw) ?? .none
    }

    private var isNetwork: Bool { transport == "network" }

    var body: some View {
        Form {
            Section {
                Picker("模块类型", selection: $kindRaw) {
                    ForEach(HardwareModuleKind.allCases) { item in
                        Text(item.title).tag(item.rawValue)
                    }
                }
                if kind != .none {
                    Text(kind.subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("模块类型")
            } footer: {
                Text("选择用哪种 4G 模块做值守后端；留空则继续用安卓手机。")
            }

            if kind != .none {
                Section {
                    Picker("型号", selection: $moduleModel) {
                        ForEach(kind.models, id: \.self) { m in
                            Text(m).tag(m)
                        }
                    }
                    Picker("连接方式", selection: $transport) {
                        Text("USB 串口").tag("usb")
                        Text("网络（TCP）").tag("network")
                    }
                    if isNetwork {
                        TextField("主机地址（如 192.168.1.10）", text: $host)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        TextField("端口（如 9527）", text: $port)
                            .keyboardType(.numberPad)
                    } else {
                        TextField("AT 串口设备（如 /dev/ttyUSB2）", text: $atPath)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                } header: {
                    Text("模块连接")
                } footer: {
                    Text(isNetwork
                        ? "网络方式：模块经 TCP 暴露 AT/SIP，填入 Mac/NAS 上模块的地址与端口。"
                        : "USB 方式：填入模块在 Mac/NAS 上的 AT 串口设备路径。")
                }

                Section {
                    TextField("SIP 用户名", text: $sipUser)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("SIP 密码", text: $sipPassword)
                    TextField("SIP 域名 / 注册地址", text: $sipDomain)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Divider()
                    TextField("SIP 服务器", text: $sipServer)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("SIP 端口", text: $sipPort)
                        .keyboardType(.numberPad)
                    Picker("编码", selection: $sipCodec) {
                        Text("PCMU").tag("PCMU")
                        Text("PCMA").tag("PCMA")
                    }
                    Picker("传输", selection: $sipTransport) {
                        Text("UDP").tag("UDP")
                        Text("TCP").tag("TCP")
                        Text("TLS").tag("TLS")
                    }
                } header: {
                    Text("SIP 账号（与网页控制台一致）")
                } footer: {
                    Text("用于把模块接入 SIP 网关（与 cellbridge-gateway 一致）；留空则用默认。")
                }

                Section {
                    TextField("模块后台主机（如 192.168.1.20）", text: $backendHost)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("模块后台端口（默认 8799）", text: $backendPort)
                        .keyboardType(.numberPad)
                    if kind != .none {
                        NavigationLink {
                            ModuleConsoleView()
                        } label: {
                            Label("打开模块控制台", systemImage: "terminal")
                        }
                    }
                } header: {
                    Text("模块后台（网页控制台同一后端）")
                } footer: {
                    Text("填入运行 module-dashboard 的主机与端口，App 即可像网页一样读取模块实时状态并下发短信 / USSD / VoWiFi / SIP 指令。")
                }

                Section {
                    Label("接入状态：预留配置", systemImage: "hammer")
                    Text("这些参数会保存，供 Mac / NAS 值守版本建立 AT / SIP 桥接时使用。当前正式版仍以安卓手机值守为准，选择模块不会立即切换后端。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } header: {
                    Text("说明")
                }
            }
        }
        .navigationTitle("硬件值守模块")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - 模块控制台：iOS 端镜像网页管理后台（同一 module-dashboard 后端）
// 网页控制台由 server.py 通过 AT 指令驱动大疆/移远模块；iOS 端此处用 URLSession
// 直连同一后端的 /api/*，展示与网页一致的实时状态并下发同类指令，做到两端「匹配」。

enum ModuleBackendKeys {
    static let host = "hardwareModuleBackendHost"
    static let port = "hardwareModuleBackendPort"
    static let sipServer = "hardwareModuleSIPServer"
    static let sipPort = "hardwareModuleSIPPort"
    static let sipCodec = "hardwareModuleSIPCodec"
    static let sipTransport = "hardwareModuleSIPTransport"
}

struct ModuleCell: Codable {
    let earfcn: Int?
    let pci: Int?
    let tac: String?
    let band: String?
    let cellId: String?
    let rsrp: Int?
    let rsrq: Int?
    let sinr: Int?
    let enb: String?
}

struct ModuleGnss: Codable {
    let on: Bool?
    let lat: String?
    let lon: String?
    let alt: Int?
    let speed: Int?
    let sats: Int?
    let hdop: Double?
    let fix: String?
}

struct ModuleNet: Codable {
    let net: String?
    let domain: String?
    let band: String?
    let apn: String?
    let pdp: String?
    let dataOn: Bool?
    let roam: Bool?
    let ip: String?
}

struct ModuleDataStats: Codable {
    let rx: String?
    let tx: String?
    let session: String?
    let lifetime: String?
}

struct ModuleStatus: Codable {
    let online: Bool
    let model: String?
    let imei: String?
    let iccid: String?
    let carrier: String?
    let netType: String?
    let rsrp: Int?
    let rsrq: Int?
    let sinr: Int?
    let bars: Int?
    let fw: String?
    let sipRegistered: Bool?
    let simulated: Bool?
    let imsi: String?
    let temp: String?
    let cell: ModuleCell?
    let gnss: ModuleGnss?
    let net: ModuleNet?
    let data: ModuleDataStats?
}

/// 直连 module-dashboard 后端（server.py）的轻量客户端；与 AirSIMAPI/WiredHTTPTransport
/// 无关——后者绑定安卓 VoWLAN 代理与 USB ECM，此处走普通 Wi-Fi/局域网 HTTP。
struct ModuleDashboardClient {
    let baseURL: URL
    private let session: URLSession

    init(baseURL: URL) {
        self.baseURL = baseURL
        self.session = URLSession(configuration: .ephemeral)
    }

    func status() async throws -> ModuleStatus {
        try await get(ModuleStatus.self, "api/status")
    }

    func sendSMS(to: String, message: String) async throws -> Bool {
        let r = try await post(["to": to, "body": message], "api/sms")
        return r["sent"] as? Bool ?? false
    }

    func sendUSSD(code: String) async throws -> String {
        let r = try await post(["code": code], "api/ussd")
        return r["response"] as? String ?? ""
    }

    func setVoWiFi(on: Bool) async throws -> String {
        let r = try await post(["action": on ? "on" : "off"], "api/vowifi")
        return r["note"] as? String ?? (on ? "已开启 VoWiFi / IMS" : "已关闭 VoWiFi / IMS")
    }

    func registerSIP(_ payload: [String: String]) async throws -> String {
        let r = try await post(payload, "api/sip")
        return (r["status"] as? String) ?? "已提交 SIP 注册"
    }

    func sendAT(_ command: String) async throws -> String {
        let r = try await post(["command": command], "api/at")
        return r["response"] as? String ?? ""
    }

    private func get<T: Decodable>(_ type: T.Type, _ path: String) async throws -> T {
        var req = URLRequest(url: baseURL.appendingPathComponent(path))
        req.timeoutInterval = 8
        let (data, response) = try await session.data(for: req)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw ModuleDashboardError.http(http.statusCode)
        }
        return try JSONDecoder().decode(T.self, from: data)
    }

    private func post(_ body: [String: Any], _ path: String) async throws -> [String: Any] {
        var req = URLRequest(url: baseURL.appendingPathComponent(path))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        req.timeoutInterval = 12
        let (data, _) = try await session.data(for: req)
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }
}

enum ModuleDashboardError: LocalizedError {
    case http(Int)
    case invalidURL
    var errorDescription: String? {
        switch self {
        case let .http(code): return "模块后台返回错误（HTTP \(code)）"
        case .invalidURL: return "模块后台地址无效"
        }
    }
}

/// 与网页控制台「总览」同步的移动端镜像：实时状态 + 短信/USSD/VoWiFi/SIP/AT。
struct ModuleConsoleView: View {
    @AppStorage(ModuleBackendKeys.host) private var backendHost = ""
    @AppStorage(ModuleBackendKeys.port) private var backendPort = "8799"
    @AppStorage(ModuleBackendKeys.sipServer) private var sipServer = ""
    @AppStorage(ModuleBackendKeys.sipPort) private var sipPort = "5060"
    @AppStorage(ModuleBackendKeys.sipCodec) private var sipCodec = "PCMU"
    @AppStorage(ModuleBackendKeys.sipTransport) private var sipTransport = "UDP"
    @AppStorage("hardwareModuleSIPUser") private var sipUser = ""
    @AppStorage("hardwareModuleSIPPassword") private var sipPassword = ""
    @AppStorage("hardwareModuleSIPDomain") private var sipDomain = ""

    @State private var status: ModuleStatus?
    @State private var loading = false
    @State private var errorText: String?
    @State private var toast: String?
    @State private var smsTo = ""
    @State private var smsBody = ""
    @State private var ussdCode = ""
    @State private var atCommand = ""
    @State private var atResult = ""

    private var client: ModuleDashboardClient? {
        let host = backendHost.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !host.isEmpty,
              let url = URL(string: "http://\(host):\(backendPort.isEmpty ? "8799" : backendPort)") else {
            return nil
        }
        return ModuleDashboardClient(baseURL: url)
    }

    var body: some View {
        Form {
            if client == nil {
                Section {
                    Text("尚未配置模块后台地址。请返回「硬件值守模块」填写「模块后台主机」与「端口」（与网页控制台同一个地址）。")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                } header: { Text("提示") }
            } else {
                overviewSection
                communicationSection
                networkSection
                atSection
            }
        }
        .navigationTitle("模块控制台")
        .navigationBarTitleDisplayMode(.inline)
        .task { await refresh() }
        .refreshable { await refresh() }
        .overlay(alignment: .bottom) {
            if let toast { toastView(toast) }
        }
    }

    private var overviewSection: some View {
        Section {
            if loading && status == nil {
                ProgressView("正在读取模块状态…")
            } else if let s = status {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Image(systemName: s.online ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                            .foregroundStyle(s.online ? .green : .secondary)
                        Text(s.online ? "模块在线" : "模块离线")
                            .font(.headline)
                        Spacer()
                        if let bars = s.bars { SignalBarsView(bars: bars) }
                    }
                    keyValue("型号", s.model)
                    keyValue("运营商", s.carrier)
                    keyValue("网络", s.netType)
                    keyValue("RSRP", s.rsrp.map { "\($0) dBm" })
                    keyValue("RSRQ", s.rsrq.map { "\($0) dB" })
                    keyValue("SINR", s.sinr.map { "\($0) dB" })
                    keyValue("IMEI", s.imei)
                    keyValue("ICCID", s.iccid)
                    keyValue("IMSI", s.imsi)
                    keyValue("固件", s.fw)
                    keyValue("温度", s.temp)
                    if let net = s.net {
                        keyValue("APN", net.apn)
                        keyValue("IP", net.ip)
                        keyValue("数据开关", net.dataOn.map { $0 ? "开" : "关" })
                        keyValue("漫游", net.roam.map { $0 ? "开" : "关" })
                    }
                    if let g = s.gnss, g.on == true {
                        keyValue("定位", "\(g.lat ?? "-"), \(g.lon ?? "-")（\(g.fix ?? "-") · \(g.sats ?? 0) 星）")
                    }
                    if let d = s.data {
                        keyValue("本次流量", d.session)
                        keyValue("累计流量", d.lifetime)
                    }
                    if let cell = s.cell {
                        keyValue("小区", "EARFCN \(cell.earfcn ?? 0) · PCI \(cell.pci ?? 0) · \(cell.band ?? "-")")
                        keyValue("TAC/CellID", "\(cell.tac ?? "-") / \(cell.cellId ?? "-")")
                    }
                    if let sim = s.sipRegistered {
                        Label(sim ? "SIP 已注册" : "SIP 未注册", systemImage: sim ? "checkmark.shield" : "shield")
                            .font(.subheadline)
                            .foregroundStyle(sim ? .green : .orange)
                    }
                }
                .phoneCard()
            } else if let errorText {
                Text(errorText).foregroundStyle(.red)
            }
            Button {
                Task { await refresh() }
            } label: { Label("刷新状态", systemImage: "arrow.clockwise") }
        } header: { Text("总览（与网页控制台同步）") }
    }

    private var communicationSection: some View {
        Section {
            NavigationLink {
                ModuleSMSView(client: client!)
            } label: { Label("发送短信", systemImage: "message") }
            NavigationLink {
                ModuleUSSDView(client: client!)
            } label: { Label("查询 USSD", systemImage: "number") }
            NavigationLink {
                ModuleVoWiFiView(client: client!)
            } label: { Label("VoWiFi / IMS", systemImage: "wifi.exclamationmark") }
            Button {
                Task { await registerSIP() }
            } label: { Label("注册 SIP 网关", systemImage: "phone.bubble.left") }
        } header: { Text("通信") }
    }

    private var networkSection: some View {
        Section {
            NavigationLink {
                ModuleSIPConfigView()
            } label: { Label("SIP 网关配置", systemImage: "server.rack") }
        } header: { Text("网络") }
    }

    private var atSection: some View {
        Section {
            TextField("AT 指令，如 AT+CSQ", text: $atCommand)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
            Button("发送 AT") { Task { await sendAT() } }
                .disabled(atCommand.isEmpty)
            if !atResult.isEmpty {
                Text(atResult)
                    .font(.system(.footnote, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 10))
            }
        } header: { Text("AT 调试") }
    }

    private func keyValue(_ k: String, _ v: String?) -> some View {
        HStack(alignment: .top) {
            Text(k).foregroundStyle(.secondary)
            Spacer()
            Text(v ?? "—").multilineTextAlignment(.trailing)
        }
        .font(.subheadline)
    }

    private func toastView(_ message: String) -> some View {
        Text(message)
            .font(.caption.weight(.medium))
            .padding(.horizontal, 14).padding(.vertical, 9)
            .background(.thinMaterial, in: Capsule())
            .padding(.bottom, 12)
    }

    private func refresh() async {
        guard let client else { return }
        loading = true; errorText = nil
        do { status = try await client.status() }
        catch { errorText = (error as? ModuleDashboardError)?.errorDescription ?? error.localizedDescription }
        loading = false
    }

    private func registerSIP() async {
        guard let client else { return }
        do {
            let payload = [
                "server": sipServer, "port": sipPort, "user": sipUser,
                "codec": sipCodec, "transport": sipTransport
            ].filter { !$0.value.isEmpty }
            let msg = try await client.registerSIP(payload)
            toast = msg
            await refresh()
        } catch {
            toast = (error as? ModuleDashboardError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func sendAT() async {
        guard let client else { return }
        do { atResult = try await client.sendAT(atCommand) }
        catch { atResult = (error as? ModuleDashboardError)?.errorDescription ?? error.localizedDescription }
    }
}

private struct SignalBarsView: View {
    let bars: Int
    var body: some View {
        HStack(spacing: 2) {
            ForEach(0..<5, id: \.self) { i in
                RoundedRectangle(cornerRadius: 1)
                    .fill(i < bars ? Color.green : Color.secondary.opacity(0.3))
                    .frame(width: 3, height: 4 + CGFloat(i) * 3)
            }
        }
    }
}

struct ModuleSMSView: View {
    let client: ModuleDashboardClient
    @State private var to = ""
    @State private var message = ""
    @State private var result: String?
    @State private var sending = false

    var body: some View {
        Form {
            Section {
                TextField("收件人号码", text: $to).keyboardType(.phonePad).monospacedDigit()
                TextField("短信内容", text: $message, axis: .vertical).lineLimit(3...8)
                Button { Task { await send() } } label: { Label("发送", systemImage: "paperplane") }
                    .disabled(to.isEmpty || message.isEmpty || sending)
                if let result { Text(result).foregroundStyle(.secondary) }
            } header: { Text("发送短信（经模块）") }
        }
        .navigationTitle("模块短信").navigationBarTitleDisplayMode(.inline)
    }

    private func send() async {
        sending = true
        do { let ok = try await client.sendSMS(to: to, message: message); result = ok ? "已发送" : "发送失败" }
        catch { result = (error as? ModuleDashboardError)?.errorDescription ?? error.localizedDescription }
        sending = false
    }
}

struct ModuleUSSDView: View {
    let client: ModuleDashboardClient
    @State private var code = ""
    @State private var result: String?
    @State private var querying = false

    var body: some View {
        Form {
            Section {
                TextField("USSD 代码，如 *101#", text: $code)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .monospacedDigit()
                Button { Task { await query() } } label: { Label("查询", systemImage: "magnifyingglass") }
                    .disabled(code.isEmpty || querying)
                if let result { Text(result).foregroundStyle(.secondary) }
            } header: { Text("查询 USSD（经模块）") }
        }
        .navigationTitle("模块 USSD").navigationBarTitleDisplayMode(.inline)
    }

    private func query() async {
        querying = true
        do { result = try await client.sendUSSD(code: code) }
        catch { result = (error as? ModuleDashboardError)?.errorDescription ?? error.localizedDescription }
        querying = false
    }
}

struct ModuleVoWiFiView: View {
    let client: ModuleDashboardClient
    @State private var on = false
    @State private var note: String?
    @State private var busy = false

    var body: some View {
        Form {
            Section {
                Toggle("启用 VoWiFi / IMS", isOn: $on)
                Button { Task { await apply() } } label: { Label("应用并重新注册", systemImage: "arrow.triangle.2.circlepath") }
                    .disabled(busy)
                if let note { Text(note).font(.footnote).foregroundStyle(.secondary) }
            } header: { Text("VoWiFi / IMS") } footer: {
                Text("发送 AT+QCFG=\"ims\",1 并触发 CFUN 重注册；ePDG / Realm 在 SIP 网关配置中填写。")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("VoWiFi / IMS").navigationBarTitleDisplayMode(.inline)
    }

    private func apply() async {
        busy = true
        do { note = try await client.setVoWiFi(on: on) }
        catch { note = (error as? ModuleDashboardError)?.errorDescription ?? error.localizedDescription }
        busy = false
    }
}

struct ModuleSIPConfigView: View {
    @AppStorage(ModuleBackendKeys.sipServer) private var server = ""
    @AppStorage(ModuleBackendKeys.sipPort) private var port = "5060"
    @AppStorage(ModuleBackendKeys.sipCodec) private var codec = "PCMU"
    @AppStorage(ModuleBackendKeys.sipTransport) private var transport = "UDP"
    @AppStorage("hardwareModuleSIPUser") private var user = ""
    @AppStorage("hardwareModuleSIPPassword") private var password = ""
    @AppStorage("hardwareModuleSIPDomain") private var domain = ""

    var body: some View {
        Form {
            Section {
                TextField("SIP 服务器", text: $server)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("端口", text: $port).keyboardType(.numberPad)
                TextField("用户名", text: $user)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("密码", text: $password)
                TextField("域名 / 注册地址", text: $domain)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
            } header: { Text("账号") }
            Section {
                Picker("编码", selection: $codec) {
                    Text("PCMU").tag("PCMU")
                    Text("PCMA").tag("PCMA")
                }
                Picker("传输", selection: $transport) {
                    Text("UDP").tag("UDP")
                    Text("TCP").tag("TCP")
                    Text("TLS").tag("TLS")
                }
            } header: { Text("参数（与网页控制台一致）") }
        }
        .navigationTitle("SIP 网关配置").navigationBarTitleDisplayMode(.inline)
    }
}
