import SwiftUI

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
                } header: {
                    Text("云端模式自检")
                } footer: {
                    Text(localModeEnabled
                        ? "本地模式不运行云端自检。"
                        : "依次验证本机 Push 凭据、uPhone Relay 身份、设备注册和 Agent 最近 90 秒心跳；不会拨号或发送短信。免费侧载无 PushKit 能力时可打开上方跳过开关。")
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
/// 单条线路徽章：Wi-Fi 直连 / 云中继 / 离线。
private struct DeviceLinkBadge: View {
    let kind: DeviceLinkKind
    let title: String

    private var color: Color {
        switch kind {
        case .wifiDirect: return .blue
        case .cloud: return .green
        case .offline: return .secondary
        }
    }

    var body: some View {
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 6, height: 6)
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
                                Text(agent.phoneNumber ?? "号码未提供")
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
                        DeviceLinkBadge(kind: agent.linkKind, title: agent.linkTitle)
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
                        model.unpairAndroidAgent(id: agent.id)
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
                        model.unpairAndroidAgent(id: agent.id)
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
