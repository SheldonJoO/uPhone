import Foundation

// MARK: - 通话与短信

/// 模块侧返回的单次通话记录。
struct CallRecord: Codable, Equatable, Sendable, Identifiable {
    let id: String
    let index: Int
    let direction: String
    let state: String
    let number: String?
    let startedAt: Date
    let updatedAt: Date
    let endedAt: Date?
    let missed: Bool
    /// Android 上报的订阅 ID（-1=未知），双卡拨号路由用。
    let subscriptionID: Int? = nil
    /// Android 上报的卡槽（0/1，-1=未知），双卡展示与策略隔离用。
    let slotIndex: Int? = nil
    /// Android 上报的接通时间（ISO8601，可缺省）；缺省时回退到 startedAt。
    let connectedAt: Date? = nil

    enum CodingKeys: String, CodingKey {
        case id, index, direction, state, number, missed
        case startedAt = "started_at"
        case updatedAt = "updated_at"
        case endedAt = "ended_at"
        case subscriptionID = "subscription_id"
        case slotIndex = "slot_index"
        case connectedAt = "connected_at"
    }

    /// 双卡短标签，如 "SIM 1"；无卡槽信息时返回 nil。
    var simLabel: String? {
        guard let slotIndex, slotIndex >= 0 else { return nil }
        return "SIM \(slotIndex + 1)"
    }

    /// 真实通话时长：以接通时间为准（connectedAt ?? startedAt → endedAt ?? 现在）。
    /// 从未接通的未接来电返回 nil。
    var callDuration: TimeInterval? {
        if connectedAt == nil, missed { return nil }
        let start = connectedAt ?? startedAt
        let end = endedAt ?? Date()
        return max(0, end.timeIntervalSince(start))
    }
}

/// 通话轮询结果；额外音频诊断字段由专门接口读取。
struct CallStatus: Codable, Sendable {
    let active: CallRecord?
    let history: [CallRecord]?
    let polling: Bool
    let eventDriven: Bool?
    let pollIntervalSeconds: Int
    let lastPollError: String
    let revision: UInt64?

    enum CodingKeys: String, CodingKey {
        case active, history, polling
        case eventDriven = "event_driven"
        case pollIntervalSeconds = "poll_interval_s"
        case lastPollError = "last_poll_error"
        case revision
    }
}

/// Agent 的统一事件快照；通话和短信共用一个长轮询，避免后台保持两套 USB 请求。
struct AgentEventStatus: Codable, Sendable {
    let revision: UInt64
    let smsRevision: UInt64
    let smsPending: Int
    let call: CallStatus

    enum CodingKeys: String, CodingKey {
        case revision, call
        case smsRevision = "sms_revision"
        case smsPending = "sms_pending"
    }
}

struct AgentHealth: Codable, Sendable {
    let ok: Bool
    let version: String
    let controlPlane: String?
    let cellularState: String?
    let cellularRegistration: String?
    let cellularRecovery: String?

    enum CodingKeys: String, CodingKey {
        case ok, version
        case controlPlane = "control_plane"
        case cellularState = "cellular_state"
        case cellularRegistration = "cellular_registration"
        case cellularRecovery = "cellular_recovery"
    }
}

/// Relay 最近一次收到的 Agent 心跳。该对象只包含可展示状态，不包含设备密钥。
struct CloudAndroidAgent: Codable, Equatable, Sendable, Identifiable {
    let agentID: String
    let agentKind: String?
    let deviceName: String?
    let manufacturer: String?
    let model: String?
    let phoneNumber: String?
    let agentVersion: String?
    let cloudOnline: Bool
    let simCards: [SimCard]

    var id: String { agentID }

    enum CodingKeys: String, CodingKey {
        case agentID = "agent_id"
        case agentKind = "agent_kind"
        case deviceName = "device_name"
        case manufacturer, model
        case phoneNumber = "phone_number"
        case agentVersion = "agent_version"
        case cloudOnline = "cloud_online"
        case simCards = "sim_cards"
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        agentID = try values.decode(String.self, forKey: .agentID)
        agentKind = try values.decodeIfPresent(String.self, forKey: .agentKind)
        deviceName = try values.decodeIfPresent(String.self, forKey: .deviceName)
        manufacturer = try values.decodeIfPresent(String.self, forKey: .manufacturer)
        model = try values.decodeIfPresent(String.self, forKey: .model)
        phoneNumber = try values.decodeIfPresent(String.self, forKey: .phoneNumber)
        agentVersion = try values.decodeIfPresent(String.self, forKey: .agentVersion)
        cloudOnline = try values.decodeIfPresent(Bool.self, forKey: .cloudOnline) ?? false
        simCards = try values.decodeIfPresent([SimCard].self, forKey: .simCards) ?? []
    }
}

struct CloudAgentStatus: Decodable, Equatable, Sendable {
    let cloudOnline: Bool
    let agentVersion: String?
    let atOK: Bool?
    let cellularState: String?
    let cellularRegistration: String?
    let cellularRecovery: String?
    let ecmCarrier: String?
    let signalDBM: Int?
    let activeAgentID: String?
    let agents: [CloudAndroidAgent]

    enum CodingKeys: String, CodingKey {
        case cloudOnline = "cloud_online"
        case agentVersion = "agent_version"
        case atOK = "at_ok"
        case cellularState = "cellular_state"
        case cellularRegistration = "cellular_registration"
        case cellularRecovery = "cellular_recovery"
        case ecmCarrier = "ecm_carrier"
        case signalDBM = "signal_dbm"
        case activeAgentID = "active_agent_id"
        case agents
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        cloudOnline = try values.decode(Bool.self, forKey: .cloudOnline)
        agentVersion = try values.decodeIfPresent(String.self, forKey: .agentVersion)
        atOK = try values.decodeIfPresent(Bool.self, forKey: .atOK)
        cellularState = try values.decodeIfPresent(String.self, forKey: .cellularState)
        cellularRegistration = try values.decodeIfPresent(String.self, forKey: .cellularRegistration)
        cellularRecovery = try values.decodeIfPresent(String.self, forKey: .cellularRecovery)
        ecmCarrier = try values.decodeIfPresent(String.self, forKey: .ecmCarrier)
        signalDBM = try values.decodeIfPresent(Int.self, forKey: .signalDBM)
        activeAgentID = try values.decodeIfPresent(String.self, forKey: .activeAgentID)
        agents = try values.decodeIfPresent([CloudAndroidAgent].self, forKey: .agents) ?? []
    }
}

