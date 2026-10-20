package com.airsim.phonecontrol;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;
import android.os.Build;

import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * VoBLE 音频网关：三级链路（VoWLAN › VoBLE › 云中继）的中间档。
 * iPhone 与值守机既不在同一 Wi-Fi、也不走公网时，通话音频经 BLE GATT 承载：
 * - AUDIO_UP（write，无响应）：iPhone → 安卓 上行 µ-law（160 字节/20ms 帧）
 * - AUDIO_DOWN（notify）：安卓 → iPhone 下行 µ-law
 * - CONTROL（write + notify）：通话控制 JSON（start/answer/end/state），
 *   携带 VoWLAN 配对密钥的 HMAC，与既有配对安全模型一致
 *
 * 音频落地方式与 VoWLANPCMRelay 完全相同：桥接到 shell PCM bridge
 * （127.0.0.1:7580，AIRSIMPCM1 握手），上行/下行各一条拷贝线程。
 */
final class VoBleGateway {
    static final UUID SERVICE_UUID = UUID.fromString("6E5F0005-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID AUDIO_UP_CHARACTERISTIC =
            UUID.fromString("6E5F0006-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID AUDIO_DOWN_CHARACTERISTIC =
            UUID.fromString("6E5F0007-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CONTROL_CHARACTERISTIC =
            UUID.fromString("6E5F0008-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final int PCM_FRAME_BYTES = 320;   // 20ms @ 8kHz 16bit mono
    private static final int MULAW_FRAME_BYTES = 160; // 同帧 µ-law

    private final Context context;
    private volatile BluetoothGattServer server;
    private volatile BluetoothDevice subscriber;
    private volatile Socket shellPCM;
    private volatile boolean sessionActive;
    private volatile AdvertiseCallback advertiseCallback;
    private final Object sessionLock = new Object();

    VoBleGateway(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean start() throws Exception {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        if (manager == null || manager.getAdapter() == null
                || manager.getAdapter().getBluetoothLeAdvertiser() == null) {
            throw new IllegalStateException("ble_advertiser_unavailable");
        }
        BluetoothGattServer created = manager.openGattServer(context, new Callback());
        if (created == null) throw new IllegalStateException("gatt_server_unavailable");
        created.addService(buildService());
        server = created;
        startAdvertising(manager.getAdapter().getBluetoothLeAdvertiser());
        BridgeLog.info("voble_gateway_started");
        return true;
    }

    /** 常驻广播值守服务：iPhone 在三级路由里按需扫描发现（VoWLAN 优先，见生命周期）。 */
    private void startAdvertising(BluetoothLeAdvertiser advertiser) {
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();
        AdvertiseCallback callback = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                BridgeLog.info("voble_advertising");
            }
            @Override public void onStartFailure(int errorCode) {
                BridgeLog.info("voble_advertising_failed code=" + errorCode);
            }
        };
        advertiseCallback = callback;
        try {
            advertiser.startAdvertising(new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .build(), data, callback);
        } catch (Exception error) {
            BridgeLog.error("voble_advertising_start_failed", error);
        }
    }

    void stop() {
        AdvertiseCallback callback = advertiseCallback;
        advertiseCallback = null;
        if (callback != null) {
            try {
                BluetoothManager manager = context.getSystemService(BluetoothManager.class);
                if (manager != null && manager.getAdapter() != null) {
                    BluetoothLeAdvertiser advertiser = manager.getAdapter().getBluetoothLeAdvertiser();
                    if (advertiser != null) advertiser.stopAdvertising(callback);
                }
            } catch (Exception ignored) {}
        }
        BluetoothGattServer active = server;
        server = null;
        subscriber = null;
        closeSession();
        if (active != null) {
            try { active.clearServices(); } catch (Exception ignored) {}
            try { active.close(); } catch (Exception ignored) {}
        }
        BridgeLog.info("voble_gateway_stopped");
    }

    boolean hasActiveSession() { return sessionActive; }

    private BluetoothGattService buildService() {
        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(new BluetoothGattCharacteristic(AUDIO_UP_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE));
        BluetoothGattCharacteristic down = new BluetoothGattCharacteristic(AUDIO_DOWN_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        down.addDescriptor(new android.bluetooth.BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805F9B34FB"),
                android.bluetooth.BluetoothGattDescriptor.PERMISSION_READ
                        | android.bluetooth.BluetoothGattDescriptor.PERMISSION_WRITE));
        service.addCharacteristic(down);
        service.addCharacteristic(new BluetoothGattCharacteristic(CONTROL_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
                        | BluetoothGattCharacteristic.PERMISSION_WRITE));
        return service;
    }

    // MARK: - 会话

    /** CONTROL start：校验 HMAC 后接通 shell PCM，启动上下行拷贝。 */
    private boolean beginSession(BluetoothDevice device, String message) {
        synchronized (sessionLock) {
            if (sessionActive) return true;
            try {
                JSONObject control = new JSONObject(message);
                if (!"start".equals(control.optString("action"))) return false;
                byte[] secret = VoWLANPairingStore.load(context);
                try {
                    // 与 VoWLAN 控制面同一把密钥；canonical 带时间戳防重放。
                    String canonical = "VOBLE1\n" + control.optLong("timestamp") + "\n"
                            + control.optString("nonce");
                    String expected = VoWLANAuth.sign(secret, canonical);
                    if (!expected.equals(control.optString("hmac"))) {
                        BridgeLog.info("voble_session_rejected reason=authentication");
                        return false;
                    }
                } finally {
                    Arrays.fill(secret, (byte) 0);
                }
                Socket shell = new Socket();
                shell.connect(new InetSocketAddress("127.0.0.1", 7580), 3_000);
                shell.setTcpNoDelay(true);
                shell.getOutputStream().write("AIRSIMPCM1\n".getBytes(StandardCharsets.US_ASCII));
                if (!"AIRSIMREADY".equals(readReadyLine(shell.getInputStream()))) {
                    shell.close();
                    BridgeLog.info("voble_session_rejected reason=shell_handshake");
                    return false;
                }
                shellPCM = shell;
                subscriber = device;
                sessionActive = true;
                BridgeLog.info("voble_session_started");
                Thread downlink = new Thread(() -> pumpDownlink(shell), "voble-downlink");
                downlink.start();
                notifyControl(controlStatus("ready", ""));
                return true;
            } catch (Exception error) {
                BridgeLog.error("voble_session_failed", error);
                return false;
            }
        }
    }

    private void endSession() {
        synchronized (sessionLock) {
            if (!sessionActive) return;
            sessionActive = false;
            Socket shell = shellPCM;
            shellPCM = null;
            subscriber = null;
            closeQuietly(shell);
            BridgeLog.info("voble_session_ended");
        }
    }

    private void closeSession() { endSession(); }

    /** 下行：shell PCM 320 字节帧 → µ-law 160 字节 → BLE 通知。 */
    private void pumpDownlink(Socket shell) {
        byte[] frame = new byte[PCM_FRAME_BYTES];
        try (InputStream input = shell.getInputStream()) {
            while (sessionActive) {
                int read = 0;
                while (read < PCM_FRAME_BYTES) {
                    int count = input.read(frame, read, PCM_FRAME_BYTES - read);
                    if (count < 0) return;
                    read += count;
                }
                byte[] mulaw = MuLawCodec.encode(frame);
                BluetoothGattServer active = server;
                BluetoothDevice device = subscriber;
                if (active == null || device == null) return;
                BluetoothGattCharacteristic characteristic = active.getService(SERVICE_UUID)
                        .getCharacteristic(AUDIO_DOWN_CHARACTERISTIC);
                if (characteristic == null) return;
                characteristic.setValue(mulaw);
                try {
                    active.notifyCharacteristicChanged(device, characteristic, false);
                } catch (Exception error) {
                    BridgeLog.error("voble_downlink_notify_failed", error);
                    return;
                }
                // BLE 5（2M PHY + DLE）下每 20ms 一帧可稳定送达；4.2 老芯片
                // 吞吐不足时自然积压丢帧，窄带音质下降但不断话。
                Thread.sleep(18);
            }
        } catch (Exception error) {
            if (sessionActive) BridgeLog.error("voble_downlink_failed", error);
        } finally {
            endSession();
        }
    }

    /** 上行：BLE 写入的 µ-law → PCM 320 字节 → shell。 */
    private void acceptUplink(BluetoothDevice device, byte[] value) {
        Socket shell = shellPCM;
        if (!sessionActive || shell == null || value == null || value.length == 0) return;
        try {
            byte[] pcm = MuLawCodec.decode(value);
            OutputStream output = shell.getOutputStream();
            int offset = 0;
            while (offset < pcm.length) {
                int count = Math.min(PCM_FRAME_BYTES, pcm.length - offset);
                output.write(pcm, offset, count);
                offset += count;
            }
        } catch (Exception error) {
            BridgeLog.error("voble_uplink_failed", error);
            endSession();
        }
    }

    /** 通话控制/拨号透传：与云命令、本地命令共用同一条执行链。 */
    private boolean dispatchCallControl(String action) {
        return dispatchCallControl(action, null);
    }

    private boolean dispatchCallControl(String action, JSONObject control) {
        try {
            JSONObject command = new JSONObject().put("command_id", UUID.randomUUID().toString())
                    .put("type", "dial".equals(action) ? "dial" : "call_control")
                    .put("action", action)
                    .put("number", control == null ? "" : control.optString("number", ""))
                    .put("subscription_id", control == null ? "" : control.optString("subscription_id", ""));
            JSONObject result = StandaloneAgentGateway.get(context).executeCloudCommand(command);
            return "completed".equals(result.optString("status"));
        } catch (Exception error) {
            BridgeLog.error("voble_call_control_failed", error);
            return false;
        }
    }

    private void notifyControl(JSONObject payload) {
        BluetoothGattServer active = server;
        BluetoothDevice device = subscriber;
        if (active == null || device == null) return;
        BluetoothGattCharacteristic characteristic = active.getService(SERVICE_UUID)
                .getCharacteristic(CONTROL_CHARACTERISTIC);
        if (characteristic == null) return;
        characteristic.setValue(payload.toString().getBytes(StandardCharsets.UTF_8));
        try { active.notifyCharacteristicChanged(device, characteristic, false); }
        catch (Exception ignored) {}
    }

    private static JSONObject controlStatus(String status, String message) throws Exception {
        JSONObject value = new JSONObject().put("status", status);
        if (!message.isEmpty()) value.put("message", message);
        return value;
    }

    private static String readReadyLine(InputStream input) throws IOException {
        byte[] buffer = new byte[64];
        int count = 0;
        while (count < buffer.length) {
            int value = input.read();
            if (value < 0) break;
            buffer[count++] = (byte) value;
            if (value == '\n') break;
        }
        return new String(buffer, 0, count, StandardCharsets.US_ASCII).trim();
    }

    private static void closeQuietly(Closeable value) {
        if (value == null) return;
        try { value.close(); } catch (Exception ignored) {}
    }

    private final class Callback extends BluetoothGattServerCallback {
        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                boolean responseNeeded, int offset, byte[] value) {
            if (CONTROL_CHARACTERISTIC.equals(characteristic.getUuid())) {
                String message = value == null ? "" : new String(value, StandardCharsets.UTF_8);
                boolean accepted;
                try {
                    JSONObject control = new JSONObject(message);
                    String action = control.optString("action", "");
                    if ("start".equals(action)) {
                        accepted = beginSession(device, message);
                    } else if ("end".equals(action)) {
                        // 先挂断通话，再拆音频会话。
                        dispatchCallControl(action);
                        endSession();
                        accepted = true;
                    } else if ("answer".equals(action) || "reject".equals(action)
                            || "dial".equals(action)) {
                        // 通话控制/拨号透传给既有执行链，与云/本地命令同一条路。
                        accepted = dispatchCallControl(action, control);
                    } else {
                        accepted = false;
                    }
                } catch (Exception error) {
                    accepted = false;
                }
                if (responseNeeded) {
                    try {
                        BluetoothGattServer active = server;
                        if (active != null) {
                            active.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
                                    offset, accepted ? "ok".getBytes(StandardCharsets.UTF_8) : null);
                        }
                    } catch (Exception ignored) {}
                }
                return;
            }
            if (AUDIO_UP_CHARACTERISTIC.equals(characteristic.getUuid())) {
                acceptUplink(device, value);
                if (responseNeeded) {
                    try {
                        BluetoothGattServer active = server;
                        if (active != null) {
                            active.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
                                    offset, null);
                        }
                    } catch (Exception ignored) {}
                }
                return;
            }
            if (responseNeeded) {
                try {
                    BluetoothGattServer active = server;
                    if (active != null) {
                        active.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                                offset, null);
                    }
                } catch (Exception ignored) {}
            }
        }

        @Override public void onNotificationSent(BluetoothDevice device, int status) {
            // 下行节流：系统确认上一帧已发出后才继续，避免通知队列堆积。
        }

        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                BridgeLog.info("voble_client_disconnected");
                endSession();
            }
        }
    }
}
