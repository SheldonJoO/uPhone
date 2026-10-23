package com.airsim.phonecontrol;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the single cloud call and bridges Relay WebSocket media to loopback Shizuku PCM. */
final class StandaloneCloudMediaManager implements Closeable {
    private static volatile StandaloneCloudMediaManager instance;

    static StandaloneCloudMediaManager get(android.content.Context context) {
        StandaloneCloudMediaManager value = instance;
        if (value == null) {
            synchronized (StandaloneCloudMediaManager.class) {
                value = instance;
                if (value == null) instance = value = new StandaloneCloudMediaManager(context);
            }
        }
        return value;
    }

    private final android.content.Context context;
    private final ExecutorService sessions = Executors.newCachedThreadPool();
    private final Object lock = new Object();
    private CloudSession active;

    private StandaloneCloudMediaManager(android.content.Context context) {
        this.context = context.getApplicationContext();
    }

    void startOutgoing(JSONObject call) {
        if (call == null) return;
        try {
            // 媒体走生效 Relay：自定义地址优先，否则用呼叫自带的 relay_url。
            String custom = AppConfig.customRelayUrl(context);
            String relayUrl = custom.isEmpty() ? call.optString("relay_url") : custom;
            start(Descriptor.from(call, relayUrl));
        }
        catch (Exception error) { BridgeLog.error("standalone_outgoing_media_invalid", error); }
    }

    void handleCallEvent(JSONObject registration, JSONObject event, StandaloneRelayClient relay) throws Exception {
        String state = event.optString("state", "");
        String direction = event.optString("direction", "");
        String androidID = event.optString("call_id", "");
        Descriptor descriptor;
        synchronized (lock) {
            descriptor = active == null ? null : active.descriptor;
        }
        if ("incoming".equals(direction) && "incoming".equals(state) && descriptor == null) {
            descriptor = Descriptor.incoming(registration, androidID,
                    AppConfig.effectiveRelayUrl(context, registration));
            JSONObject payload = identity(registration).put("event", "incoming_call")
                    .put("call_id", descriptor.callID).put("call_uuid", descriptor.callUUID)
                    .put("generation", descriptor.generation).put("call_secret", descriptor.secret)
                    .put("number", event.optString("number", "")).put("caller_name", "")
                    .put("issued_at", Instant.now().toString())
                    .put("expires_at", Instant.now().plusSeconds(90).toString())
                    .put("media_transport", "legacy_pcm");
            relay.postJSON(registration, "/v1/events/call", payload, 15_000);
            start(descriptor);
        } else if (descriptor != null && descriptor.androidID.isEmpty()
                && "outgoing".equals(direction)) {
            descriptor.androidID = androidID;
        }
        if (descriptor != null) {
            String phase = phase(state);
            if (!phase.isEmpty()) {
                JSONObject lifecycle = identity(registration).put("event", "call_state")
                        .put("call_id", descriptor.callID).put("call_uuid", descriptor.callUUID)
                        .put("generation", descriptor.generation).put("phase", phase)
                        .put("source", "agent").put("timestamp", Instant.now().toString())
                        .put("trace_id", "standalone-" + descriptor.callUUID + "-" + phase);
                relay.postJSON(registration, "/v1/events/call-state", lifecycle, 12_000);
            }
            if ("ended".equals(state)) closeActive(descriptor);
        }
    }

    private void start(Descriptor descriptor) {
        synchronized (lock) {
            if (active != null && active.descriptor.same(descriptor)) return;
            if (active != null) active.close();
            active = new CloudSession(descriptor);
            // 云端通话开始即确保本地 PCM 桥（Shizuku 特权进程）已拉起：否则 runPCMLocked 连不上
            // 127.0.0.1:7580，pcm 永远为 null，下行送不出去、上行被丢弃，整通通话上下行全灭。
            // 这是 iOS 接听后「上下行全静音但推送/振铃正常」的根因前置之一。（2026-10-23 补）
            try { ShizukuBridgeManager.get(context).ensureStarted(); }
            catch (Throwable ignored) { BridgeLog.error("standalone_cloud_bridge_ensure_failed", ignored); }
            // 云端通话期间本机必须静默：不依赖 InCallService（默认拨号角色丢失 /
            // 服务被回收时它收不到 call），由媒体会话自己保持静音原因。
            LocalCallAudioController.get(context).retain("cloud-media");
            sessions.execute(active);
        }
    }