struct SimCard: Equatable, Identifiable, Sendable, Codable {
    let subscriptionID: Int
    let slotIndex: Int
    let carrierName: String
    let displayName: String
    let number: String

    var id: Int { subscriptionID }

    /// SIM 1 / SIM 2 这样的短标签。
    var shortLabel: String {
        if slotIndex >= 0 { return "SIM \(slotIndex + 1)" }
        return "SIM"
    }

    /// 选择器里显示的完整标签：SIM 1 · 中国移动 · 138****0000
    var fullLabel: String {
        var parts = [shortLabel]
        let carrier = carrierName.isEmpty ? displayName : carrierName
        if !carrier.isEmpty { parts.append(carrier) }
        if !number.isEmpty { parts.append(maskedNumber) }
        return parts.joined(separator: " · ")
    }

    private var maskedNumber: String {
        guard number.count > 7 else { return number }
        let prefix = number.prefix(3)
        let suffix = number.suffix(4)
        return "\(prefix)****\(suffix)"
    }

    enum CodingKeys: String, CodingKey {
        case subscriptionID = "subscription_id"
        case slotIndex = "slot_index"
        case carrierName = "carrier_name"
        case displayName = "display_name"
        case number
    }
}

/// 双卡选择的持久化：记住用户上次选的 SIM。
enum SimSelectionStore {
    private static let key = "uphone.selected-sim-subscription-id"

    static func selectedSubscriptionID() -> Int? {
        let value = UserDefaults.standard.integer(forKey: key)
        // integer(forKey:) 在缺省时返回 0；用 object(forKey:) 区分"未设置"。
        // subscription_id 允许为 0（手动补卡时 SIM 1 用槽位 0 做占位）。
        guard UserDefaults.standard.object(forKey: key) != nil, value >= 0 else { return nil }
        return value
    }

    static func select(subscriptionID: Int?) {
        if let subscriptionID {
            UserDefaults.standard.set(subscriptionID, forKey: key)
        } else {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }
}

/// 值守机的统一连接方式：本地和云端不再是两种"设备"，而是一台设备的两种"线路"。
/// 同一 Wi-Fi 下优先 Wi-Fi 直连，不在身边时经云中继兜底。
enum DeviceLinkKind {
    case wifiDirect
    case cloud
    case offline
}

struct AndroidAgentPresentation: Equatable, Identifiable, Sendable {
    let id: String
    let deviceName: String
    let phoneNumber: String?
    let agentKind: String?
    let cloudOnline: Bool
    let vowlanOnline: Bool
    let isCurrent: Bool
    let simCards: [SimCard]

    init(
        id: String,
        deviceName: String,
        phoneNumber: String?,
        agentKind: String?,
        cloudOnline: Bool,
        vowlanOnline: Bool,
        isCurrent: Bool,
        simCards: [SimCard] = []
    ) {
        self.id = id
        self.deviceName = deviceName
        self.phoneNumber = phoneNumber
        self.agentKind = agentKind
        self.cloudOnline = cloudOnline
        self.vowlanOnline = vowlanOnline
        self.isCurrent = isCurrent
        self.simCards = simCards
    }

    var agentKindTitle: String {
        switch agentKind {
        case "standalone": return "Standalone"
        case "avf": return "AVF Agent"
        default: return "Android Agent"
        }
    }

    /// 当前生效的线路：Wi-Fi 直连优先，云中继兜底，都不通即离线。
    var linkKind: DeviceLinkKind {
        if vowlanOnline { return .wifiDirect }
        if cloudOnline { return .cloud }
        return .offline
    }

    var linkTitle: String {
        switch linkKind {
        case .wifiDirect: return "Wi-Fi 直连"
        case .cloud: return "云中继"
        case .offline: return "离线"
        }
    }

    /// SIM 卡小结："SIM 1 · 中国移动 138****0000" / 单卡显示号码 / 无卡显示占位。
    var simSummaryLines: [String] {
        if simCards.count > 1 { return simCards.map(\.fullLabel) }
        if let first = simCards.first { return [first.fullLabel] }
        return [phoneNumber ?? "号码未提供"]
    }
}

enum ModuleConnectionSummary: Equatable, Sendable {
    case localOnline
    case localOnlineNoService
    case cloudOnly
    case offline
}

enum ModuleConnectionPolicy {
    static func summary(
        localControlReachable: Bool,
        cloudHeartbeatFresh: Bool,
        cellularState: String?
    ) -> ModuleConnectionSummary {
        if localControlReachable {
            return ["searching", "denied", "unregistered"].contains(cellularState ?? "")
                ? .localOnlineNoService : .localOnline
        }
        return cloudHeartbeatFresh ? .cloudOnly : .offline
    }
}

enum SMSDirection: String, Codable, Equatable, Sendable {
    case incoming
    case outgoing
}

struct SMSMessage: Codable, Equatable, Sendable, Identifiable {
    let sender: String
    let content: String
    let code: String?
    let timestamp: Date
    /// 模块交付队列的稳定标识；也用于推送与本地同步之间的去重。
    var deliveryID: String?
    /// 旧模块没有方向字段，缺省按收到的短信展示；发送成功后由 App 标记为 outgoing。
    var direction: SMSDirection? = nil
    /// 收到短信的 SIM（双卡场景）；发送时为 nil。
    var subscriptionID: Int? = nil
    var slotIndex: Int? = nil
    /// 服务端短信分类：pickup（取件码）/ code（验证码）/ other；旧数据为 nil。
    var category: String? = nil
    /// relay 解析的接收号码后四位（多卡场景标注用）；无则为 nil。
    var toTail: String? = nil

    var isOutgoing: Bool { direction == .outgoing }

    /// 收到短信时的 SIM 短标签，如 "SIM 1"；无信息时返回 nil。
    var simLabel: String? {
        guard !isOutgoing, let slotIndex, slotIndex >= 0 else { return nil }
        return "SIM \(slotIndex + 1)"
    }

