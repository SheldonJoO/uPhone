import Foundation

/// iOS 端通话录音：在传输层抓取双向 8kHz 16-bit PCM（上行=本机麦克风，下行=远端声音），
/// 写入立体声 WAV（左声道=本机，右声道=远端），保存在 Documents/CallRecordings。
/// 线程安全：所有可变状态由 NSLock 保护，可在音频回调线程直接调用。
final class CallRecorder: @unchecked Sendable {
    static let sampleRate: UInt32 = 8_000
    static let channels: UInt16 = 2
    static let bitsPerSample: UInt16 = 16

    private let lock = NSLock()
    private var handle: FileHandle?
    private var pendingUp = Data()
    private var pendingDown = Data()
    private var dataBytes: UInt32 = 0
    private var stopped = false
    let fileURL: URL

    init?() {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("CallRecordings", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        } catch {
            return nil
        }
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        let stamp = formatter.string(from: Date())
        fileURL = dir.appendingPathComponent("uPhone_\(stamp).wav")
        guard FileManager.default.createFile(atPath: fileURL.path, contents: wavHeader(dataBytes: 0), attributes: nil),
              let handle = try? FileHandle(forWritingTo: fileURL) else {
            return nil
        }
        self.handle = handle
    }

    /// 写入上行（本机麦克风）16-bit PCM mono 数据。
    func writeUplink(_ pcm: Data) {
        append(pcm, uplink: true)
    }

    /// 写入下行（远端声音）16-bit PCM mono 数据。
    func writeDownlink(_ pcm: Data) {
        append(pcm, uplink: false)
    }

    private func append(_ pcm: Data, uplink: Bool) {
        guard !pcm.isEmpty else { return }
        lock.lock()
        defer { lock.unlock() }
        guard !stopped, handle != nil else { return }
        if uplink { pendingUp.append(pcm) } else { pendingDown.append(pcm) }
        // 防声道时间错位：单侧堆积超过 1 秒时丢弃最旧的多余数据
        //（如下行长时间静音），保持双声道时间对齐。
        let maxPending = Int(Self.sampleRate) * 2 // 1 秒 mono 16-bit
        if pendingUp.count > pendingDown.count + maxPending {
            pendingUp.removeFirst(pendingUp.count - pendingDown.count - maxPending)
        } else if pendingDown.count > pendingUp.count + maxPending {
            pendingDown.removeFirst(pendingDown.count - pendingUp.count - maxPending)
        }
        interleaveLocked()
    }

    /// 把两侧 pending 按帧交错写入（左=上行，右=下行）；短的一侧补零对齐。
    private func interleaveLocked() {
        var out = Data()
        out.reserveCapacity(min(pendingUp.count, pendingDown.count) * 2)
        while pendingUp.count >= 2 && pendingDown.count >= 2 {
            out.append(pendingUp[0])
            out.append(pendingUp[1])
            out.append(pendingDown[0])
            out.append(pendingDown[1])
            pendingUp.removeFirst(2)
            pendingDown.removeFirst(2)
        }
        writeLocked(out)
    }

    private func writeLocked(_ data: Data) {
        guard !data.isEmpty, let handle else { return }
        do {
            try handle.write(contentsOf: data)
            dataBytes &+= UInt32(data.count)
        } catch {
            // 磁盘写入失败：停止录音，关闭句柄，保留已写部分
            stopped = true
            try? handle.close()
            self.handle = nil
        }
    }

