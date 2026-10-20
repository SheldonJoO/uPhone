package com.airsim.phonecontrol;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class PairingServer implements Closeable {
    public interface Listener {
        void onStarted(String code, String address, long expiresAtMillis, String qrPayload);
        void onSucceeded(String agentResponse);
        void onFailed(String message);
    }

    private static final int MAX_HEADERS = 8_192;
    private static final int MAX_BODY = 32_768;
    private final Context context;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private volatile ServerSocket socket;
    private volatile PairingSession session;
    private volatile NsdManager.RegistrationListener registration;
    private volatile BlePairingAdvertiser ble;

    public PairingServer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        executor.execute(() -> {
            try {
                PairingSession created = PairingSession.create(System.currentTimeMillis());
                ServerSocket server = new ServerSocket(0);
                server.setReuseAddress(true);
                socket = server;
                session = created;
                advertise(server.getLocalPort(), created);
                startBle(created);
                // 私有 IPv4 为可选项：BLE 配对通道本就服务「无 Wi-Fi」场景，地址解析
                // 失败只代表不走 HTTP/扫码路径，绝不能阻断整个配对窗口，否则「无 Wi-Fi
                // 蓝牙配对」这条路径永远打不开。没有地址时 qrPayload 留空，由 UI 提示改用蓝牙。
                String address = "";
                String qr = "";
                try {
                    String ip = preferredPrivateIPv4();
                    address = ip + ":" + server.getLocalPort();
                    qr = qrPayload(created, address);
                } catch (IOException ipError) {
                    BridgeLog.info("pairing_no_private_ip reason=" + safeMessage(ipError));
                }
                listener.onStarted(created.code(), address, created.expiresAtMillis(), qr);
                timer.schedule(() -> {
                    listener.onFailed("配对窗口已过期");
                    closeQuietly();
                }, PairingSession.LIFETIME_MILLIS, TimeUnit.MILLISECONDS);
                while (!server.isClosed() && System.currentTimeMillis() <= created.expiresAtMillis()) {
                    try (Socket peer = server.accept()) {
                        if (handle(peer, created)) break;
                    }
                }
            } catch (Exception error) {
                if (socket != null && !socket.isClosed()) {
                    BridgeLog.info("pairing_server_failed reason=" + safeMessage(error));
                    listener.onFailed(safeMessage(error));
                }
            } finally {
                closeQuietly();
            }
        });
    }

    /** 当前设备的身份提示（relay device_id 尾段）；未配对时为空串。 */
    private String deviceIDHint() {
        try {
            JSONObject registration = new StandaloneAgentStore(context).registration();
            if (registration == null) return "";
            String deviceID = registration.optString("device_id", "");
            return deviceID.length() <= 6 ? deviceID : deviceID.substring(deviceID.length() - 6);
        } catch (Exception error) {
            return "";
        }
    }

    private boolean handle(Socket peer, PairingSession created) throws Exception {
        peer.setSoTimeout(8_000);
        InetAddress remote = peer.getInetAddress();
        if (!(remote.isSiteLocalAddress() || remote.isLinkLocalAddress() || remote.isLoopbackAddress())) {
            BridgeLog.info("pairing_claim_rejected reason=non_private_peer");
            respond(peer, 403, "{\"error\":\"private_network_required\"}");
            return false;
        }
        BufferedInputStream input = new BufferedInputStream(peer.getInputStream());
        byte[] headers = readHeaders(input);
        String headerText = new String(headers, StandardCharsets.US_ASCII);
        String[] lines = headerText.split("\\r\n");
        if (lines.length == 0 || !"POST /pair/v1/claim HTTP/1.1".equals(lines[0])) {
            BridgeLog.info("pairing_claim_rejected reason=route");
            respond(peer, 404, "{\"error\":\"not_found\"}");
            return false;
        }
        int length = contentLength(lines);
        byte[] body = readExactly(input, length);
        byte[] response = processClaim(body, created);
        int status = statusOf(response);
        respond(peer, status, new String(response, StandardCharsets.UTF_8));
        return status == 200;
    }

    /** claim 的处理核心：HTTP 与 BLE 两条通道共用。返回 "status\njson" 形式响应。 */
    private byte[] processClaim(byte[] body, PairingSession created) {
        try {
            JSONObject claim = new JSONObject(new String(body, StandardCharsets.UTF_8));
            if (claim.optInt("version") != 1 || !created.id().equals(claim.optString("session_id"))) {
                BridgeLog.info("pairing_claim_rejected reason=session");
                return statusBody(400, "{\"error\":\"invalid_session\"}");
            }
            byte[] plaintext;
            try {
                plaintext = created.claim(
                        PairingCrypto.decodeBase64URL(claim.getString("client_public_key")),
                        PairingCrypto.decodeBase64URL(claim.getString("sealed_registration")),
                        System.currentTimeMillis());
            } catch (GeneralSecurityException | IllegalArgumentException error) {
                BridgeLog.info("pairing_claim_rejected reason=authentication");
                return statusBody(401, "{\"error\":\"pairing_auth_failed\"}");
            }
            try {
                String registrationJSON = new String(plaintext, StandardCharsets.UTF_8);
                JSONObject registration = new JSONObject(registrationJSON);
                String vowlanSecret = registration.optString("vowlan_secret", "");
                if (vowlanSecret.isEmpty()) throw new GeneralSecurityException("VoWLAN secret missing");
                registration.put("android_agent_id", AndroidAgentIdentity.agentID(context));
                registration.put("android_device_name", AndroidAgentIdentity.deviceName());
                registration.put("android_manufacturer", android.os.Build.MANUFACTURER);
                registration.put("android_model", android.os.Build.MODEL);
                registration.put("android_phone_number", AndroidAgentIdentity.phoneNumber(context));
                registration.put("android_agent_kind", RuntimeMode.isStandalone(context) ? "standalone" : "avf");
                // VoWLAN HMAC 密钥只属于 Android 热点网关。Linux Agent 的 Push
                // 注册结构使用严格 JSON 解码，且无须接触该密钥，因此转发前必须移除。
                registration.remove("vowlan_secret");
                String agentResponse = new AgentClient(context).registerPairing(registration.toString());
                VoWLANPairingStore.saveEncoded(context, vowlanSecret);
                VoWLANGatewayService.start(context);
                BridgeLog.info("pairing_claim_accepted");
                listener.onSucceeded(agentResponse);
                // 配对成功后广播必须立刻消失：一次性窗口即刻作废（NSD + BLE 都停），
                // 杜绝「配对完还能在配对页看到设备、重复配对生成新身份」。
                closeQuietly();
                return statusBody(200, agentResponse);
            } catch (Exception error) {
                BridgeLog.error("pairing_claim_processing_failed", error);
                return statusBody(500, "{\"error\":\"registration_failed\"}");
            } finally {
                java.util.Arrays.fill(plaintext, (byte) 0);
            }
        } catch (Exception error) {
            BridgeLog.info("pairing_claim_rejected reason=malformed");
            return statusBody(400, "{\"error\":\"invalid_claim\"}");
        }
    }

    private static byte[] statusBody(int status, String json) {
        return (status + "\n" + json).getBytes(StandardCharsets.UTF_8);
    }

    private static int statusOf(byte[] statusBody) {
        String text = new String(statusBody, StandardCharsets.US_ASCII);
        int newline = text.indexOf('\n');
        try {
            return Integer.parseInt(text.substring(0, newline < 0 ? text.length() : newline).trim());
        } catch (NumberFormatException error) {
            return 500;
        }
    }

    private void advertise(int port, PairingSession created) {
        NsdManager manager = context.getSystemService(NsdManager.class);
        NsdServiceInfo info = new NsdServiceInfo();
        // Android NSD 的注销是异步的；用户快速重开窗口时固定服务名可能仍被
        // 系统占用并返回 ALREADY_ACTIVE。会话短 ID 保证每个一次性窗口唯一。
        info.setServiceName("uPhone-安卓-" + created.id().substring(0, 8));
        info.setServiceType("_airsim-pair._tcp.");
        info.setPort(port);
        info.setAttribute("v", "1");
        info.setAttribute("session", created.id());
        info.setAttribute("key", PairingCrypto.base64URL(created.publicKey()));
        info.setAttribute("expires", Long.toString(created.expiresAtMillis()));
        // 身份提示：iPhone 发现 did 与注册表活跃设备一致时按「同一台手机重配」
        // 复用现有密钥，不再 stage 新身份——重复配对生成无名 ghost 的根治点。
        String did = deviceIDHint();
        if (!did.isEmpty()) info.setAttribute("did", did);
        registration = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo ignored) {}
            @Override public void onRegistrationFailed(NsdServiceInfo ignored, int code) {
                BridgeLog.info("pairing_advertisement_failed code=" + code);
                listener.onFailed("热点发现发布失败（" + code + "）");
            }
            @Override public void onServiceUnregistered(NsdServiceInfo ignored) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo ignored, int code) {}
        };
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
    }

    /** BLE 配对通道：与 NSD 窗口同生命周期，claim 走同一个 processClaim。 */
    private void startBle(PairingSession created) {
        try {
            JSONObject sessionInfo = new JSONObject();
            sessionInfo.put("v", 1);
            sessionInfo.put("session", created.id());
            sessionInfo.put("key", PairingCrypto.base64URL(created.publicKey()));
            sessionInfo.put("expires", created.expiresAtMillis());
            String did = deviceIDHint();
            if (!did.isEmpty()) sessionInfo.put("did", did);
            ble = new BlePairingAdvertiser(context,
                    sessionInfo.toString().getBytes(StandardCharsets.UTF_8),
                    body -> processClaim(body, created));
            ble.start();
            BridgeLog.info("pairing_ble_advertisement_started");
        } catch (Exception error) {
            // BLE 不可用（无硬件 / 未授权）不该阻断 Wi-Fi 配对主路径。
            BridgeLog.info("pairing_ble_start_skipped reason=" + safeMessage(error));
            ble = null;
        }
    }

    private static byte[] readHeaders(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int state = 0;
        while (output.size() < MAX_HEADERS) {
            int value = input.read();
            if (value < 0) throw new IOException("请求头不完整");
            output.write(value);
            state = (state == 0 && value == '\r') ? 1
                    : (state == 1 && value == '\n') ? 2
                    : (state == 2 && value == '\r') ? 3
                    : (state == 3 && value == '\n') ? 4 : 0;
            if (state == 4) return output.toByteArray();
        }
        throw new IOException("请求头过大");
    }

    private static int contentLength(String[] lines) throws IOException {
        for (String line : lines) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                int value;
                try { value = Integer.parseInt(line.substring(15).trim()); }
                catch (NumberFormatException error) { throw new IOException("Content-Length 无效", error); }
                if (value < 1 || value > MAX_BODY) throw new IOException("请求体大小无效");
                return value;
            }
        }
        throw new IOException("缺少 Content-Length");
    }

    private static byte[] readExactly(BufferedInputStream input, int length) throws IOException {
        byte[] output = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(output, offset, length - offset);
            if (count < 0) throw new IOException("请求体不完整");
            offset += count;
        }
        return output;
    }

    private static void respond(Socket peer, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : "Error";
        byte[] headers = ("HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: application/json\r\n" +
                "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        OutputStream output = peer.getOutputStream();
        output.write(headers);
        output.write(body);
        output.flush();
    }

    /**
     * QR payload for iPhone扫码配对. Contains everything the iPhone needs to
     * connect directly: session, server public key, IP/port, code, expiry.
     * Format: {"v":1,"session":"...","key":"...","ip":"...","port":1234,"code":"123456","expires":...}
     */
    static String qrPayload(PairingSession session, String address) {
        try {
            int colon = address.lastIndexOf(':');
            String ip = colon > 0 ? address.substring(0, colon) : address;
            int port = colon > 0 ? Integer.parseInt(address.substring(colon + 1)) : 0;
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("v", 1);
            json.put("session", session.id());
            json.put("key", PairingCrypto.base64URL(session.publicKey()));
            json.put("ip", ip);
            json.put("port", port);
            json.put("code", session.code());
            json.put("expires", session.expiresAtMillis());
            return json.toString();
        } catch (Exception error) {
            BridgeLog.info("qr_payload_failed reason=" + error.getClass().getSimpleName());
            return "";
        }
    }

    static String preferredPrivateIPv4() throws IOException {
        List<NetworkInterface> interfaces = new ArrayList<>();
        Enumeration<NetworkInterface> enumeration = NetworkInterface.getNetworkInterfaces();
        while (enumeration.hasMoreElements()) interfaces.add(enumeration.nextElement());
        interfaces.sort(Comparator.comparingInt(item -> interfacePriority(item.getName())));
        for (NetworkInterface item : interfaces) {
            if (!item.isUp() || item.isLoopback()) continue;
            Enumeration<InetAddress> addresses = item.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                String host = address.getHostAddress();
                if (address instanceof Inet4Address && address.isSiteLocalAddress() && !host.startsWith("10.185.5.")) return host;
            }
        }
        throw new IOException("未找到 VoWLAN IPv4 地址，请连接 Wi-Fi 或开启移动热点");
    }

    private static int interfacePriority(String name) {
        if (name.startsWith("swlan") || name.startsWith("ap")) return 0;
        if (name.startsWith("wlan")) return 1;
        return 2;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private void closeQuietly() {
        try { close(); } catch (IOException ignored) {}
    }

    @Override public void close() throws IOException {
        BlePairingAdvertiser activeBle = ble;
        ble = null;
        if (activeBle != null) {
            try { activeBle.stop(); } catch (Exception ignored) {}
        }
        NsdManager.RegistrationListener active = registration;
        registration = null;
        if (active != null) {
            try { context.getSystemService(NsdManager.class).unregisterService(active); }
            catch (IllegalArgumentException ignored) {}
        }
        ServerSocket activeSocket = socket;
        if (activeSocket != null && !activeSocket.isClosed()) activeSocket.close();
        timer.shutdownNow();
        executor.shutdown();
    }
}