    /// 短信验证码：优先用服务端提取的 code 字段，否则从正文正则提取 4-8 位数字。
    var verificationCode: String? {
        if let code, code.range(of: "^[0-9]{4,8}$", options: .regularExpression) != nil {
            return code
        }
        // 常见验证码句式：验证码 123456、code is 123456、【xxx】123456
        let patterns = [
            "验证码[^0-9]{0,6}([0-9]{4,8})",
            "verification code[^0-9]{0,6}([0-9]{4,8})",
            "code[^0-9]{0,6}([0-9]{4,8})",
            "【[^】]{1,12}】([0-9]{4,8})",
        ]
        for pattern in patterns {
            if let range = content.range(of: pattern, options: [.regularExpression, .caseInsensitive]) {
                let matched = String(content[range])
                if let digits = matched.range(of: "[0-9]{4,8}$", options: .regularExpression) {
                    return String(matched[digits])
                }
            }
        }
        return nil
    }

    /// 是否为快递取件码短信：优先用服务端分类，旧数据用本地规则兜底。
    var isPickup: Bool {
        guard !isOutgoing else { return false }
        if category == "pickup" { return true }
        if category != nil { return false }
        let senderHit = ["丰巢", "菜鸟", "京东", "顺丰", "邮政"].contains { sender.contains($0) }
        return senderHit || content.contains("取件码") || content.contains("提货码")
    }

    /// 取件码：先按"取件码/提货码"句式提取，否则用服务端 code 或验证码兜底。
    var pickupCode: String? {
        let patterns = [
            "取件码[^0-9A-Za-z]{0,8}([0-9A-Za-z][0-9A-Za-z\\-]{3,15})",
            "提货码[^0-9A-Za-z]{0,8}([0-9A-Za-z][0-9A-Za-z\\-]{3,15})",
        ]
        for pattern in patterns {
            if let range = content.range(of: pattern, options: .regularExpression) {
                let matched = String(content[range])
                if let token = matched.range(of: "[0-9A-Za-z\\-]+$", options: .regularExpression) {
                    return String(matched[token])
                }
            }
        }
        if let code, !code.isEmpty { return code }
        return verificationCode
    }

    /// 新版 Agent 用 delivery_id 对齐 APNs 与本地同步；旧版仍回退到内容指纹。
    var id: String {
        if let deliveryID, !deliveryID.isEmpty { return "delivery:\(deliveryID)" }
        return "\(sender)\u{0}\(timestamp.timeIntervalSince1970)\u{0}\(content)\u{0}\(direction?.rawValue ?? SMSDirection.incoming.rawValue)"
    }

    enum CodingKeys: String, CodingKey {
        case sender, content, code, timestamp, direction
        case deliveryID = "delivery_id"
        case category
        case subscriptionID = "subscription_id"
        case slotIndex = "slot_index"
        case toTail = "to_tail"
    }
}

enum SMSHistoryPolicy {
    static func appendingSentMessage(
        recipient: String,
        content: String,
        at timestamp: Date,
        to messages: [SMSMessage],
        limit: Int
    ) -> [SMSMessage] {
        let sentMessage = SMSMessage(
            sender: recipient,
            content: content,
            code: nil,
            timestamp: timestamp,
            deliveryID: nil,
            direction: .outgoing
        )
        return Array((messages + [sentMessage])
            .sorted { $0.timestamp > $1.timestamp }
            .prefix(max(0, limit)))
    }
}

struct SMSStatus: Codable, Sendable {
    let autoCleanupME: Bool
    let count: Int?
    let lastPollError: String?

    enum CodingKeys: String, CodingKey {
        case count
        case autoCleanupME = "auto_cleanup_me"
        case lastPollError = "last_poll_error"
    }
}

struct RejectResponse: Codable, Sendable { let rejected: Bool }
struct SMSSendResult: Codable, Sendable {
    let sent: Bool
    let segments: Int?

    init(sent: Bool, segments: Int?) {
        self.sent = sent
        self.segments = segments
    }
}

struct RelayErrorResponse: Decodable, Sendable { let error: String }
struct CallRecordingResponse: Codable, Sendable { let recording: Bool; let path: String? }
struct SIMIdentity: Codable, Sendable {
    let phoneNumber: String
    enum CodingKeys: String, CodingKey { case phoneNumber = "phone_number" }
}

// MARK: - 模块、网络与定位

struct ModemStatus: Codable, Equatable, Sendable {
    let imei: String?
    let firmware: String?
    let iccid: String?
    let imsi: String?
    let operatorName: String?
    let simInserted: Bool?
    let signalDBM: Int?
    let networkMode: String?
    let radioBand: String?
    let registrationText: String?
    let cellularState: String?
    let cellularRecovery: String?
    let lastRegisteredAt: Date?

    enum CodingKeys: String, CodingKey {
        case imei, firmware, iccid, imsi
        case operatorName = "operator"
        case simInserted = "sim_inserted"
        case signalDBM = "signal_dbm"
        case networkMode = "network_mode"
        case radioBand = "radio_band"
        case registrationText = "reg_status_text"
        case cellularState = "cellular_state"
        case cellularRecovery = "cellular_recovery"
        case lastRegisteredAt = "last_registered_at"
    }
}

struct NetworkTrafficSnapshot: Codable, Sendable {
    let available: Bool
    let interface: String?
    let rxBytes: UInt64
    let txBytes: UInt64
    let sessionRX: UInt64
    let sessionTX: UInt64
    let sessionTotal: UInt64
    let sampledAtMS: Int64
    let error: String?

    enum CodingKeys: String, CodingKey {
        case available, interface, error
        case rxBytes = "rx_bytes"
        case txBytes = "tx_bytes"
        case sessionRX = "session_rx_bytes"
        case sessionTX = "session_tx_bytes"
        case sessionTotal = "session_total_bytes"
        case sampledAtMS = "sampled_at_ms"
    }
}

/// Agent 从模块只读 sysfs 接口采样的电源与温度数据。
struct SystemPowerStatus: Codable, Sendable {
    let supported: Bool
    let readings: [SystemPowerReading]
    let sampledAtMS: Int64

    enum CodingKeys: String, CodingKey {
        case supported, readings
        case sampledAtMS = "sampled_at_ms"
    }
}

struct SystemPowerReading: Codable, Sendable, Identifiable {
    let kind: String
    let name: String
    let path: String
    let voltageV: Double?
    let currentA: Double?
    let powerW: Double?
    let temperatureC: Double?
    let capacityPercent: Int?
    let online: Bool?
    let status: String?