    /// 结束录音：补齐尾部、回填 WAV 头长度，返回文件 URL。
    /// 数据不足 1 秒视为无效录音，删除文件并返回 nil。
    func stop() -> URL? {
        lock.lock()
        defer { lock.unlock() }
        guard !stopped else { return nil }
        stopped = true
        // 尾部对齐：短的一侧补零
        var tail = Data()
        let upFrames = pendingUp.count / 2
        let downFrames = pendingDown.count / 2
        let frames = max(upFrames, downFrames)
        for i in 0..<frames {
            let u0 = i < upFrames ? pendingUp[i * 2] : 0
            let u1 = i < upFrames ? pendingUp[i * 2 + 1] : 0
            let d0 = i < downFrames ? pendingDown[i * 2] : 0
            let d1 = i < downFrames ? pendingDown[i * 2 + 1] : 0
            tail.append(contentsOf: [u0, u1, d0, d1])
        }
        pendingUp.removeAll()
        pendingDown.removeAll()
        writeLocked(tail)
        // 回填头
        if let handle {
            do {
                try handle.seek(toOffset: 0)
                try handle.write(contentsOf: wavHeader(dataBytes: dataBytes))
                try handle.close()
            } catch {
                try? handle.close()
            }
            self.handle = nil
        }
        // 有效时长（秒）= 数据字节 / (采样率*声道数*2字节)
        let seconds = Double(dataBytes) / Double(Self.sampleRate * UInt32(Self.channels) * 2)
        guard seconds >= 1.0 else {
            try? FileManager.default.removeItem(at: fileURL)
            return nil
        }
        return fileURL
    }

    private func wavHeader(dataBytes: UInt32) -> Data {
        var header = Data()
        func appendString(_ s: String) { header.append(contentsOf: s.utf8) }
        func appendU32(_ v: UInt32) {
            header.append(UInt8(v & 0xFF))
            header.append(UInt8((v >> 8) & 0xFF))
            header.append(UInt8((v >> 16) & 0xFF))
            header.append(UInt8((v >> 24) & 0xFF))
        }
        func appendU16(_ v: UInt16) {
            header.append(UInt8(v & 0xFF))
            header.append(UInt8((v >> 8) & 0xFF))
        }
        let byteRate = Self.sampleRate * UInt32(Self.channels) * UInt32(Self.bitsPerSample) / 8
        let blockAlign = Self.channels * Self.bitsPerSample / 8
        appendString("RIFF")
        appendU32(36 &+ dataBytes)
        appendString("WAVE")
        appendString("fmt ")
        appendU32(16)
        appendU16(1) // PCM
        appendU16(Self.channels)
        appendU32(Self.sampleRate)
        appendU32(byteRate)
        appendU16(blockAlign)
        appendU16(Self.bitsPerSample)
        appendString("data")
        appendU32(dataBytes)
        return header
    }
}

/// 录音中心（单例）：两种音频传输（云端 IPhoneCloudCallSession / 本地 AudioSessionController）
/// 共用一个录音器。任一通话音频路径只需在上下行 PCM 处调用 write，start/stop 由 AppModel 控制，
/// 通话结束时各音频类的 teardown 调用 finishIfRecording() 自动收尾一次。
final class CallRecordingCenter: @unchecked Sendable {
    static let shared = CallRecordingCenter()

    private let lock = NSLock()
    private var recorder: CallRecorder?

    private init() {}

    /// 开始录音；已有录音在进行时直接返回 true。
    @discardableResult
    func start() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if recorder != nil { return true }
        guard let created = CallRecorder() else { return false }
        recorder = created
        return true
    }

    func writeUplink(_ pcm: Data) {
        lock.lock()
        let current = recorder
        lock.unlock()
        current?.writeUplink(pcm)
    }

    func writeDownlink(_ pcm: Data) {
        lock.lock()
        let current = recorder
        lock.unlock()
        current?.writeDownlink(pcm)
    }

    /// 停止录音并返回文件 URL；没有录音时返回 nil。
    func stop() -> URL? {
        lock.lock()
        let current = recorder
        recorder = nil
        lock.unlock()
        return current?.stop()
    }

    var isRecording: Bool {
        lock.lock()
        defer { lock.unlock() }
        return recorder != nil
    }

    /// 通话 teardown 路径调用：若正在录音则收尾并通知 UI 弹出分享。
    func finishIfRecording() {
        if let url = stop() {
            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: .uPhoneCallRecordingFinished,
                    object: nil,
                    userInfo: ["url": url]
                )
            }
        }
    }
}
