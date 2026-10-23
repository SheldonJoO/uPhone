package com.airsim.bridge;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PhoneAudioBridge {
    enum PlaybackRoute {
        SAMSUNG_TAG,
        TELEPHONY_DEVICE,
        UNSUPPORTED
    }

    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int ROUTE_VERIFY_TIMEOUT_MS = 2000;

    private PhoneAudioBridge() {}

    public static void main(String[] argv) {
        long started = SystemClock.elapsedRealtime();
        BridgeStats stats = new BridgeStats();
        try {
            BridgeArguments arguments = BridgeArguments.parse(argv);
            serve(arguments, started, stats);
        } catch (Throwable error) {
            emit(started, "bridge_error", Map.of(
                "error_class", error.getClass().getName(),
                "error_message", safeMessage(error),
                "result", "error"));
            error.printStackTrace(System.err);
            System.exit(2);
        }
    }

    static int captureSource() {
        return 3;
    }

    static int playbackUsage() {
        return AudioAttributes.USAGE_VOICE_COMMUNICATION;
    }

    static String[] playbackTags() {
        return new String[] {"VOICE_TX"};
    }

    static PlaybackRoute playbackRoute(
            boolean samsungTagAvailable, boolean telephonyDeviceAvailable) {
        if (samsungTagAvailable) return PlaybackRoute.SAMSUNG_TAG;
        if (telephonyDeviceAvailable) return PlaybackRoute.TELEPHONY_DEVICE;
        return PlaybackRoute.UNSUPPORTED;
    }

    static long listenerRetryDelayMillis(int consecutiveFailures) {
        int boundedFailures = Math.max(1, Math.min(consecutiveFailures, 20));
        return Math.min(5000L, boundedFailures * 250L);
    }

    static boolean voiceTxRouteTimedOut(
            boolean routeVerified, long firstPlaybackAt, long now) {
        return !routeVerified
            && firstPlaybackAt != 0
            && now - firstPlaybackAt >= ROUTE_VERIFY_TIMEOUT_MS;
    }

    private static void serve(BridgeArguments arguments, long started, BridgeStats stats) {
        BridgeSessionGate gate = new BridgeSessionGate();
        int consecutiveFailures = 0;
        while (true) {
            try {
                InetAddress address = resolveListenAddress(arguments);
                try (ServerSocket server = new ServerSocket()) {
                    server.setReuseAddress(true);
                    server.bind(new InetSocketAddress(address, arguments.listenPort()), 4);
                    consecutiveFailures = 0;
                    emit(started, "bridge_listening", Map.of(
                        "listen_host", address.getHostAddress(),
                        "listen_interface", arguments.listenInterface() == null ? "explicit" : arguments.listenInterface(),
                        "listen_port", arguments.listenPort(),
                        "model", Build.MODEL,
                        "sample_rate", SAMPLE_RATE,
                        "frame_bytes", BridgeProtocol.FRAME_BYTES,
                        "result", "ok"));
                    while (!server.isClosed()) {
                        Socket client = server.accept();
                        if (!gate.tryAcquire(client)) {
                            stats.recordRejectedClient();
                            emit(started, "client_rejected", statsFields(stats.snapshot()));
                            client.close();
                            continue;
                        }
                        Thread session = new Thread(() -> {
                            try {
                                handleClient(client, started, stats);
                            } finally {
                                gate.release(client);
                            }
                        }, "airsim-phone-audio-session");
                        session.start();
                    }
                }
            } catch (IOException error) {
                consecutiveFailures++;
                long retryDelay = listenerRetryDelayMillis(consecutiveFailures);
                emit(started, "listener_restarting", Map.of(
                    "attempt", consecutiveFailures,
                    "error_class", error.getClass().getName(),
                    "error_message", safeMessage(error),
                    "retry_delay_ms", retryDelay,
                    "result", "retrying"));
                SystemClock.sleep(retryDelay);
            }
        }
    }

    private static InetAddress resolveListenAddress(BridgeArguments arguments) throws IOException {
        if (arguments.listenHost() != null) return InetAddress.getByName(arguments.listenHost());
        NetworkInterface network = NetworkInterface.getByName(arguments.listenInterface());
        if (network == null || !network.isUp() || network.isLoopback()) {
            throw new IOException("AVF interface unavailable: " + arguments.listenInterface());
        }
        Enumeration<InetAddress> addresses = network.getInetAddresses();
        while (addresses.hasMoreElements()) {
            InetAddress address = addresses.nextElement();
            if (address.getAddress().length == 4 && address.isSiteLocalAddress()) return address;
        }
        throw new IOException("AVF private IPv4 unavailable: " + arguments.listenInterface());
    }

    private static void handleClient(Socket client, long started, BridgeStats stats) {
        try (client) {
            client.setKeepAlive(true);
            client.setTcpNoDelay(true);
            BridgeProtocol.acceptHandshake(client.getInputStream(), client.getOutputStream());
            emit(started, "client_ready", Map.of("result", "ok"));
            streamCallAudio(client, started, stats);
        } catch (Throwable error) {
            emit(started, "session_error", Map.of(
                "error_class", error.getClass().getName(),
                "error_message", safeMessage(error),
                "result", "error"));
        } finally {
            emit(started, "client_closed", statsFields(stats.snapshot()));
        }
    }

    private static void streamCallAudio(Socket client, long started, BridgeStats stats)
            throws Exception {
        int recordBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING);
        int trackBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING);
        if (recordBufferSize <= 0 || trackBufferSize <= 0) {
            throw new IllegalStateException("invalid Android audio buffer sizes");
        }

        AudioRecord recorder = new AudioRecord(
            captureSource(), SAMPLE_RATE, CHANNEL_IN, ENCODING,
            Math.max(recordBufferSize, BridgeProtocol.FRAME_BYTES * 10));
        AudioAttributes.Builder attributeBuilder = new AudioAttributes.Builder()
            .setUsage(playbackUsage())
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH);
        boolean samsungTagAvailable = tryAddSamsungVoiceTxTag(attributeBuilder);
        AudioDeviceInfo telephonyTx = samsungTagAvailable ? null : findTelephonyTxDevice();
        PlaybackRoute playbackRoute = playbackRoute(
            samsungTagAvailable, telephonyTx != null);
        // 非三星设备常既无 sem VOICE_TX tag、也枚举不到 TYPE_TELEPHONY 输出设备。
        // 旧逻辑在此直接抛异常，导致 VOICE_DOWNLINK 下行还没开始采就整体失败、
        // 云端/VoWLAN 上下行全灭（接听无声）。改为降级：用默认输出路由建 track，
        // 至少保证下行能采到并送达 iPhone；上行路由稍后在 playUplink 里软降级。（2026-10-23 修）
        AudioTrack track = new AudioTrack.Builder()
            .setAudioAttributes(attributeBuilder.build())
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_OUT)
                .build())
            .setBufferSizeInBytes(Math.max(trackBufferSize, BridgeProtocol.FRAME_BYTES * 10))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();
        try {
            if (playbackRoute == PlaybackRoute.TELEPHONY_DEVICE
                    && !track.setPreferredDevice(telephonyTx)) {
                throw new IllegalStateException("Android Telephony Tx route was rejected");
            }
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("VOICE_DOWNLINK AudioRecord initialization failed");
            }
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("VOICE_TX AudioTrack initialization failed");
            }
            emit(started, "audio_initialized", Map.of(
                "audio_session_id", recorder.getAudioSessionId(),
                "capture_source", "voice_downlink",
                "playback_route", playbackRoute.name().toLowerCase(),
                "record_buffer_bytes", recordBufferSize,
                "track_buffer_bytes", trackBufferSize,
                "result", "ok"));

            recorder.startRecording();
            track.play();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("VOICE_DOWNLINK AudioRecord did not start");
            }

            AtomicBoolean running = new AtomicBoolean(true);
            Runnable stop = () -> {
                if (running.compareAndSet(true, false)) {
                    try {
                        client.close();
                    } catch (IOException ignored) {
                    }
                }
            };
            Thread downlink = new Thread(
                () -> captureDownlink(recorder, client, stats, running, stop),
                "airsim-phone-downlink");
            Thread uplink = new Thread(
                () -> playUplink(track, client, started, stats, running, stop),
                "airsim-phone-uplink");
            Thread reporter = new Thread(
                () -> reportStats(started, stats, running),
                "airsim-phone-stats");
            downlink.start();
            uplink.start();
            reporter.start();
            downlink.join();
            uplink.join();
            running.set(false);
            reporter.interrupt();
            reporter.join();
        } finally {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop();
            }
            if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop();
            }
            recorder.release();
            track.release();
        }
    }

    private static void captureDownlink(
            AudioRecord recorder,
            Socket client,
            BridgeStats stats,
            AtomicBoolean running,
            Runnable stop) {
        byte[] frame = new byte[BridgeProtocol.FRAME_BYTES];
        try {
            OutputStream output = client.getOutputStream();
            while (running.get()) {
                int count = recorder.read(frame, 0, frame.length, AudioRecord.READ_BLOCKING);
                if (count < 0) {
                    throw new IOException("AudioRecord read failed: " + count);
                }
                int evenCount = count & ~1;
                if (evenCount == 0) {
                    continue;
                }
                output.write(frame, 0, evenCount);
                stats.recordDownlink(frame, evenCount);
            }
        } catch (Throwable error) {
            if (running.get()) {
                emit(0, "downlink_error", Map.of(
                    "error_class", error.getClass().getName(),
                    "error_message", safeMessage(error)));
            }
        } finally {
            Arrays.fill(frame, (byte) 0);
            stop.run();
        }
    }

    private static void playUplink(
            AudioTrack track,
            Socket client,
            long started,
            BridgeStats stats,
            AtomicBoolean running,
            Runnable stop) {
        byte[] frame = new byte[BridgeProtocol.FRAME_BYTES];
        long lastWriteAt = 0;
        long writtenFrames = 0;
        boolean routeVerified = false;
        boolean routeWarned = false;
        long firstPlaybackAt = 0;
        try {
            client.setSoTimeout(250);
            InputStream input = client.getInputStream();
            while (running.get()) {
                int count;
                try {
                    count = readFrame(input, frame);
                } catch (SocketTimeoutException timeout) {
                    continue;
                }
                if (count < 0) {
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                if (firstPlaybackAt == 0) {
                    firstPlaybackAt = now;
                }
                // 上行 10-15 秒固定延迟根治（2026-10-23 修）：网络抖动把若干帧攒成突发送达时，
                // WRITE_BLOCKING 逐帧实时播出会把积压永久固化成固定延迟（=localhost TCP 缓冲
                // 的容量，恰好 10-15 秒），且一旦形成只会越来越大、全程不恢复。
                // 改为「限速丢弃」：两次写入至少间隔 15ms（≤50 帧/秒 = 实时节奏），突发中
                // 的陈旧帧直接丢弃；AudioTrack 缓冲放不下的帧也丢弃。延迟收敛在一个音频缓冲内。
                if (now - lastWriteAt >= 15) {
                    long head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                    int bufferedFrames = (int) (writtenFrames - head);
                    int frameFrames = count / 2;
                    if (frameFrames <= track.getBufferSizeInFrames() - bufferedFrames) {
                        int written = track.write(frame, 0, count, AudioTrack.WRITE_BLOCKING);
                        if (written < 0) {
                            throw new IOException("AudioTrack write failed: " + written);
                        }
                        if (written > 0) {
                            writtenFrames += written / 2;
                            lastWriteAt = SystemClock.elapsedRealtime();
                        }
                    }
                }
                stats.recordUplink(frame, count);
                AudioDeviceInfo routed = track.getRoutedDevice();
                if (!routeVerified && routed != null
                        && routed.getType() == AudioDeviceInfo.TYPE_TELEPHONY) {
                    routeVerified = true;
                    emit(started, "voice_tx_route_verified", Map.of(
                        "device_id", routed.getId(),
                        "device_type", routed.getType(),
                        "result", "ok"));
                }
                // 路由超时只告警、绝不再退出会话（2026-10-23 修）：旧逻辑在 2 秒内验证不到
                // TYPE_TELEPHONY 就 return，本套接字从此无人读取，上游 readLoop 写上行 PCM
                // 在 TCP 缓冲填满后永久阻塞，连带拒接/挂断/状态消息全部卡死、下行也被拖垮。
                if (!routeVerified && !routeWarned && voiceTxRouteTimedOut(
                        routeVerified, firstPlaybackAt, SystemClock.elapsedRealtime())) {
                    routeWarned = true;
                    emit(started, "voice_tx_route_unverified", Map.of("result", "keep_draining"));
                }
            }
        } catch (Throwable error) {
            if (running.get()) {
                emit(started, "uplink_error", Map.of(
                    "error_class", error.getClass().getName(),
                    "error_message", safeMessage(error)));
            }
        } finally {
            Arrays.fill(frame, (byte) 0);
            stop.run();
        }
    }

    private static int readFrame(InputStream input, byte[] frame) throws IOException {
        int offset = 0;
        while (offset < frame.length) {
            int count = input.read(frame, offset, frame.length - offset);
            if (count < 0) {
                return offset == 0 ? -1 : offset;
            }
            if (count > 0) {
                offset += count;
            }
        }
        return offset;
    }

    private static boolean tryAddSamsungVoiceTxTag(AudioAttributes.Builder builder) {
        try {
            Method addTag = AudioAttributes.Builder.class.getMethod(
                "semAddAudioTag", String.class);
            for (String tag : playbackTags()) {
                addTag.invoke(builder, tag);
            }
            return true;
        } catch (NoSuchMethodException | IllegalAccessException
                | InvocationTargetException error) {
            return false;
        }
    }

    private static AudioDeviceInfo findTelephonyTxDevice() {
        try {
            Method getDevices = AudioManager.class.getMethod("getDevicesStatic", int.class);
            AudioDeviceInfo[] devices = (AudioDeviceInfo[]) getDevices.invoke(
                null, AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device : devices) {
                if (device.isSink() && device.getType() == AudioDeviceInfo.TYPE_TELEPHONY) {
                    return device;
                }
            }
        } catch (NoSuchMethodException | IllegalAccessException
                | InvocationTargetException | ClassCastException error) {
            return null;
        }
        return null;
    }

    private static void reportStats(long started, BridgeStats stats, AtomicBoolean running) {
        while (running.get()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (running.get()) {
                emit(started, "pcm_stats", statsFields(stats.snapshot()));
            }
        }
    }

    private static Map<String, Object> statsFields(BridgeStats.Snapshot snapshot) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("downlink_bytes", snapshot.downlinkBytes());
        fields.put("downlink_frames", snapshot.downlinkFrames());
        fields.put("downlink_peak", snapshot.downlinkPeak());
        fields.put("uplink_bytes", snapshot.uplinkBytes());
        fields.put("uplink_frames", snapshot.uplinkFrames());
        fields.put("uplink_peak", snapshot.uplinkPeak());
        fields.put("rejected_clients", snapshot.rejectedClients());
        return fields;
    }

    private static synchronized void emit(
            long started,
            String event,
            Map<String, ?> extra) {
        Map<String, Object> fields = new TreeMap<>();
        fields.put("component", "android_audio_bridge");
        fields.put("elapsed_ms", started == 0 ? 0 : SystemClock.elapsedRealtime() - started);
        fields.put("event", event);
        fields.put("pid", Process.myPid());
        fields.put("protocol", "airsimpcm1");
        fields.put("sdk", Build.VERSION.SDK_INT);
        fields.put("uid", Process.myUid());
        fields.putAll(extra);
        System.out.println(json(fields));
    }

    private static String json(Map<String, ?> fields) {
        StringBuilder output = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            if (!first) {
                output.append(',');
            }
            first = false;
            output.append('"').append(escape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                output.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                output.append(value);
            } else {
                output.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        return output.append('}').toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
    }
}
