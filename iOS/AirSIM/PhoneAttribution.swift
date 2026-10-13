import Foundation

/// 离线手机号码归属地查询。
/// 数据：phone.dat（ls0f/phone，MIT 协议，数据截至 2023-12，共 499527 条 7 位号段记录）。
/// 文件格式（小端）：8 字节头（4 字节版本 + 4 字节索引区偏移），记录区为
/// "省份|城市|邮编|区号\0" 字符串，索引区每 9 字节一条（7 位号段 int32 + 记录偏移 int32 + 卡类型 uint8）。
/// 卡类型：1=移动 2=联通 3=电信 4=电信虚拟运营商 5=联通虚拟运营商 6=移动虚拟运营商。
struct PhoneAttributionResult {
    let province: String
    let city: String
    let carrier: String

    /// 用于 CallKit 来电显示，如“北京”。
    var shortPlace: String { city.isEmpty ? province : city }
    /// 用于 App 内展示，如“北京 · 中国移动”。
    var displayText: String { "\(shortPlace) · \(carrier)" }
}

final class PhoneAttribution {
    static let shared = PhoneAttribution()

    private let lock = NSLock()
    private var loaded = false
    /// 排序后的索引：(7 位号段, 记录区偏移, 卡类型)
    private var index: [(prefix: Int, offset: Int, card: UInt8)] = []
    private var recordBytes: [UInt8] = []

    private init() {}

    /// 查询手机号归属地；非大陆手机号或查不到时返回 nil。
    func lookup(_ number: String?) -> PhoneAttributionResult? {
        guard let prefix = Self.sevenDigitPrefix(from: number) else { return nil }
        ensureLoaded()
        lock.lock()
        defer { lock.unlock() }
        guard loaded, !index.isEmpty else { return nil }
        var lo = 0
        var hi = index.count - 1
        while lo <= hi {
            let mid = (lo + hi) / 2
            let entry = index[mid]
            if entry.prefix == prefix {
                return result(for: entry)
            } else if entry.prefix < prefix {
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return nil
    }

    /// 脱敏显示，如 13800101234 -> "138****1234"（支持 +86/0086/86 前缀）。
    static func masked(_ number: String?) -> String? {
        guard let digits = Self.mainlandDigits(from: number) else { return nil }
        return String(digits.prefix(3)) + "****" + String(digits.suffix(4))
    }

    // MARK: - 私有

    private func result(for entry: (prefix: Int, offset: Int, card: UInt8)) -> PhoneAttributionResult? {
        guard entry.offset < recordBytes.count else { return nil }
        var end = entry.offset
        while end < recordBytes.count && recordBytes[end] != 0 { end += 1 }
        let slice = recordBytes[entry.offset..<end]
        guard let text = String(bytes: slice, encoding: .utf8) else { return nil }
        // 记录格式：省份|城市|邮编|区号
        let parts = text.split(separator: "|", omittingEmptySubsequences: false)
        guard parts.count >= 2 else { return nil }
        return PhoneAttributionResult(
            province: String(parts[0]),
            city: String(parts[1]),
            carrier: Self.carrierName(for: entry.card)
        )
    }

    private static func carrierName(for card: UInt8) -> String {
        switch card {
        case 1: return "中国移动"
        case 2: return "中国联通"
        case 3: return "中国电信"
        case 4: return "电信虚拟运营商"
        case 5: return "联通虚拟运营商"
        case 6: return "移动虚拟运营商"
        default: return "未知运营商"
        }
    }

    private static func digitsOnly(_ number: String?) -> String? {
        guard let number, !number.isEmpty else { return nil }
        let digits = number.filter { $0.isNumber }
        guard !digits.isEmpty else { return nil }
        return digits
    }

    /// 归一化为 11 位大陆手机号（去 +86/0086/86 前缀）；非大陆手机号返回 nil。
    private static func mainlandDigits(from number: String?) -> String? {
        guard var digits = digitsOnly(number) else { return nil }
        if digits.hasPrefix("0086") { digits = String(digits.dropFirst(4)) }
        else if digits.hasPrefix("86"), digits.count == 13 { digits = String(digits.dropFirst(2)) }
        guard digits.count == 11, digits.hasPrefix("1") else { return nil }
        return digits
    }

    /// 提取 7 位号段：仅支持中国大陆手机号（11 位以 1 开头，允许 +86/0086/86 前缀）。
    private static func sevenDigitPrefix(from number: String?) -> Int? {
        guard let digits = mainlandDigits(from: number) else { return nil }
        return Int(digits.prefix(7))
    }

    private func ensureLoaded() {
        lock.lock()
        defer { lock.unlock() }
        guard !loaded else { return }
        loaded = true
        guard let url = Bundle.main.url(forResource: "phone", withExtension: "dat"),
              let data = try? Data(contentsOf: url), data.count > 8 else { return }
        let bytes = [UInt8](data)
        let firstIndexOffset = Int(bytes[4])
            | (Int(bytes[5]) << 8)
            | (Int(bytes[6]) << 16)
            | (Int(bytes[7]) << 24)
        guard firstIndexOffset > 8, firstIndexOffset < bytes.count else { return }
        recordBytes = Array(bytes[8..<firstIndexOffset])
        var entries: [(Int, Int, UInt8)] = []
        entries.reserveCapacity((bytes.count - firstIndexOffset) / 9)
        var offset = firstIndexOffset
        while offset + 9 <= bytes.count {
            let prefix = Int(bytes[offset])
                | (Int(bytes[offset + 1]) << 8)
                | (Int(bytes[offset + 2]) << 16)
                | (Int(bytes[offset + 3]) << 24)
            let recordOffset = Int(bytes[offset + 4])
                | (Int(bytes[offset + 5]) << 8)
                | (Int(bytes[offset + 6]) << 16)
                | (Int(bytes[offset + 7]) << 24)
            let card = bytes[offset + 8]
            entries.append((prefix, recordOffset, card))
            offset += 9
        }
        index = entries
    }
}