    var id: String { "\(kind):\(path)" }

    enum CodingKeys: String, CodingKey {
        case kind, name, path, online, status
        case voltageV = "voltage_v"
        case currentA = "current_a"
        case powerW = "power_w"
        case temperatureC = "temperature_c"
        case capacityPercent = "capacity_percent"
    }
}

struct CellularPolicyStatus: Codable, Sendable {
    let forceOff: Bool
    let services: [String]?
    enum CodingKeys: String, CodingKey { case forceOff = "force_off"; case services }
}

// MARK: - WRT Lite 路由与流量控制

struct RouterConfig: Codable, Equatable, Sendable {
    var internetAccess: Bool
    var natEnabled: Bool
    var monthlyQuotaBytes: UInt64
    var blockWhenQuotaExceeded: Bool
    var quotaResetDay: Int
    var downloadLimitKbps: Int
    var uploadLimitKbps: Int
    var schedules: [RouterSchedule]?
    var portForwards: [RouterPortForward]?
    var dnsOverrides: [RouterDNSOverride]?

    enum CodingKeys: String, CodingKey {
        case internetAccess = "internet_access"
        case natEnabled = "nat_enabled"
        case monthlyQuotaBytes = "monthly_quota_bytes"
        case blockWhenQuotaExceeded = "block_when_quota_exceeded"
        case quotaResetDay = "quota_reset_day"
        case downloadLimitKbps = "download_limit_kbps"
        case uploadLimitKbps = "upload_limit_kbps"
        case schedules
        case portForwards = "port_forwards"
        case dnsOverrides = "dns_overrides"
    }
}

struct RouterSchedule: Codable, Equatable, Sendable {
    var name: String
    var enabled: Bool
    var weekdays: [Int]
    var start: String
    var end: String
}

struct RouterPortForward: Codable, Equatable, Sendable, Identifiable {
    var name: String
    var enabled: Bool
    var `protocol`: String
    var externalPort: Int
    var internalIP: String
    var internalPort: Int
    var id: String { "\(`protocol`)-\(externalPort)-\(internalIP)-\(internalPort)" }

    enum CodingKeys: String, CodingKey {
        case name, enabled, `protocol`
        case externalPort = "external_port"
        case internalIP = "internal_ip"
        case internalPort = "internal_port"
    }
}

struct RouterDNSOverride: Codable, Equatable, Sendable, Identifiable {
    var hostname: String
    var address: String
    var id: String { hostname }
}

struct RouterUsage: Codable, Equatable, Sendable {
    let period: String
    let usedBytes: UInt64
    let lastRXBytes: UInt64
    let lastTXBytes: UInt64
    let updatedAt: String?

    enum CodingKeys: String, CodingKey {
        case period
        case usedBytes = "used_bytes"
        case lastRXBytes = "last_rx_bytes"
        case lastTXBytes = "last_tx_bytes"
        case updatedAt = "updated_at"
    }
}

struct RouterCapabilities: Codable, Equatable, Sendable {
    let nat: Bool
    let dhcp: Bool
    let dns: Bool
    let trafficAccounting: Bool
    let quotaEnforcement: Bool
    let rateShaping: Bool
    let portForwarding: Bool?
    let dnsOverrides: Bool?
    let schedules: Bool?
    let trafficHistory: Bool?
    let staticDHCP: Bool?
    let vpn: Bool?
    let perClientFirewall: Bool?

    enum CodingKeys: String, CodingKey {
        case nat, dhcp, dns
        case trafficAccounting = "traffic_accounting"
        case quotaEnforcement = "quota_enforcement"
        case rateShaping = "rate_shaping"
        case portForwarding = "port_forwarding"
        case dnsOverrides = "dns_overrides"
        case schedules
        case trafficHistory = "traffic_history"
        case staticDHCP = "static_dhcp"
        case vpn
        case perClientFirewall = "per_client_firewall"
    }
}

struct RouterTrafficDay: Codable, Equatable, Sendable, Identifiable {
    let date: String
    let rxBytes: UInt64
    let txBytes: UInt64
    var id: String { date }
    enum CodingKeys: String, CodingKey {
        case date
        case rxBytes = "rx_bytes"
        case txBytes = "tx_bytes"
    }
}

struct RouterTrafficHistory: Codable, Equatable, Sendable {
    let days: [RouterTrafficDay]
}

struct RouterForwardingStatus: Codable, Equatable, Sendable {
    let enabled: Bool
    let reason: String
    let blockLocalAgent: Bool

    enum CodingKeys: String, CodingKey {
        case enabled, reason
        case blockLocalAgent = "block_local_agent"
    }
}

struct RouterSystemStatus: Codable, Equatable, Sendable {
    let ipForward: Bool
    let wanInterface: String
    let lanInterface: String

    enum CodingKeys: String, CodingKey {
        case ipForward = "ip_forward"
        case wanInterface = "wan_interface"
        case lanInterface = "lan_interface"
    }
}

struct RouterStatus: Codable, Equatable, Sendable {
    let mode: String
    let config: RouterConfig
    let usage: RouterUsage
    let capabilities: RouterCapabilities
    let forwarding: RouterForwardingStatus
    let system: RouterSystemStatus?
    let lastError: String?
    let lastAppliedAt: String?
    let trafficHistory: RouterTrafficHistory?

    enum CodingKeys: String, CodingKey {
        case mode, config, usage, capabilities, forwarding, system
        case lastError = "last_error"
        case lastAppliedAt = "last_applied_at"
        case trafficHistory = "traffic_history"
    }
}

struct RouterConfigApplyResponse: Codable, Sendable {
    let config: RouterConfig
    let applied: Bool
    let warning: String?
}

struct RouterInternetResponse: Codable, Sendable {
    let internetAccess: Bool
    enum CodingKeys: String, CodingKey { case internetAccess = "internet_access" }
}

struct RouterRepairResponse: Codable, Sendable {
    let repaired: Bool
}

struct RouterClient: Codable, Identifiable, Sendable {
    let ip: String
    let mac: String?
    let hostname: String?
    let online: Bool
    let leaseRemainingSeconds: Int64?

