import Foundation
import Security

/// One Android 值守机 paired with this iPhone.
struct PairedDevice: Codable, Identifiable, Equatable {
    var id: String { deviceID }
    let deviceID: String
    let deviceSecret: String
    let relayURL: String
    var deviceName: String
    let pairedAt: Date

    var displayName: String {
        deviceName.isEmpty ? "值守机 …\(deviceID.suffix(6))" : deviceName
    }
}

/// Keychain-backed registry of paired Android devices (multi-device support).
///
/// The pre-existing single-device keychain slots (airsim.push-device-id etc.)
/// remain the "active device" consumed by all call/SMS flows; this registry
/// tracks every paired device so the user can switch between them.
enum DeviceRegistry {
    private static let service = "com.sheldonjoo.uphone.push"
    private static let listAccount = "paired-devices"
    private static let activeAccount = "active-device-id"

    // MARK: - Device list

    static func allDevices() -> [PairedDevice] {
        guard let data = load(account: listAccount),
              let devices = try? JSONDecoder().decode([PairedDevice].self, from: data)
        else { return [] }
        return devices
    }

    private static func saveAll(_ devices: [PairedDevice]) -> Bool {
        guard let data = try? JSONEncoder().encode(devices) else { return false }
        return save(data, account: listAccount)
    }

    /// Insert or replace by deviceID. Returns the stored device.
    @discardableResult
    static func upsert(_ device: PairedDevice) -> PairedDevice {
        var devices = allDevices().filter { $0.deviceID != device.deviceID }
        devices.append(device)
        devices.sort { $0.pairedAt < $1.pairedAt }
        _ = saveAll(devices)
        return device
    }

    static func remove(deviceID: String) {
        let devices = allDevices().filter { $0.deviceID != deviceID }
        _ = saveAll(devices)
        if activeDeviceID() == deviceID {
            _ = save(Data(), account: activeAccount)
        }
    }

    static func rename(deviceID: String, to name: String) {
        var devices = allDevices()
        guard let index = devices.firstIndex(where: { $0.deviceID == deviceID }) else { return }
        devices[index].deviceName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        _ = saveAll(devices)
    }

    static func device(for deviceID: String) -> PairedDevice? {
        allDevices().first { $0.deviceID == deviceID }
    }

    // MARK: - Active device

    static func activeDeviceID() -> String? {
        guard let data = load(account: activeAccount),
              let id = String(data: data, encoding: .utf8),
              !id.isEmpty
        else { return nil }
        return id
    }

    static func setActiveDeviceID(_ deviceID: String) {
        _ = save(Data(deviceID.utf8), account: activeAccount)
    }

    /// Credentials for answering / acting on an event that names a device.
    /// Falls back to the active device when the named one is unknown.
    static func credentials(for deviceID: String?) -> PairedDevice? {
        if let deviceID, let device = device(for: deviceID) { return device }
        if let active = activeDeviceID() { return device(for: active) }
        return allDevices().first
    }

    // MARK: - Keychain plumbing
    private static func load(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data
        else { return nil }
        return data
    }

    private static func save(_ data: Data, account: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        var item: CFTypeRef?
        let exists = SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess
        if exists {
            let update: [String: Any] = [kSecValueData as String: data]
            return SecItemUpdate(query as CFDictionary, update as CFDictionary) == errSecSuccess
        }
        var add = query
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        add[kSecValueData as String] = data
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }
}

// MARK: - Multi-device pairing & switching

extension DeviceRegistry {
    private static let stagedAccount = "staged-device-secret"
    private static let activeSecretAccount = "device-secret"
    private static let deviceIDKey = "airsim.push-device-id"
    private static let relayURLKey = "airsim.push-relay-url"

    /// Generate a fresh relay credential set for pairing an additional device.
    /// Staged separately; the active secret is untouched until activateStagedDevice.
    @discardableResult
    static func stageNewDeviceSecret() -> String? {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { return nil }
        let secret = Data(bytes).base64EncodedString()
        guard save(Data(secret.utf8), account: stagedAccount) else { return nil }
        return secret
    }

    static func stagedDeviceSecret() -> String? {
        guard let data = load(account: stagedAccount),
              let secret = String(data: data, encoding: .utf8), !secret.isEmpty
        else { return nil }
        return secret
    }

    /// Import the current active slots into the registry when missing.
    /// Makes pre-existing single-device pairings show up in the device list.
    static func archiveActiveDeviceIfNeeded() {
        let defaults = UserDefaults.standard
        guard let deviceID = defaults.string(forKey: deviceIDKey), !deviceID.isEmpty,
              let secretData = load(account: activeSecretAccount),
              let secret = String(data: secretData, encoding: .utf8), !secret.isEmpty,
              device(for: deviceID) == nil
        else { return }
        let relayURL = defaults.string(forKey: relayURLKey) ?? ""
        upsert(PairedDevice(deviceID: deviceID, deviceSecret: secret,
                            relayURL: relayURL, deviceName: "", pairedAt: Date()))
        if activeDeviceID() == nil { setActiveDeviceID(deviceID) }
    }

    /// After a successful new-device pairing: archive the previous active device,
    /// then make the staged credentials the active ones.
    @discardableResult
    static func activateStagedDevice(deviceName: String, relayURL: String) -> PairedDevice? {
        guard let secret = stagedDeviceSecret() else { return nil }
        let deviceID = PushDeviceIdentity.deviceID(deviceSecret: secret)
        archiveActiveDeviceIfNeeded()
        let device = PairedDevice(deviceID: deviceID, deviceSecret: secret,
                                  relayURL: relayURL, deviceName: deviceName, pairedAt: Date())
        upsert(device)
        UserDefaults.standard.set(deviceID, forKey: deviceIDKey)
        UserDefaults.standard.set(relayURL, forKey: relayURLKey)
        _ = save(Data(secret.utf8), account: activeSecretAccount)
        setActiveDeviceID(deviceID)
        delete(account: stagedAccount)
        return device
    }

    /// Switch the active relay credentials to another paired device.
    /// The caller should trigger a registration re-sync afterwards.
    @discardableResult
    static func switchTo(deviceID: String) -> Bool {
        guard let device = device(for: deviceID) else { return false }
        UserDefaults.standard.set(device.deviceID, forKey: deviceIDKey)
        UserDefaults.standard.set(device.relayURL, forKey: relayURLKey)
        _ = save(Data(device.deviceSecret.utf8), account: activeSecretAccount)
        setActiveDeviceID(deviceID)
        return true
    }

    private static func delete(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
