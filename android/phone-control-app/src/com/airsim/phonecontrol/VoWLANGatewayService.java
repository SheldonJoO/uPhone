package com.airsim.phonecontrol;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.app.role.RoleManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.IBinder;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VoWLANGatewayService extends Service {
    // DJOneHub owns 7590/7591 on devices where both apps are installed.
    // AirSIM advertises these independent ports through Bonjour.
    public static final int CONTROL_PORT = 7592;
    public static final int PCM_PORT = 7593;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private volatile VoWLANControlGateway control;
    private volatile VoWLANPCMRelay pcm;
    private volatile VoBleGateway voble;
    private NsdManager.RegistrationListener advertisement;
    private String activeHotspot = "";
    private String lastDiagnosticState = "";
    private static volatile String currentDiagnosticState = "尚未检查";

    // 配对窗口与 VoBLE 音频网关共用同一个 BLE 广播槽（legacy 单广播）。
    // 配对窗口开启期间停掉 VoBLE 广播并阻止其重启，窗口关闭后交还。
    private static volatile VoWLANGatewayService runningInstance;
    private static volatile boolean pairingWindowActive;

    /** 配对窗口开启：立即停掉 VoBLE 广播（让出 BLE 广播槽），并阻止 lifecycleLoop 重启。 */
    public static void beginPairingWindow() {
        pairingWindowActive = true;
        VoWLANGatewayService active = runningInstance;
        if (active != null) active.stopVoBle();
        BridgeLog.info("voble_paused_for_pairing");
    }

    /** 配对窗口关闭：交还广播槽，lifecycleLoop 会按需重新拉起 VoBLE。 */
    public static void endPairingWindow() {
        pairingWindowActive = false;
        BridgeLog.info("voble_resume_after_pairing");
    }

    static String currentDiagnosticState() {
        return currentDiagnosticState;
    }

    /** 当前 VoWLAN 网关监听地址（如 192.168.43.1:7592）；未就绪返回空串。值守页展示用。 */
    static String currentGatewayAddress() {
        VoWLANGatewayService active = runningInstance;
        if (active == null) return "";
        synchronized (active) {
            VoWLANControlGateway gateway = active.control;
            if (gateway == null || active.activeHotspot.isEmpty()) return "";
            return active.activeHotspot + ":" + gateway.localPort();
        }
    }

    public static void start(Context context) {
        BridgeLog.debug("vowlan_service_start_requested");
        try { context.startForegroundService(new Intent(context, VoWLANGatewayService.class)); }
        catch (RuntimeException error) { BridgeLog.error("vowlan_service_start_failed", error); }
    }

    @Override public void onCreate() {
        super.onCreate();
        runningInstance = this;
        currentDiagnosticState = "服务正在启动";
        BridgeLog.info("vowlan_service_created");
        startForeground(BridgeNotification.VOWLAN_ID, BridgeNotification.vowlan(this, "正在发现 VoWLAN 网络"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        running = true;
        worker.execute(this::lifecycleLoop);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }

    static void stop(Context context) {
        try { context.stopService(new Intent(context, VoWLANGatewayService.class)); }
        catch (RuntimeException error) { BridgeLog.error("vowlan_service_stop_failed", error); }
    }

    private void lifecycleLoop() {
        while (running) {
            try {
                Inet4Address hotspot = findAddress(true);
                Inet4Address avf = RuntimeMode.isStandalone(this)
                        ? (Inet4Address) InetAddress.getByName("127.0.0.1") : findAddress(false);
                if (hotspot == null && hasVoWLANCall()) {
                    BridgeLog.info("hotspot_lost_no_midcall_handover action=end_current_call");
                    CallRepository.disconnectAll();
                }
                boolean paired = VoWLANPairingStore.configured(this);
                RoleManager roleManager = getSystemService(RoleManager.class);
                boolean dialerRoleHeld = roleManager != null
                        && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)
                        && roleManager.isRoleHeld(RoleManager.ROLE_DIALER);
                boolean agentReady = false;
                boolean pcmReady = false;
                if (hotspot != null && avf != null && paired && AppConfig.configured(this)) {
                    boolean sameRunningGateway = hotspot.getHostAddress().equals(activeHotspot)
                            && control != null && pcm != null;
                    if (sameRunningGateway) {
                        // 不用空 TCP 探针打扰正在使用的单会话 PCM bridge。已启动的 relay
                        // 会在真实客户端连接时完成内部握手并报告精确错误。
                        agentReady = true;
                        pcmReady = true;
                    } else {
                        try {
                            String status = new AgentClient(this).status();
                            agentReady = status.contains("\"configured\":true") || status.contains("\"ok\":true");
                        } catch (Exception error) {
                            BridgeLog.error("vowlan_agent_probe_failed", error);
                        }
                        pcmReady = portOpen(avf, 7580);
                    }
                }
                diagnosticState(hotspot, avf, paired, agentReady, pcmReady, dialerRoleHeld);
                // 三级递进路由：VoWLAN › VoBLE › 云中继。
                // 一级在线就停掉二级，不占 BLE；一级不可用但已配对即拉二级。
                boolean vowlanReady = VoWLANNetworkPolicy.shouldAdvertise(
                        hotspot != null, paired, agentReady, pcmReady, dialerRoleHeld);
                if (vowlanReady) {
                    ensureStarted(hotspot, avf);
                    stopVoBle();
                    update("VoWLAN 就绪 · " + hotspot.getHostAddress());
                } else {
                    stopGateways();
                    if (paired && AppConfig.configured(this) && !pairingWindowActive) {
                        // 配对窗口开着时 BLE 广播槽归配对用（见 beginPairingWindow），
                        // 这里不再抢广播，否则无 Wi-Fi 重配时 iPhone 扫不到配对设备。
                        ensureVoBle();
                        update("VoBLE 就绪 · 蓝牙直连（无共享 Wi-Fi）");
                    } else if (pairingWindowActive) {
                        stopVoBle();
                        update("配对窗口进行中 · 蓝牙广播归配对使用");
                    } else {
                        stopVoBle();
                        update(!dialerRoleHeld ? "请将 AirSIM 设为默认电话应用"
                                : !paired ? "VoWLAN 未配对"
                                : hotspot == null ? "正在等待同网 Wi-Fi 或手机热点"
                                : !agentReady ? RuntimeMode.agentLabel(this) + " 尚未就绪" : "PCM 音频桥尚未就绪");
                    }
                }
                // PCM 桥上有真实通话时，本机静音由这里也保持一份原因：
                // InCallService 可能因默认拨号角色丢失/服务被回收而收不到 call，
                // 漏音的主路径就是这条没人施静音的窗口。VoBLE 会话同理。
                if (hasVoWLANCall() || (voble != null && voble.hasActiveSession())) {
                    LocalCallAudioController.get(this).retain("pcm-call");
                } else {
                    LocalCallAudioController.get(this).release("pcm-call");
                }
                Thread.sleep(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                currentDiagnosticState = "服务异常：" + error.getClass().getSimpleName();
                BridgeLog.error("vowlan_lifecycle_failed", error);
                stopGateways();
                update("VoWLAN 正在恢复");
                try { Thread.sleep(2_000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private synchronized void ensureStarted(Inet4Address hotspot, Inet4Address avf) throws Exception {
        String host = hotspot.getHostAddress();
        if (host.equals(activeHotspot) && control != null && pcm != null && advertisement != null) return;
        stopGateways();
        VoWLANControlGateway nextControl = new VoWLANControlGateway(this);
        VoWLANPCMRelay nextPCM = new VoWLANPCMRelay(this);
        nextControl.start(hotspot, CONTROL_PORT);
        try {
            nextPCM.start(hotspot, PCM_PORT, new InetSocketAddress(avf, 7580));
        } catch (Exception error) {
            nextControl.close();
            throw error;
        }
        control = nextControl;
        pcm = nextPCM;
        activeHotspot = host;
        if (control.localPort() != CONTROL_PORT || pcm.localPort() != PCM_PORT) {
            BridgeLog.info("vowlan_preferred_port_occupied control_port=" + control.localPort()
                    + " pcm_port=" + pcm.localPort());
        }
        advertise();
        BridgeLog.info("vowlan_ready hotspot=" + host + " control_port=" + control.localPort()
                + " pcm_port=" + pcm.localPort());
    }

    private void advertise() {
        NsdServiceInfo info = new NsdServiceInfo();
        String agentID = AndroidAgentIdentity.agentID(this);
        String kind = RuntimeMode.isStandalone(this) ? "standalone" : "avf";
        info.setServiceName("AirSIM-" + kind + "-" + agentID.substring(Math.max(0, agentID.length() - 8)));
        info.setServiceType("_airsim-vowlan._tcp.");
        info.setPort(control.localPort());
        info.setAttribute("v", "1");
        info.setAttribute("agent_id", agentID);
        info.setAttribute("agent_kind", kind);
        info.setAttribute("device_name", AndroidAgentIdentity.deviceName());
        info.setAttribute("manufacturer", android.os.Build.MANUFACTURER);
        info.setAttribute("model", android.os.Build.MODEL);
        info.setAttribute("host", activeHotspot);
        info.setAttribute("control_port", Integer.toString(control.localPort()));
        info.setAttribute("pcm_port", Integer.toString(pcm.localPort()));
        info.setAttribute("caps", "control,pcm");
        advertisement = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo ignored) {}
            @Override public void onRegistrationFailed(NsdServiceInfo ignored, int code) {
                BridgeLog.info("vowlan_advertisement_failed code=" + code);
            }
            @Override public void onServiceUnregistered(NsdServiceInfo ignored) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo ignored, int code) {}
        };
        getSystemService(NsdManager.class).registerService(info, NsdManager.PROTOCOL_DNS_SD, advertisement);
    }

    private synchronized void stopGateways() {
        if (advertisement != null) {
            try { getSystemService(NsdManager.class).unregisterService(advertisement); }
            catch (IllegalArgumentException ignored) {}
            advertisement = null;
        }
        try { if (control != null) control.close(); } catch (Exception ignored) {}
        try { if (pcm != null) pcm.close(); } catch (Exception ignored) {}
        control = null;
        pcm = null;
        activeHotspot = "";
    }

    /** 二级通道：VoBLE 音频网关（GATT + 广播）。VoWLAN 在线时由调用方停掉。 */
    private synchronized void ensureVoBle() {
        if (voble != null) return;
        try {
            VoBleGateway gateway = new VoBleGateway(this);
            gateway.start();
            voble = gateway;
        } catch (Exception error) {
            // BLE 不可用（无硬件/未授权）时静默保持仅有云中继，不阻断值守。
            BridgeLog.info("voble_start_skipped reason=" + error.getClass().getSimpleName());
        }
    }

    private synchronized void stopVoBle() {
        VoBleGateway active = voble;
        voble = null;
        if (active != null) active.stop();
    }

    private synchronized boolean hasVoWLANCall() {
        return (control != null && control.ownsCall()) || (pcm != null && pcm.hasActiveSession());
    }

    private void diagnosticState(Inet4Address hotspot, Inet4Address avf, boolean paired,
                                 boolean agentReady, boolean pcmReady, boolean dialerRoleHeld) {
        String value = "hotspot=" + address(hotspot) + " avf=" + address(avf) + " paired=" + paired
                + " agent_ready=" + agentReady + " pcm_ready=" + pcmReady
                + " dialer_role=" + dialerRoleHeld;
        currentDiagnosticState = value;
        if (!value.equals(lastDiagnosticState)) {
            lastDiagnosticState = value;
            BridgeLog.info("vowlan_state " + value);
        }
    }

    private static String address(InetAddress address) {
        return address == null ? "missing" : address.getHostAddress();
    }

    private static Inet4Address findAddress(boolean hotspot) throws Exception {
        Inet4Address wifiFallback = null;
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface item = interfaces.nextElement();
            if (!item.isUp() || item.isLoopback()) continue;
            Enumeration<InetAddress> addresses = item.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!(address instanceof Inet4Address value)) continue;
                if (hotspot && VoWLANNetworkPolicy.isHotspotInterface(item.getName(), value.getHostAddress())) return value;
                if (hotspot && wifiFallback == null
                        && VoWLANNetworkPolicy.isVoWLANInterface(item.getName(), value.getHostAddress())) {
                    wifiFallback = value;
                }
                if (!hotspot && "avf_tap_fixed".equals(item.getName())) return value;
            }
        }
        return hotspot ? wifiFallback : null;
    }

    private static boolean portOpen(InetAddress address, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 500);
            return true;
        } catch (Exception error) {
            BridgeLog.debug("pcm_probe_failed address=" + address.getHostAddress() + " port=" + port
                    + " error=" + error.getClass().getSimpleName());
            return false;
        }
    }

    private void update(String detail) {
        getSystemService(android.app.NotificationManager.class).notify(
                BridgeNotification.VOWLAN_ID, BridgeNotification.vowlan(this, detail));
    }

    @Override public void onDestroy() {
        BridgeLog.info("vowlan_service_destroyed");
        currentDiagnosticState = "服务已停止";
        running = false;
        if (VoWLANGatewayService.runningInstance == this) VoWLANGatewayService.runningInstance = null;
        worker.shutdownNow();
        stopVoBle();
        stopGateways();
        super.onDestroy();
    }
}