    var id: String { mac?.isEmpty == false ? mac! : ip }

    enum CodingKeys: String, CodingKey {
        case ip, mac, hostname, online
        case leaseRemainingSeconds = "lease_remaining_seconds"
    }
}

struct RouterClientsResponse: Codable, Sendable {
    let clients: [RouterClient]
}

struct USBProfileStatus: Codable, Sendable {
    let mode: String
    let uacEnabled: Bool
    let configuration: String
    let needsReconnect: Bool
    let message: String?

    enum CodingKeys: String, CodingKey {
        case mode, configuration, message
        case uacEnabled = "uac_enabled"
        case needsReconnect = "needs_reconnect"
    }
}

struct NetworkCheckResult: Codable, Sendable {
    let ok: Bool
    let summary: String?
    let detail: String?
}

struct GPSStatus: Codable, Sendable {
    let enabled: Bool
    let lastFix: GPSFixSummary?
    let lastError: String?
    enum CodingKeys: String, CodingKey {
        case enabled
        case lastFix = "last_fix"
        case lastError = "last_error"
    }
}

struct GPSFixSummary: Codable, Sendable {
    let latitude: String?
    let longitude: String?
    let hdop: String
    let satellites: String
}

struct GPSControlResponse: Codable, Sendable {
    let enabled: Bool
    let lastFix: GPSFixSummary?
    enum CodingKeys: String, CodingKey { case enabled; case lastFix = "last_fix" }
}

struct ATResult: Codable, Sendable { let response: String }

// MARK: - eSIM

struct ESIMOverview: Codable, Sendable {
    let cardType: String?
    let message: String?
    let chipInfo: ESIMChipInfo?
    let profiles: [ESIMProfileGroup]?

    enum CodingKeys: String, CodingKey {
        case message, profiles
        case cardType = "card_type"
        case chipInfo = "chip_info"
    }
}

struct ESIMChipInfo: Codable, Sendable {
    let skuName: String?
    let serialNumber: String?
    let firmware: String?
    let eids: [ESIMEID]?

    enum CodingKeys: String, CodingKey {
        case firmware, eids
        case skuName = "sku_name"
        case serialNumber = "serial_number"
    }
}

struct ESIMEID: Codable, Sendable, Identifiable {
    let eid: String?
    let aid: String?
    let freeNvram: String?
    let firmware: String?
    let specGuess: String?

    var id: String { eid ?? aid ?? UUID().uuidString }

    enum CodingKeys: String, CodingKey {
        case eid, aid, firmware
        case freeNvram = "free_nvram"
        case specGuess = "spec_guess"
    }
}

struct ESIMProfileGroup: Codable, Sendable {
    let eid: String?
    let aidHex: String?
    let profiles: [ESIMProfile]?
    enum CodingKeys: String, CodingKey { case eid, profiles; case aidHex = "aid_hex" }
}

struct ESIMProfile: Codable, Sendable, Identifiable {
    let iccid: String?
    let name: String?
    let serviceProviderName: String?
    let state: Int?
    let stateText: String?

    var id: String { iccid ?? name ?? UUID().uuidString }
    var enabled: Bool { state == 1 }
    var displayName: String { name ?? serviceProviderName ?? iccid ?? "未命名 Profile" }

    enum CodingKeys: String, CodingKey {
        case iccid, name, state
        case serviceProviderName = "service_provider_name"
        case stateText = "state_text"
    }
}

struct ESIMHealth: Codable, Sendable {
    let ok: Bool?
    let message: String?
    let activeProfile: ESIMProfile?
    let moduleICCID: String?
    let registration: String?
    let registered: Bool?
    let signalDBM: Int?
    let networkMode: String?

    enum CodingKeys: String, CodingKey {
        case ok, message, registration, registered
        case activeProfile = "active_profile"
        case moduleICCID = "module_iccid"
        case signalDBM = "signal_dbm"
        case networkMode = "network_mode"
    }
}

struct ESIMSwitchResult: Codable, Sendable {
    let switchAccepted: Bool?
    let phase: String?
    let targetICCID: String?
    let recoveryPending: Bool?
    let moduleRebootRequested: Bool?
    let reconnectWaitSeconds: Int?

    enum CodingKeys: String, CodingKey {
        case phase
        case switchAccepted = "switch_accepted"
        case targetICCID = "target_iccid"
        case recoveryPending = "recovery_pending"
        case moduleRebootRequested = "module_reboot_requested"
        case reconnectWaitSeconds = "reconnect_wait_seconds"
    }
}

struct ESIMNote: Codable, Sendable { let label: String?; let phone: String?; let tags: String? }
struct ESIMNotesResponse: Codable, Sendable { let notes: [String: ESIMNote] }
struct ESIMDownloadResult: Codable, Sendable { let message: String? }

struct ESIMPhonebookProbe: Codable, Sendable {
    let storageSupported: Bool?
    let storageSelected: Bool?
    let readSupported: Bool?
    let writeSupported: Bool?
    let storageStatus: String?

    enum CodingKeys: String, CodingKey {
        case storageSupported = "storage_supported"
        case storageSelected = "storage_selected"
        case readSupported = "read_supported"
        case writeSupported = "write_supported"
        case storageStatus = "storage_status"
    }
}

// MARK: - 诊断与语音运行时

struct ModuleDebugSnapshot: Codable, Sendable {
    let debug: ModuleDebugBufferStatus
    let agent: ModuleDebugAgentStatus
    let at: ModuleDebugATStatus
    let usb: [String: String]
    let traffic: ModuleDebugTrafficStatus
    let modem: ModemStatus
    let calls: ModuleDebugCallStatus
    let sms: ModuleDebugSMSStatus
    let voice: VoiceRuntimeStatus
    let events: [ModuleDebugEvent]
}

struct ModuleDebugBufferStatus: Codable, Sendable {
    let latestSequence: UInt64
    let returnedEvents: Int
    let storedBytes: Int
    let maxEvents: Int
    let maxStoredBytes: Int

    enum CodingKeys: String, CodingKey {
        case latestSequence = "latest_sequence"
        case returnedEvents = "returned_events"
        case storedBytes = "stored_bytes"
        case maxEvents = "max_events"
        case maxStoredBytes = "max_stored_bytes"
    }
}

struct ModuleDebugAgentStatus: Codable, Sendable {
    let version: String
    let uptimeSeconds: Int
    let goroutines: Int
    let heapBytes: UInt64