    private void closeActive(Descriptor descriptor) {
        synchronized (lock) {
            if (active != null && active.descriptor.same(descriptor)) {
                active.close();
                active = null;
                LocalCallAudioController.get(context).release("cloud-media");
            }
        }
    }

    private final class CloudSession implements Runnable, Closeable {
        final Descriptor descriptor;
        volatile boolean running = true;
        volatile StandaloneWebSocket webSocket;
        volatile Socket pcm;
        volatile String mediaProtocol = "";
        volatile boolean mediaStarted;
        volatile boolean pcmLoopRunning;
        int sequence;
        // 上行重成帧缓冲：中继二进制帧写入前先按 320 字节对齐，杜绝 7580 流错位。（2026-10-24）
        private final ByteArrayOutputStream pendingUplink = new ByteArrayOutputStream();

        CloudSession(Descriptor descriptor) { this.descriptor = descriptor; }

        @Override public void run() {
            while (running) {
                try {
                    URI endpoint = URI.create(descriptor.relayURL.replaceFirst("^https", "wss")
                            + "/v1/calls/" + descriptor.callUUID + "/connect?role=agent&token="
                            + URLEncoder.encode(descriptor.secret, StandardCharsets.UTF_8));
                    webSocket = StandaloneWebSocket.connect(endpoint, "");
                    webSocket.writeText(status("agent_ready", ""));
                    BridgeLog.info("standalone_cloud_media_connected call_uuid="
                            + DebugRedactor.safeIdentifier(descriptor.callUUID));
                    readLoop();
                } catch (Exception error) {
                    if (running) BridgeLog.error("standalone_cloud_media_disconnected", error);
                } finally {
                    closeSocket(webSocket);
                    webSocket = null;
                    closeSocket(pcm);
                    pcm = null;
                }
                if (running) {
                    try { Thread.sleep(1_000); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            }
        }

        private void readLoop() throws Exception {
            while (running && webSocket != null) {
                StandaloneWebSocket.Message message = webSocket.read();
                if (message.opcode() == 2) {
                    // 上行（iPhone 用户语音 → 其他方）：先解码再按 320 字节严格重新成帧写入本地桥。
                    // 旧逻辑直接把原始 payload 写进 127.0.0.1:7580，一旦中继把多帧合并成一个 WS 二进制
                    // 消息，7580 流里就会出现「非 320 整数倍」的字节错位，AudioTrack 按 16-bit 采样解析
                    // 后整条上行变成杂音。这里与 downlink 的 pipeDownlink 对称，保证桥上永远是 320 对齐帧。（2026-10-24 修）
                    if (mediaStarted && pcm != null) writeUplink(decodePCM(message.payload()));
                    continue;
                }
                JSONObject control = new JSONObject(new String(message.payload(), StandardCharsets.UTF_8));
                String action = control.optString("action", "");
                if (!"answer".equals(action) && !"reject".equals(action) && !"end".equals(action)) continue;
                mediaProtocol = control.optString("media_protocol", "");
                JSONObject command = new JSONObject().put("command_id",
                                control.optString("command_id", UUID.randomUUID().toString()))
                        .put("type", "call_control").put("action", action);
                JSONObject result = StandaloneAgentGateway.get(context).executeCloudCommand(command);
                if (!"completed".equals(result.optString("status"))) {
                    webSocket.writeText(status("action_error", result.optString("error", "Telecom command failed")));
                    continue;
                }
                if ("answer".equals(action)) {
                    webSocket.writeText(status("answer_ok", ""));
                    mediaStarted = true;
                    // PCM 桥死亡（媒体 WS 重连后 pcm 已被 finally 关闭）时必须能重启，
                    // 否则重连后的会话上下行全灭、状态也不再转发。（2026-10-23 修）
                    if (pcm == null && !pcmLoopRunning) {
                        sessions.execute(this::runPCM);
                    }
                } else {
                    webSocket.writeText(status("reject".equals(action) ? "rejected" : "ended", ""));
                    running = false;
                }
            }
        }

        private void runPCM() {
            pcmLoopRunning = true;
            try {
                runPCMLocked();
            } finally {
                pcmLoopRunning = false;
            }
        }

        private void runPCMLocked() {
            // 整个通话期间持续保活 PCM 桥：媒体 WS 抖动/重连时 pcm 会被 CloudSession.run 的
            // finally 关闭，这里循环重连本地 127.0.0.1:7580 桥，直到通话结束（running=false）
            // 或 mediaStarted 被复位。否则 WS 一断、pcm 一关，上下行全灭、状态也不再转发。
            // 旧逻辑有 45 秒上限，长通话中途 WS 重连后 pcm 再掉就再也接不回来。（2026-10-23 再修）
            while (running && mediaStarted) {
                try {
                    Socket connection = new Socket();
                    connection.connect(new InetSocketAddress("127.0.0.1", 7580), 2_000);
                    connection.setTcpNoDelay(true);
                    connection.setSoTimeout(0);
                    OutputStream output = connection.getOutputStream();
                    output.write("AIRSIMPCM1\n".getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    byte[] ready = connection.getInputStream().readNBytes(11);
                    if (!"AIRSIMREADY".equals(new String(ready, StandardCharsets.US_ASCII))) {
                        connection.close();
                        throw new java.io.IOException("PCM handshake failed");
                    }
                    pcm = connection;
                    webSocket.writeText(status("pcm_listening", ""));
                    webSocket.writeText(status("active", ""));
                    webSocket.writeText(status("pcm_ready", ""));
                    pipeDownlink(connection.getInputStream());
                } catch (Exception error) {
                    closeSocket(pcm);
                    pcm = null;
                    if (running) BridgeLog.error("standalone_pcm_bridge_failed", error);
                }
                if (running) {
                    try { Thread.sleep(750); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            }
        }

        private void pipeDownlink(InputStream input) throws Exception {
            ByteArrayOutputStream pending = new ByteArrayOutputStream();
            byte[] chunk = new byte[2048];
            boolean first = true;
            while (running && mediaStarted) {
                int count = input.read(chunk);
                if (count < 0) return;
                pending.write(chunk, 0, count);
                byte[] bytes = pending.toByteArray();
                int offset = 0;
                while (bytes.length - offset >= 320) {
                    byte[] frame = java.util.Arrays.copyOfRange(bytes, offset, offset + 320);
                    webSocket.writeBinary(encodePCM(frame));
                    if (first) { first = false; webSocket.writeText(status("pcm_first_frame", "")); }
                    offset += 320;
                }
                pending.reset();
                if (offset < bytes.length) pending.write(bytes, offset, bytes.length - offset);
            }
        }

        // 上行重成帧：把中继二进制帧累积后按 320 字节对齐写进本地桥。这样无论中继把多少帧合并成
        // 一个 WS 消息，7580 流里永远是 320 整数倍字节，AudioTrack 解析不会错位成杂音。（2026-10-24）
        private void writeUplink(byte[] payload) throws Exception {
            if (pcm == null) return;
            pendingUplink.write(payload, 0, payload.length);
            byte[] bytes = pendingUplink.toByteArray();
            int offset = 0;
            while (bytes.length - offset >= 320) {
                byte[] frame = java.util.Arrays.copyOfRange(bytes, offset, offset + 320);
                pcm.getOutputStream().write(frame);
                offset += 320;
            }
            pendingUplink.reset();
            if (offset < bytes.length) pendingUplink.write(bytes, offset, bytes.length - offset);
        }

        private byte[] encodePCM(byte[] payload) {
            if (!"airsim-pcm-v1".equalsIgnoreCase(mediaProtocol)) return payload;
            ByteBuffer wire = ByteBuffer.allocate(20 + payload.length).order(ByteOrder.BIG_ENDIAN);
            wire.put(new byte[]{'A', 'S', 'P', 'M'}).put((byte) 1).put((byte) 0).putShort((short) 20)
                    .putInt(sequence++).putLong(System.currentTimeMillis()).put(payload);
            return wire.array();
        }

        private byte[] decodePCM(byte[] wire) throws Exception {
            if (wire.length < 4 || wire[0] != 'A' || wire[1] != 'S' || wire[2] != 'P' || wire[3] != 'M') return wire;
            if (wire.length != 340 || wire[4] != 1 || ByteBuffer.wrap(wire, 6, 2).order(ByteOrder.BIG_ENDIAN).getShort() != 20) {
                throw new java.io.IOException("AirSIM PCM frame invalid");
            }
            return java.util.Arrays.copyOfRange(wire, 20, wire.length);
        }

        private String status(String value, String message) throws Exception {
            JSONObject result = new JSONObject().put("status", value).put("call_id", descriptor.callID)
                    .put("call_uuid", descriptor.callUUID).put("generation", descriptor.generation);
            if (!message.isEmpty()) result.put("message", message);
            return result.toString();
        }

        @Override public void close() {
            running = false;
            closeSocket(webSocket);
            closeSocket(pcm);
        }
    }

    private static final class Descriptor {
        final String callID;
        final String callUUID;
        final long generation;
        final String secret;
        final String relayURL;
        final String direction;
        String androidID;

        Descriptor(String callID, String callUUID, long generation, String secret,
                   String relayURL, String direction, String androidID) {
            this.callID = callID;
            this.callUUID = callUUID;
            this.generation = generation <= 0 ? 1 : generation;
            this.secret = secret;
            this.relayURL = relayURL;
            this.direction = direction;
            this.androidID = androidID;
        }

        static Descriptor from(JSONObject call, String relayUrl) {
            Descriptor result = new Descriptor(call.optString("call_id"), call.optString("call_uuid"),
                    call.optLong("generation", 1), call.optString("call_secret"),
                    relayUrl, call.optString("direction", "outgoing"), "");
            result.validate();
            return result;
        }

        static Descriptor incoming(JSONObject registration, String androidID, String relayUrl) {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            StringBuilder encoded = new StringBuilder(64);
            for (byte value : secret) encoded.append(String.format("%02x", value & 0xff));
            Descriptor result = new Descriptor("android-" + System.currentTimeMillis(), UUID.randomUUID().toString(), 1,
                    encoded.toString(), relayUrl, "incoming", androidID);
            result.validate();
            return result;
        }

        private void validate() {
            if (callID.isBlank() || callUUID.isBlank() || secret.isBlank()) {
                throw new IllegalArgumentException("云端通话描述缺少身份或密钥");
            }
            URI relay = URI.create(relayURL);
            if (!"https".equalsIgnoreCase(relay.getScheme()) || relay.getHost() == null
                    || relay.getRawUserInfo() != null) {
                throw new IllegalArgumentException("云端通话 Relay 必须是无凭据的 HTTPS 地址");
            }
        }

        boolean same(Descriptor other) { return other != null && callUUID.equalsIgnoreCase(other.callUUID); }
    }

    private static JSONObject identity(JSONObject registration) throws Exception {
        return new JSONObject().put("device_id", registration.getString("device_id"))
                .put("device_secret", registration.getString("device_secret"));
    }

    private static String phase(String state) {
        return switch (state) {
            case "incoming" -> "ringing";
            case "dialing", "alerting" -> "connecting";
            case "active", "held" -> "active";
            case "ended" -> "ended";
            default -> "";
        };
    }

    private static void closeSocket(Object value) {
        if (value instanceof Closeable closeable) {
            try { closeable.close(); } catch (Exception ignored) {}
        }
    }

    @Override public void close() {
        synchronized (lock) {
            if (active != null) active.close();
            active = null;
            LocalCallAudioController.get(context).release("cloud-media");
        }
    }
}
