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
                    Text("uPhone 通过同一 Wi-Fi 或安卓热点建立 VoWLAN；离开局域网时可使用独立云端 Relay。")
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
            .navigationTitle("uPhone")
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
                    Toggle(isOn: $localModeEnabled) {
                        Label("仅本地模式", systemImage: "wifi")
                    }
                    .onChange(of: localModeEnabled) { enabled in
                        selfTestTask?.cancel()
                        selfTestPhase = .idle
                        selfTestReport = nil
                        if enabled { cloudModeEnabled = false }
                        model.setLocalModeEnabled(enabled)
                    }
                } header: {
                    Text("运行模式")
                } footer: {
                    Text(localModeEnabled
                        ? "仅使用同一 Wi-Fi 或手机热点下的 VoWLAN。不会注册 Relay、APNs、PushKit 或 CallKit。"
                        : "关闭后可按需启用云端 Relay、PushKit 和 CallKit。")
                }

                Section("当前连接") {
                    LabeledContent("当前模式", value: model.connectionModePresentation.title)
                    LabeledContent("连接状态", value: model.connectionModePresentation.status)
                    LabeledContent("VoWLAN", value: VoWLANStatusCopy.text(for: model.vowlan.availability))
                    LabeledContent(
                        "云端 Relay",
                        value: localModeEnabled
                            ? "本地模式已关闭"
                            : (!cloudModeEnabled
                            ? "已关闭"
                            : (model.cloudAgentStatus?.cloudOnline == true ? "Agent 在线" : "不可达"))
                    )
                    Text(model.connectionModePresentation.detail)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    if let connectionMessage = model.connectionMessage, !connectionMessage.isEmpty {
                        Text(connectionMessage)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                Section("安卓连接") {
                    LabeledContent("VoWLAN", value: VoWLANStatusCopy.text(for: model.vowlan.availability))
                    NavigationLink {
                        SamsungPairingView()
                    } label: {
                        Label("值守配对", systemImage: "personalhotspot")
                    }
                    Button("重新发现", systemImage: "arrow.clockwise") {
                        model.vowlan.restartBrowsing()
                        Task { await model.vowlan.probeNow() }
                    }
                }

                PairedDevicesSection()

                Section {
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

                    TextField("https://relay.example.com", text: $relayURL)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(localModeEnabled)
                    Button("保存 Relay 地址") {
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
                } header: {
                    Text("云端 Relay")
                } footer: {
                    Text("云端关闭后，局域网内的安卓 VoWLAN 仍可使用；公网拨打、接听和短信需要云端 Relay 与有效设备注册。")
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

                Section("关于") {
                    LabeledContent("项目", value: "uPhone · 安卓")
                    Picker("外观", selection: $settings.appearance) {
                        ForEach(AppAppearance.allCases) { appearance in
                            Text(appearance.title).tag(appearance)
                        }
                    }
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
struct PairedDevicesSection: View {
    @EnvironmentObject private var model: AppModel
    @State private var devices: [PairedDevice] = []
    @State private var activeID: String?
    @State private var renamingDevice: PairedDevice?
    @State private var renameText = ""
    @State private var switching = false

    var body: some View {
        Section {
            ForEach(devices) { device in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(device.displayName).font(.headline)
                        Text("…\(device.deviceID.suffix(8))")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if device.deviceID == activeID {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture { switchTo(device) }
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button("改名") {
                        renamingDevice = device
                        renameText = device.deviceName
                    }.tint(.blue)
                    Button("删除", role: .destructive) { remove(device) }
                }
            }
            NavigationLink {
                SamsungPairingView()
            } label: {
                Label("配对新设备", systemImage: "plus.circle")
            }
        } header: {
            Text("值守设备")
        } footer: {
            Text("点选切换当前使用的值守机；多台设备的来电会标注来源。")
        }
        .onAppear(perform: refresh)
        .alert("重命名设备", isPresented: renamePresented) {
            TextField("设备名称，如 TCL-深圳", text: $renameText)
            Button("取消", role: .cancel) {}
            Button("保存") {
                if let device = renamingDevice {
                    DeviceRegistry.rename(deviceID: device.deviceID, to: renameText)
                    refresh()
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

    private func refresh() {
        DeviceRegistry.archiveActiveDeviceIfNeeded()
        devices = DeviceRegistry.allDevices()
        activeID = DeviceRegistry.activeDeviceID()
    }

    private func switchTo(_ device: PairedDevice) {
        guard device.deviceID != activeID, !switching else { return }
        switching = true
        Task {
            let ok = DeviceRegistry.switchTo(deviceID: device.deviceID)
            if ok {
                await VoIPPushController.shared.forceRegistrationSync()
                await model.refreshCloudStatusForDiagnostics()
            }
            await MainActor.run {
                refresh()
                switching = false
            }
        }
    }

    private func remove(_ device: PairedDevice) {
        DeviceRegistry.remove(deviceID: device.deviceID)
        refresh()
    }
}

/// 来电策略管理：黑白名单、静默时段、拒接短信通知、拦截记录。
struct CallPolicyView: View {
    @ObservedObject private var store = CallPolicyStore.shared
    @State private var newBlockNumber = ""
    @State private var newWhiteNumber = ""
    @State private var showingRuleEditor = false

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
                HStack {
                    TextField("输入号码后添加", text: $newBlockNumber)
                        .keyboardType(.phonePad)
                        .monospacedDigit()
                    Button("添加") { addNumber($newBlockNumber, to: \.blacklist) }
                        .disabled(CallPolicyStore.normalizedNumber(newBlockNumber).isEmpty)
                }
            } header: {
                Text("黑名单（\(store.policy.blacklist.count)）")
            } footer: {
                Text("名单内号码来电将自动拒接。")
                    .font(.caption)
            }

            Section {
                Toggle("包含通讯录全部号码", isOn: $store.policy.whitelistIncludesContacts)
                ForEach(store.policy.whitelist, id: \.self) { number in
                    Text(number).monospacedDigit()
                }
                .onDelete { store.policy.whitelist.remove(atOffsets: $0) }
                HStack {
                    TextField("输入号码后添加", text: $newWhiteNumber)
                        .keyboardType(.phonePad)
                        .monospacedDigit()
                    Button("添加") { addNumber($newWhiteNumber, to: \.whitelist) }
                        .disabled(CallPolicyStore.normalizedNumber(newWhiteNumber).isEmpty)
                }
            } header: {
                Text("白名单（\(store.policy.whitelist.count)）")
            } footer: {
                Text("白名单模式下，非名单号码（含未知号码）一律自动拒接。打开包含通讯录开关后，通讯录里的号码自动放行。")
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
                                Text(ruleDeviceName(for: rule))
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
            QuietRuleEditor { rule in
                store.policy.quietRules.append(rule)
            }
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

    private func addNumber(_ input: Binding<String>, to keyPath: WritableKeyPath<CallPolicy, [String]>) {
        let normalized = CallPolicyStore.normalizedNumber(input.wrappedValue)
        guard !normalized.isEmpty else { return }
        if !store.policy[keyPath: keyPath].contains(normalized) {
            store.policy[keyPath: keyPath].append(normalized)
        }
        input.wrappedValue = ""
    }

    private func ruleDeviceName(for rule: QuietRule) -> String {
        if rule.deviceID.isEmpty { return "全部设备" }
        return store.deviceName(for: rule.deviceID) ?? "未知设备"
    }
}

/// 新增静默时段：选设备 + 起止时间。
private struct QuietRuleEditor: View {
    @Environment(\.dismiss) private var dismiss
    var onSave: (QuietRule) -> Void

    @State private var deviceID = ""
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