    enum CodingKeys: String, CodingKey {
        case version, goroutines
        case uptimeSeconds = "uptime_seconds"
        case heapBytes = "heap_bytes"
    }
}

struct ModuleDebugATStatus: Codable, Sendable {
    let device: String
    let lastSuccessAt: String?
    let consecutiveFailures: Int
    let reopenCount: Int

    enum CodingKeys: String, CodingKey {
        case device
        case lastSuccessAt = "last_success_at"
        case consecutiveFailures = "consecutive_failures"
        case reopenCount = "reopen_count"
    }
}

struct ModuleDebugTrafficStatus: Codable, Sendable {
    let interface: String?
    let rxBytes: UInt64?
    let txBytes: UInt64?
    let error: String?

    enum CodingKeys: String, CodingKey {
        case interface, error
        case rxBytes = "rx_bytes"
        case txBytes = "tx_bytes"
    }
}

struct ModuleDebugCallStatus: Codable, Sendable {
    let active: CallRecord?
    let historyCount: Int
    let eventDriven: Bool
    let lastError: String

    enum CodingKeys: String, CodingKey {
        case active
        case historyCount = "history_count"
        case eventDriven = "event_driven"
        case lastError = "last_error"
    }
}

struct ModuleDebugSMSStatus: Codable, Sendable {
    let count: Int
    let lastError: String

    enum CodingKeys: String, CodingKey {
        case count
        case lastError = "last_error"
    }
}

struct ModuleDebugEvent: Codable, Sendable, Identifiable {
    let sequence: UInt64
    let timestamp: String
    let category: String
    let direction: String?
    let summary: String
    let payload: String?
    let fields: [String: String]?

    var id: UInt64 { sequence }
}

struct NetworkDiagnostic: Codable, Sendable {
    let usbnetMode: String?
    let usbcfg: String?
    let pdpContexts: [PDPContext]?
    let activeContexts: [Int]?
    let pdpAddresses: [String]?
    let interfaces: [NetworkInterface]?
    let defaultRoute: NetworkDefaultRoute?
    let usbNetworkPresent: Bool
    let usbDevice: USBDeviceStatus?
    let errors: [String: String]?

    enum CodingKeys: String, CodingKey {
        case usbcfg, errors
        case usbnetMode = "usbnet_mode"
        case pdpContexts = "pdp_contexts"
        case activeContexts = "active_contexts"
        case pdpAddresses = "pdp_addresses"
        // 后端为兼容 Mac 版保留了旧字段名。
        case interfaces = "mac_interfaces"
        case defaultRoute = "default_route"
        case usbNetworkPresent = "usb_network_present"
        case usbDevice = "usb_device"
    }
}

struct PDPContext: Codable, Sendable { let id: Int?; let pdn: String?; let apn: String? }
struct NetworkInterface: Codable, Sendable {
    let name: String?; let status: String?; let ipv4: String?; let mac: String?; let kind: String?
}
struct NetworkDefaultRoute: Codable, Sendable { let interface: String?; let gateway: String? }
struct USBDeviceStatus: Codable, Sendable {
    let vendor: String?; let product: String?; let vendorID: String?; let productID: String?; let mode: String?
    enum CodingKeys: String, CodingKey {
        case vendor, product, mode
        case vendorID = "vendor_id"
        case productID = "product_id"
    }
}

struct ModuleSetupStatus: Codable, Sendable {
    let state: String
    let summary: String
    let detail: String?
    let canInitialize: Bool
    let requiresConfirmation: Bool

    enum CodingKeys: String, CodingKey {
        case state, summary, detail
        case canInitialize = "can_initialize"
        case requiresConfirmation = "requires_confirmation"
    }
}

struct VoiceRuntimeStatus: Codable, Sendable {
    let ready: Bool
    let runtimeInstalled: Bool
    let runtimeSource: String?
    let runtimeDetail: String?
    let lastError: String?

    enum CodingKeys: String, CodingKey {
        case ready
        case runtimeInstalled = "runtime_installed"
        case runtimeSource = "runtime_source"
        case runtimeDetail = "runtime_detail"
        case lastError = "last_error"
    }
}

struct ModuleUpdateStatus: Codable, Sendable {
    let supported: Bool
    let formatVersion: Int?
    let platform: String?
    let installedVersion: String?
    let publicKeyID: String?
    let dataFreeBytes: UInt64?
    let tempFreeBytes: UInt64?
    let callActive: Bool?
    let updatePending: Bool?
    let agentPID: Int?
    let factoryServicePID: Int?

    enum CodingKeys: String, CodingKey {
        case supported, platform
        case formatVersion = "format_version"
        case installedVersion = "installed_version"
        case publicKeyID = "public_key_id"
        case dataFreeBytes = "data_free_bytes"
        case tempFreeBytes = "temp_free_bytes"
        case callActive = "call_active"
        case updatePending = "update_pending"
        case agentPID = "agent_pid"
        case factoryServicePID = "factory_service_pid"
    }
}

enum ModuleUpdateInstallMode: String, Sendable {
    case normal
    case repair
}

struct ModuleUpdateInstallationState: Codable, Sendable, Equatable {
    let operationID: String?
    let mode: String?
    let phase: String
    let installedVersion: String?
    let targetVersion: String?
    let progress: Int
    let message: String
    let error: String?
    let rollbackConfirmed: Bool
    let updatedAt: Date?

    enum CodingKeys: String, CodingKey {
        case mode, phase, progress, message, error
        case operationID = "operation_id"
        case installedVersion = "installed_version"
        case targetVersion = "target_version"
        case rollbackConfirmed = "rollback_confirmed"
        case updatedAt = "updated_at"
    }
}

struct ModuleUpdateLogChunk: Codable, Sendable {
    let text: String
    let nextOffset: Int64
    let complete: Bool

    enum CodingKeys: String, CodingKey {
        case text, complete
        case nextOffset = "next_offset"
    }
}

struct ModuleUpdateResult: Codable, Sendable {
    let updated: Bool
    let version: String?
    let restartRequired: Bool?
    let message: String?

