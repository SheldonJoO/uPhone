package com.airsim.phonecontrol;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * BLE 配对通道：Wi-Fi 不可用（AP 隔离 / 只连了热点 / NSD 被路由器挡）时的
 * 替代握手路径。与一次性 NSD 窗口同生命周期，claim 的加解密与处理完全复用
 * {@link PairingServer} 的 processClaim，BLE 只负责把字节搬过去。
 *
 * GATT 布局：
 * - SESSION（读）：一次性会话信息 JSON（session / key / expires / did）
 * - CLAIM（写）：iPhone 写入加密 claim，响应经 RESPONSE 通知+读取返回
 */
final class BlePairingAdvertiser {
    interface ClaimHandler {
        byte[] handle(byte[] claimBody);
    }

    static final UUID SERVICE_UUID = UUID.fromString("6E5F0001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID SESSION_CHARACTERISTIC =
            UUID.fromString("6E5F0002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CLAIM_CHARACTERISTIC =
            UUID.fromString("6E5F0003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID RESPONSE_CHARACTERISTIC =
            UUID.fromString("6E5F0004-B5A3-F393-E0A9-E50E24DCCA9E");

    private final Context context;
    private final byte[] sessionPayload;
    private final ClaimHandler claimHandler;
    private volatile byte[] responsePayload = new byte[0];
    private volatile BluetoothGattServer server;
    private volatile AdvertiseCallback advertiseCallback;

    BlePairingAdvertiser(Context context, byte[] sessionPayload, ClaimHandler claimHandler) {
        this.context = context.getApplicationContext();
        this.sessionPayload = sessionPayload;
        this.claimHandler = claimHandler;
    }

    boolean isSupported() {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
                && manager() != null && manager().getAdapter() != null;
    }

    private BluetoothManager manager() {
        return context.getSystemService(BluetoothManager.class);
    }

    void start() throws Exception {
        if (!isSupported()) throw new IllegalStateException("bluetooth_le_unavailable");
        BluetoothGattServer gattServer = manager().openGattServer(context, new GattCallback());
        if (gattServer == null) throw new IllegalStateException("gatt_server_unavailable");
        gattServer.addService(buildService());
        server = gattServer;
        BluetoothLeAdvertiser advertiser = manager().getAdapter().getBluetoothLeAdvertiser();
        if (advertiser == null) {
            gattServer.close();
            server = null;
            throw new IllegalStateException("ble_advertiser_unavailable");
        }
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new android.os.ParcelUuid(SERVICE_UUID))
                .build();
        AdvertiseCallback callback = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                BridgeLog.info("pairing_ble_advertising");
            }
            @Override public void onStartFailure(int errorCode) {
                BridgeLog.info("pairing_ble_advertising_failed code=" + errorCode);
            }
        };
        advertiseCallback = callback;
        advertiser.startAdvertising(new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build(), data, callback);
    }

    void stop() {
        AdvertiseCallback callback = advertiseCallback;
        advertiseCallback = null;
        if (callback != null) {
            try {
                BluetoothLeAdvertiser advertiser = manager().getAdapter().getBluetoothLeAdvertiser();
                if (advertiser != null) advertiser.stopAdvertising(callback);
            } catch (Exception ignored) {}
        }
        BluetoothGattServer active = server;
        server = null;
        if (active != null) {
            try { active.clearServices(); } catch (Exception ignored) {}
            try { active.close(); } catch (Exception ignored) {}
        }
        responsePayload = new byte[0];
    }

    private BluetoothGattService buildService() {
        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic session = new BluetoothGattCharacteristic(
                SESSION_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        BluetoothGattCharacteristic claim = new BluetoothGattCharacteristic(
                CLAIM_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        BluetoothGattCharacteristic response = new BluetoothGattCharacteristic(
                RESPONSE_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        service.addCharacteristic(session);
        service.addCharacteristic(claim);
        service.addCharacteristic(response);
        return service;
    }

    private final class GattCallback extends BluetoothGattServerCallback {
        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattCharacteristic characteristic) {
            byte[] payload;
            if (SESSION_CHARACTERISTIC.equals(characteristic.getUuid())) {
                payload = sessionPayload;
            } else if (RESPONSE_CHARACTERISTIC.equals(characteristic.getUuid())) {
                payload = responsePayload;
            } else {
                respond(device, requestId, offset, null);
                return;
            }
            byte[] slice = offset >= payload.length ? new byte[0]
                    : java.util.Arrays.copyOfRange(payload, offset, payload.length);
            respond(device, requestId, 0, slice);
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                boolean responseNeeded, int offset, byte[] value) {
            if (!CLAIM_CHARACTERISTIC.equals(characteristic.getUuid())) {
                if (responseNeeded) respond(device, requestId, offset, null);
                return;
            }
            byte[] result = claimHandler.handle(value == null ? new byte[0] : value);
            responsePayload = result == null ? new byte[0] : result;
            if (responseNeeded) respond(device, requestId, offset, result);
            // 同时通知已订阅 RESPONSE 的客户端，省一次读取往返。
            BluetoothGattServer active = server;
            if (active != null) {
                BluetoothGattCharacteristic response = active.getService(SERVICE_UUID)
                        .getCharacteristic(RESPONSE_CHARACTERISTIC);
                if (response != null) {
                    response.setValue(result);
                    try { active.notifyCharacteristicChanged(device, response, false); }
                    catch (Exception ignored) {}
                }
            }
        }

        private void respond(BluetoothDevice device, int requestId, int offset, byte[] value) {
            BluetoothGattServer active = server;
            if (active == null) return;
            int status = value == null
                    ? android.bluetooth.BluetoothGatt.GATT_INVALID_OFFSET
                    : android.bluetooth.BluetoothGatt.GATT_SUCCESS;
            try {
                active.sendResponse(device, requestId, status,
                        value == null ? 0 : offset, value);
            } catch (Exception ignored) {}
        }
    }
}
