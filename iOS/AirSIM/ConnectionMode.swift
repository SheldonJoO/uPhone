import Foundation

/// 连接模式：VoWLAN / VoBLE / 云中继 各自独立可选，外加混合（三级递进，默认）。
///
/// - hybrid：三级递进 VoWLAN › VoBLE › 云中继，前者优先（原有行为）。
/// - vowlan：只用 Wi-Fi 直连，不可达即失败。
/// - voble：只用 VoBLE 蓝牙直连（无网近场通话）。
/// - cloud：只用云中继（外地收发；需要有效 APNs 凭据，侧载无 PushKit token 时来推不可用）。
enum ConnectionMode: String, CaseIterable, Identifiable {
    case hybrid
    case vowlan
    case voble
    case cloud

    var id: String { rawValue }

    var title: String {
        switch self {
        case .hybrid: return "混合（自动递进）"
        case .vowlan: return "仅 Wi-Fi 直连"
        case .voble: return "仅蓝牙直连"
        case .cloud: return "仅云中继"
        }
    }

    var detail: String {
        switch self {
        case .hybrid: return "同一 Wi-Fi 走 VoWLAN；无网走 VoBLE 蓝牙；都没有才走云中继"
        case .vowlan: return "只用 VoWLAN。不可达即失败，不经过公网、不耗流量"
        case .voble: return "只用 VoBLE 蓝牙直连，适合无 Wi-Fi 的近场通话（窄带 64kbps）"
        case .cloud: return "只用云中继，外地也能收发。侧载无 PushKit 凭据时来电推送不可用"
        }
    }

    var systemImage: String {
        switch self {
        case .hybrid: return "arrow.triangle.branch"
        case .vowlan: return "wifi"
        case .voble: return "bolt.horizontal"
        case .cloud: return "cloud"
        }
    }

    /// 该模式是否使用某条链路。
    func allows(_ transport: CallTransport) -> Bool {
        switch self {
        case .hybrid: return true
        case .vowlan: return transport == .vowlan
        case .voble: return transport == .voble
        case .cloud: return transport == .cloud
        }
    }

    /// 该模式是否需要持续扫描 VoBLE 服务。
    var scansVoBLE: Bool {
        self == .hybrid || self == .voble
    }

    static let preferenceKey = "connection-mode"

    static var current: ConnectionMode {
        let raw = UserDefaults.standard.string(forKey: preferenceKey)
        return ConnectionMode(rawValue: raw ?? "") ?? .hybrid
    }

    static func set(_ mode: ConnectionMode) {
        UserDefaults.standard.set(mode.rawValue, forKey: preferenceKey)
    }
}