    enum CodingKeys: String, CodingKey {
        case updated, version, message
        case restartRequired = "restart_required"
    }
}

struct MaVoAudioHostConfig: Codable, Sendable {
    let vendorID: UInt16
    let productID: UInt16
    let locationID: UInt32
    let routeReady: Bool
    let routeSessionReady: Bool?
    let routeError: String?
    let routeRunning: Bool?
    let helperPID: Int?
    let sessionStartedAt: String?
    let statisticsAvailable: Bool?
    let statistics: VoicePCMStatistics?
    let diagnosticLog: String?
    let logTail: [String]?

    enum CodingKeys: String, CodingKey {
        case vendorID = "vendor_id"
        case productID = "product_id"
        case locationID = "location_id"
        case routeReady = "route_ready"
        case routeSessionReady = "route_session_ready"
        case routeError = "route_error"
        case routeRunning = "route_running"
        case helperPID = "helper_pid"
        case sessionStartedAt = "session_started_at"
        case statisticsAvailable = "statistics_available"
        case statistics
        case diagnosticLog = "diagnostic_log"
        case logTail = "log_tail"
    }
}

/// 模块语音桥的累计 PCM 统计；峰值为 PCM16 绝对幅度，0 代表纯静音。
struct VoicePCMStatistics: Codable, Sendable {
    let uplinkBytes: UInt64
    let uplinkFrames: UInt64
    let uplinkPeak: UInt64
    let downlinkBytes: UInt64
    let downlinkFrames: UInt64
    let downlinkPeak: UInt64
    let downlinkDroppedFrames: UInt64

    enum CodingKeys: String, CodingKey {
        case uplinkBytes = "uplink_bytes"
        case uplinkFrames = "uplink_frames"
        case uplinkPeak = "uplink_peak"
        case downlinkBytes = "downlink_bytes"
        case downlinkFrames = "downlink_frames"
        case downlinkPeak = "downlink_peak"
        case downlinkDroppedFrames = "downlink_dropped_frames"
    }
}

/// 来电策略：黑白名单 + 分时段静默。iOS 侧执行（relay 侧执行是 clean-room 重写版的规划）。
struct CallPolicy: Codable {
    enum Mode: String, Codable, CaseIterable {
        case off, blacklist, whitelist
        var title: String {
            switch self {
            case .off: return "关闭"
            case .blacklist: return "黑名单"
            case .whitelist: return "白名单"
            }
        }
    }
    var mode: Mode = .off
    /// 归一化后的号码（纯数字，已去 +86/0086 前缀）。
    var blacklist: [String] = []
    var whitelist: [String] = []
    /// 按卡槽隔离的黑名单（key 为 slotIndex 0/1），号码格式与 blacklist 一致。
    var blacklistBySlot: [Int: [String]] = [:]
    /// 按卡槽隔离的白名单（key 为 slotIndex 0/1），号码格式与 whitelist 一致。
    var whitelistBySlot: [Int: [String]] = [:]
    /// 白名单模式/静默例外是否包含通讯录全部号码。
    var whitelistIncludesContacts: Bool = false
    var quietRules: [QuietRule] = []
    var blockSMSEnabled: Bool = false
    var blockSMSText: String = "您拨打的号码暂时无法接听，请稍后再拨。"
    /// 未接来电自动回复短信。
    var missedReplyEnabled: Bool = false
    var missedReplyText: String = "刚才没能接听您的电话，稍后会回复您。"
    /// 仅回复通讯录联系人（避免向陌生/骚扰号码确认本机活跃）。
    var missedReplyContactsOnly: Bool = true
}

/// 分时段静默规则。deviceID 为空表示全部设备（单卡场景等同按 SIM 卡）。
/// slotIndex 为 nil 表示适用于所有 SIM，否则只对指定卡槽生效。
struct QuietRule: Codable, Identifiable {
    var id: UUID = UUID()
    var deviceID: String = ""
    var slotIndex: Int? = nil
    /// 当天分钟数 0..<1440；start > end 表示跨天（如 23:00–07:00）。
    var startMinutes: Int = 1380
    var endMinutes: Int = 420
    var enabled: Bool = true

    func isActive(at date: Date, deviceID: String?, slotIndex: Int? = nil) -> Bool {
        guard enabled else { return false }
        if !self.deviceID.isEmpty, self.deviceID != deviceID { return false }
        if let ruleSlot = self.slotIndex, ruleSlot != slotIndex { return false }
        let cal = Calendar.current
        let mins = cal.component(.hour, from: date) * 60 + cal.component(.minute, from: date)
        if startMinutes <= endMinutes {
            return mins >= startMinutes && mins < endMinutes
        }
        return mins >= startMinutes || mins < endMinutes
    }

    var timeRangeText: String {
        "\(Self.text(for: startMinutes))–\(Self.text(for: endMinutes))"
    }

    static func text(for minutes: Int) -> String {
        String(format: "%02d:%02d", minutes / 60, minutes % 60)
    }
}

/// 被策略拦截的来电记录。
struct BlockedCallEvent: Codable, Identifiable {
    var id: UUID = UUID()
    var number: String?
    var deviceID: String?
    var reason: String
    var date: Date = Date()
}

struct CallPolicyDecision {
    let shouldBlock: Bool
    let reason: String
    static let allow = CallPolicyDecision(shouldBlock: false, reason: "")
}

/// 来电策略存储：UserDefaults 持久化，主线程访问。
@MainActor
final class CallPolicyStore: ObservableObject {
    static let shared = CallPolicyStore()

    @Published var policy: CallPolicy {
        didSet {
            savePolicy()
            // 黑名单变化时通知（AppModel 会同步到 Android 本地）。
            if policy.blacklist != oldValue.blacklist {
                onBlacklistChanged?()
            }
        }
    }
    @Published private(set) var blockedLog: [BlockedCallEvent] = []

    /// 黑名单变化时的回调（由 AppModel 设置，用于同步到 Android）。
    var onBlacklistChanged: (() -> Void)?

    private static let policyKey = "uphone.callPolicy"
    private static let logKey = "uphone.blockedCallLog"
    private static let repliedMissedKey = "uphone.repliedMissedCalls"
    private static let maxLogCount = 100
    private static let maxRepliedCount = 50

