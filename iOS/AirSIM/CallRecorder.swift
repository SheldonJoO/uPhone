import Foundation

/// iOS 端通话录音：在传输层抓取双向 8kHz 16-bit PCM（上行=本机麦克风，下行=远端声音），
/// 写入立体声 WAV（左声道=本机，右声道=远端），保存在 Documents/CallRecordings。
///
/// 线程模型（2026-10-14 重设计，替代之前的逐回调交错方案）：
/// - 实时音频线程（tap 回调）永不拿锁、永不阻塞：CallRecordingCenter 的 write
///   路径用 lock.try()，拿不到锁就丢这一帧（约 120ms），绝不在实时线程上等锁。
///   之前闪退的根因就是实时线程拿 NSLock：锁持有者在文件 IO 时被抢占，
///   实时线程优先级反转，被系统看门狗杀进程（必须重开 App）。
/// - 所有可变状态收敛在一条专用串行后台队列上；混音只在该队列批量做
///   （每 2 秒交错落盘一次），stop() 时收尾并补零对齐。内存有界（每侧 ≤30 秒）。
final class CallRecorder: @unchecked Sendable {
    static let sampleRate: UInt32 = 8_000
    static let channels: UInt16 = 2
    static let bitsPerSample: UInt16 = 16
    /// 每侧缓冲上限 30 秒（内存有界）；对齐部分每 2 秒批量落盘一次。
    private static let maxBufferedSeconds = 30
    private static let flushSeconds = 2

    private let queue = DispatchQueue(label: "uphone.callrecorder", qos: .utility)
    private var handle: FileHandle?
    private var up = Data()
    private var down = Data()
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
        formatter.dateFormat = "yyyyMMdd_HHmmss_SSS"
        let stamp = formatter.string(from: Date())
        fileURL = dir.appendingPathComponent("uPhone_\(stamp).wav")
        guard FileManager.default.createFile(atPath: fileURL.path, contents: wavHeader(dataBytes: 0), attributes: nil),
              let handle = try? FileHandle(forWritingTo: fileURL) else {
            return nil
        }
        self.handle = handle
    }

    /// 写入上行（本机麦克风）16-bit PCM mono。只做 async 入队，实时线程安全。
    func writeUplink(_ pcm: Data) {
        guard !pcm.isEmpty else { return }
        queue.async { [weak self] in self?.append(pcm, uplink: true) }
    }

    /// 写入下行（远端声音）16-bit PCM mono。只做 async 入队，实时线程安全。
    func writeDownlink(_ pcm: Data) {
        guard !pcm.isEmpty else { return }
        queue.async { [weak self] in self?.append(pcm, uplink: false) }
    }

    private func append(_ pcm: Data, uplink: Bool) {
        dispatchPrecondition(condition: .onQueue(queue))
        guard !stopped, handle != nil else { return }
        var chunk = pcm
        if chunk.count % 2 == 1 { chunk.removeLast() }  // 保证 16-bit 对齐
        if uplink { up.append(chunk) } else { down.append(chunk) }
        // 内存有界：每侧最多保留 30 秒
        let cap = Int(Self.sampleRate) * 2 * Self.maxBufferedSeconds
        if up.count > cap { up.removeFirst(up.count - cap) }
        if down.count > cap { down.removeFirst(down.count - cap) }
        // 批量落盘：两侧对齐部分攒够 2 秒写一次
        let frames = min(up.count, down.count) / 2
        guard frames >= Int(Self.sampleRate) * Self.flushSeconds else { return }
        writeInterleaved(frames: frames)
        up.removeFirst(frames * 2)
        down.removeFirst(frames * 2)
    }

    /// 按帧交错写入（左=上行，右=下行）。调用方保证 up/down 各至少 frames 帧。
    private func writeInterleaved(frames: Int) {
        dispatchPrecondition(condition: .onQueue(queue))
        guard frames > 0 else { return }
        var out = Data(count: frames * 4)
        out.withUnsafeMutableBytes { destRaw in
            up.withUnsafeBytes { upRaw in
                down.withUnsafeBytes { downRaw in
                    let dest = destRaw.bindMemory(to: UInt8.self)
                    let ub = upRaw.bindMemory(to: UInt8.self)
                    let db = downRaw.bindMemory(to: UInt8.self)
                    for i in 0..<frames {
                        let o = i * 4, s = i * 2
                        dest[o] = ub[s]
                        dest[o + 1] = ub[s + 1]
                        dest[o + 2] = db[s]
                        dest[o + 3] = db[s + 1]
                    }
                }
            }
        }
        writeData(out)
    }

    private func writeData(_ data: Data) {
        dispatchPrecondition(condition: .onQueue(queue))
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

    /// 结束录音：等待队列排空、尾部补零对齐、回填 WAV 头长度，返回文件 URL。
    /// 数据不足 1 秒视为无效录音，删除文件并返回 nil。
    func stop() -> URL? {
        queue.sync {
            dispatchPrecondition(condition: .onQueue(self.queue))
            guard !stopped else { return nil }
            stopped = true
            // 尾部：两侧按帧对齐，不足的声道补零
            let upFrames = up.count / 2
            let downFrames = down.count / 2
            let frames = max(upFrames, downFrames)
            if frames > 0 {
                var out = Data(count: frames * 4)
                out.withUnsafeMutableBytes { destRaw in
                    up.withUnsafeBytes { upRaw in
                        down.withUnsafeBytes { downRaw in
                            let dest = destRaw.bindMemory(to: UInt8.self)
                            let ub = upRaw.bindMemory(to: UInt8.self)
                            let db = downRaw.bindMemory(to: UInt8.self)
                            for i in 0..<frames {
                                let o = i * 4, s = i * 2
                                if i < upFrames {
                                    dest[o] = ub[s]
                                    dest[o + 1] = ub[s + 1]
                                }
                                if i < downFrames {
                                    dest[o + 2] = db[s]
                                    dest[o + 3] = db[s + 1]
                                }
                            }
                        }
                    }
                }
                writeData(out)
            }
            up.removeAll(keepingCapacity: false)
            down.removeAll(keepingCapacity: false)
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
///
/// 注意：writeUplink/writeDownlink 会被实时音频线程（tap 回调）调用，
/// 因此内部用 lock.try() 永不阻塞；start/stop/isRecording 只在主线程/后台线程调用，用普通 lock。
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

    /// 实时线程调用：tryLock 拿不到就丢这一帧，绝不阻塞。
    func writeUplink(_ pcm: Data) {
        guard lock.try() else { return }
        defer { lock.unlock() }
        recorder?.writeUplink(pcm)
    }

    /// 实时线程调用：tryLock 拿不到就丢这一帧，绝不阻塞。
    func writeDownlink(_ pcm: Data) {
        guard lock.try() else { return }
        defer { lock.unlock() }
        recorder?.writeDownlink(pcm)
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