    /// 由 AppModel 注入：返回归一化后的全部联系人号码（白名单包含联系人时用）。
    var contactNumberProvider: (() -> Set<String>)?

    /// 已发送未接回复的通话 ID（防重复）。
    private var repliedMissedCallIDs: Set<String> = []

    private init() {
        if let data = UserDefaults.standard.data(forKey: Self.policyKey),
           let decoded = try? JSONDecoder().decode(CallPolicy.self, from: data) {
            policy = decoded
        } else {
            policy = CallPolicy()
        }
        if let data = UserDefaults.standard.data(forKey: Self.logKey),
           let decoded = try? JSONDecoder().decode([BlockedCallEvent].self, from: data) {
            blockedLog = decoded
        }
        if let ids = UserDefaults.standard.stringArray(forKey: Self.repliedMissedKey) {
            repliedMissedCallIDs = Set(ids)
        }
    }

    private func savePolicy() {
        if let data = try? JSONEncoder().encode(policy) {
            UserDefaults.standard.set(data, forKey: Self.policyKey)
        }
    }

    /// 号码归一化：只留数字，去 +86/0086 前缀。
    static func normalizedNumber(_ raw: String) -> String {
        var digits = raw.filter(\.isWholeNumber)
        if digits.hasPrefix("0086"), digits.count > 12 {
            digits = String(digits.dropFirst(4))
        } else if digits.hasPrefix("86"), digits.count == 13, digits.dropFirst(2).first == "1" {
            digits = String(digits.dropFirst(2))
        }
        return digits
    }

    func decision(for number: String?, deviceID: String?, at date: Date = Date(), slotIndex: Int? = nil) -> CallPolicyDecision {
        let normalized = Self.normalizedNumber(number ?? "")
        // 静默时段：白名单（含联系人）不受限，其余一律拦截；规则可按 SIM 卡隔离。
        if policy.quietRules.contains(where: { $0.isActive(at: date, deviceID: deviceID, slotIndex: slotIndex) }) {
            return !normalized.isEmpty && isWhitelisted(normalized)
                ? .allow
                : CallPolicyDecision(shouldBlock: true, reason: "静默时段")
        }
        switch policy.mode {
        case .off:
            return .allow
        case .blacklist:
            // 全局黑名单或该卡槽的黑名单命中其一即拦截。
            let inSlotBlacklist = slotIndex.flatMap { policy.blacklistBySlot[$0] }?.contains(normalized) ?? false
            return (policy.blacklist.contains(normalized) || inSlotBlacklist)
                ? CallPolicyDecision(shouldBlock: true, reason: "黑名单")
                : .allow
        case .whitelist:
            // 未知号码也视为非白名单，一律拒接；该卡槽的白名单同样放行。
            let inSlotWhitelist = slotIndex.flatMap { policy.whitelistBySlot[$0] }?.contains(normalized) ?? false
            return !normalized.isEmpty && (isWhitelisted(normalized) || inSlotWhitelist)
                ? .allow
                : CallPolicyDecision(shouldBlock: true, reason: "非白名单号码")
        }
    }

    /// 是否在白名单中（含"包含通讯录"选项）。
    private func isWhitelisted(_ normalized: String) -> Bool {
        if policy.whitelist.contains(normalized) { return true }
        if policy.whitelistIncludesContacts, let provider = contactNumberProvider {
            return provider().contains(normalized)
        }
        return false
    }

    func logBlocked(number: String?, deviceID: String?, reason: String, at date: Date = Date()) {
        var log = blockedLog
        log.insert(BlockedCallEvent(number: number, deviceID: deviceID, reason: reason, date: date), at: 0)
        if log.count > Self.maxLogCount { log = Array(log.prefix(Self.maxLogCount)) }
        blockedLog = log
        if let data = try? JSONEncoder().encode(log) {
            UserDefaults.standard.set(data, forKey: Self.logKey)
        }
    }

    func clearBlockedLog() {
        blockedLog = []
        UserDefaults.standard.removeObject(forKey: Self.logKey)
    }

    /// 是否需要对该未接来电发送自动回复（未发送过才返回 true）。
    func shouldReplyToMissedCall(id: String) -> Bool {
        !repliedMissedCallIDs.contains(id)
    }

    /// 标记已发送未接回复（持久化，防重复）。
    func markMissedCallReplied(id: String) {
        repliedMissedCallIDs.insert(id)
        if repliedMissedCallIDs.count > Self.maxRepliedCount {
            repliedMissedCallIDs = Set(repliedMissedCallIDs.suffix(Self.maxRepliedCount))
        }
        UserDefaults.standard.set(Array(repliedMissedCallIDs), forKey: Self.repliedMissedKey)
    }

    /// 该号码最近是否已回复过（10 分钟内只回复一次，避免连续漏接时刷屏）。
    func recentlyReplied(to normalizedNumber: String) -> Bool {
        // 用 blockedLog 的时间戳做近似：查找最近的回复记录
        // （实际实现：在 UserDefaults 存号码->时间的映射）
        let key = Self.repliedMissedKey + ".time." + normalizedNumber
        let last = UserDefaults.standard.double(forKey: key)
        return last > 0 && Date().timeIntervalSince1970 - last < 600
    }

    func markReplied(to normalizedNumber: String) {
        let key = Self.repliedMissedKey + ".time." + normalizedNumber
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: key)
    }

    /// 加入黑名单；号码为空或已在名单中时返回 false。
    @discardableResult
    func addToBlacklist(_ raw: String) -> Bool {
        let normalized = Self.normalizedNumber(raw)
        guard !normalized.isEmpty, !policy.blacklist.contains(normalized) else { return false }
        policy.blacklist.append(normalized)
        return true
    }

    /// 加入白名单；号码为空或已在名单中时返回 false。
    @discardableResult
    func addToWhitelist(_ raw: String) -> Bool {
        let normalized = Self.normalizedNumber(raw)
        guard !normalized.isEmpty, !policy.whitelist.contains(normalized) else { return false }
        policy.whitelist.append(normalized)
        return true
    }

    func deviceName(for deviceID: String?) -> String? {
        guard let deviceID, !deviceID.isEmpty else { return nil }
        return DeviceRegistry.allDevices().first { $0.deviceID == deviceID }?.displayName
    }
}
