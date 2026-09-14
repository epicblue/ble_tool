package com.example.gyroble.service;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BLE 连接层服务（后台 Service）：负责 GATT 连接/断开、特征值读写、通知使能与写队列。
 *
 * 本文件由 ble_tool 演示工程的 BluetoothLeService 精简而来，修改点：
 *  1) 删除了对 UI 类 Ble_Activity 的静态引用耦合；
 *  2) 接收数据的 Intent extra 统一为 EXTRA_BYTE_DATA（byte[]）；
 *  3) 修复发送队列问题：原工程当数据长度恰好为 20 的整数倍时不会调用 startSend，
 *     导致数据滞留在队列中；现 startSend 带防重入保护，可随时安全调用；
 *  4) connectGatt 按 API 等级自动选择 TRANSPORT_LE（API23+），兼容更低版本；
 *  5) setCharacteristicNotification 增加 CCCD 描述符空判断，防止部分设备崩溃；
 *  6) 写操作加入超时保护，避免某次写回调丢失后队列永久卡死；
 *  7) 新增 requestMtu() 以便协商更大的单包长度。
 */
public class BluetoothLeService extends Service {

    private static final String TAG = "BluetoothLeService";

    /** CCCD 描述符 UUID（使能通知/取消通知都要写它） */
    public static final UUID UUID_CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;
    private int mConnectionState = STATE_DISCONNECTED;

    // ---- 广播 Action（与原工程保持同构，仅改了包名前缀）----
    public final static String ACTION_GATT_CONNECTED =
            "com.example.gyroble.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.example.gyroble.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.gyroble.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.example.gyroble.bluetooth.le.ACTION_DATA_AVAILABLE";
    /** Intent extra：byte[] 原始接收数据 */
    public final static String EXTRA_BYTE_DATA =
            "com.example.gyroble.bluetooth.le.EXTRA_BYTE_DATA";
    /** Intent extra：int RSSI 值 */
    public final static String EXTRA_RSSI =
            "com.example.gyroble.bluetooth.le.EXTRA_RSSI";

    // ---- 写队列 ----
    private final List<byte[]> mQueue = new ArrayList<>();
    /** 上一次写是否已收到 onCharacteristicWrite 回调 */
    private volatile boolean mWriteDone = true;
    /** 是否已有发送线程在跑 */
    private volatile boolean mSending = false;

    /* ------------------------------------------------------------------ */
    /* GATT 回调                                                           */
    /* ------------------------------------------------------------------ */
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mConnectionState = STATE_CONNECTED;
                broadcastUpdate(ACTION_GATT_CONNECTED);
                Log.i(TAG, "Connected to GATT server, start service discovery: "
                        + mBluetoothGatt.discoverServices());
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mConnectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server. status=" + status);
                broadcastUpdate(ACTION_GATT_DISCONNECTED);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic.getValue());
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            // 从机主动通知（陀螺仪连续导航数据从这里上来）
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic.getValue());
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            // 一次写完成，放行队列中的下一包
            mWriteDone = true;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onCharacteristicWrite status=" + status);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor, int status) {
            Log.i(TAG, "onDescriptorWrite status=" + status);
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                final Intent intent = new Intent(ACTION_DATA_AVAILABLE);
                intent.putExtra(EXTRA_RSSI, rssi);
                sendBroadcast(intent);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            // mtu 为协商后的 ATT MTU，有效单包载荷 = mtu - 3
            Log.i(TAG, "onMtuChanged mtu=" + mtu + " status=" + status);
        }
    };

    /* ------------------------------------------------------------------ */
    /* 广播                                                                */
    /* ------------------------------------------------------------------ */
    private void broadcastUpdate(final String action) {
        sendBroadcast(new Intent(action));
    }

    private void broadcastUpdate(final String action, final byte[] data) {
        final Intent intent = new Intent(action);
        if (data != null && data.length > 0) {
            intent.putExtra(EXTRA_BYTE_DATA, data);
        }
        sendBroadcast(intent);
    }

    /* ------------------------------------------------------------------ */
    /* Service 绑定                                                        */
    /* ------------------------------------------------------------------ */
    public class LocalBinder extends Binder {
        public BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        close();
        return super.onUnbind(intent);
    }

    /* ------------------------------------------------------------------ */
    /* 对外 API                                                            */
    /* ------------------------------------------------------------------ */

    /** 初始化蓝牙适配器，返回是否成功 */
    public boolean initialize() {
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }
        return true;
    }

    public boolean isConnected() {
        return mConnectionState == STATE_CONNECTED;
    }

    /** 发起连接（异步，结果通过 ACTION_GATT_CONNECTED / DISCONNECTED 广播） */
    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }
        // 之前连接过同一设备，尝试直接重连
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
                && mBluetoothGatt != null) {
            if (mBluetoothGatt.connect()) {
                mConnectionState = STATE_CONNECTING;
                return true;
            }
            return false;
        }
        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found. Unable to connect.");
            return false;
        }
        // API23+ 指定 TRANSPORT_LE，避免个别手机走 BR/EDR 导致 133 错误
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mBluetoothGatt = device.connectGatt(this, false, mGattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } else {
            mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        }
        mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    /** 断开连接（异步，结果通过广播回调） */
    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
    }

    /** 释放 GATT 资源（unbind 时自动调用） */
    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
        mConnectionState = STATE_DISCONNECTED;
        synchronized (mQueue) {
            mQueue.clear();
        }
    }

    /** 请求 MTU 协商（API21+），协商成功后单包载荷 = mtu - 3，默认 23-3=20 字节 */
    public boolean requestMtu(int mtu) {
        if (mBluetoothGatt == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return mBluetoothGatt.requestMtu(mtu);
        }
        return false;
    }

    /** 读取特征值（结果在 onCharacteristicRead → 广播） */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    /** 读取远端 RSSI（结果带 EXTRA_RSSI 广播） */
    public void readRssi() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.readRemoteRssi();
    }

    /**
     * 使能/关闭指定特征值的通知。
     * 必须写 CCCD(0x2902) 描述符，从机才会真正推送 notification。
     */
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        if (!mBluetoothGatt.setCharacteristicNotification(characteristic, enabled)) {
            Log.w(TAG, "setCharacteristicNotification failed");
            return;
        }
        BluetoothGattDescriptor clientConfig =
                characteristic.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG);
        if (clientConfig == null) {
            Log.w(TAG, "CCCD descriptor not found on " + characteristic.getUuid());
            return;
        }
        clientConfig.setValue(enabled
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        mBluetoothGatt.writeDescriptor(clientConfig);
    }

    public List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) {
            return null;
        }
        return mBluetoothGatt.getServices();
    }

    /* ------------------------------------------------------------------ */
    /* 写队列：BLE 写操作必须串行，等上一包 onCharacteristicWrite 回调再发下一包   */
    /* ------------------------------------------------------------------ */

    /** 将一帧/一片数据加入发送队列（不会立即发送，需调用 startSend） */
    public void writeCharacteristic(byte[] bytes) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        if (bytes == null || bytes.length == 0) {
            return;
        }
        synchronized (mQueue) {
            mQueue.add(bytes.clone());
        }
    }

    /**
     * 启动发送线程，把队列里的数据依次写到指定特征值。
     * 带防重入保护：已有线程在发送时直接返回，可重复调用。
     */
    public void startSend(final BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null || characteristic == null) {
            Log.w(TAG, "not ready to send");
            return;
        }
        if (mSending) {
            return; // 已有发送线程在跑，新数据入队即可
        }
        mSending = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 根据特征值属性选择写类型
                    int props = characteristic.getProperties();
                    boolean onlyNoResp =
                            (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                                    && (props & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0;
                    characteristic.setWriteType(onlyNoResp
                            ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

                    while (true) {
                        byte[] next;
                        synchronized (mQueue) {
                            if (mQueue.isEmpty()) {
                                break;
                            }
                            next = mQueue.remove(0);
                        }
                        // 等待上一次写完成（回调里会把 mWriteDone 置回 true）
                        int waited = 0;
                        while (!mWriteDone && waited < 100) { // 最长等 1s
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException ignored) {
                            }
                            waited++;
                        }
                        if (!mWriteDone) {
                            Log.w(TAG, "write timeout, drop/continue current package");
                        }
                        characteristic.setValue(next);
                        mWriteDone = false;
                        if (!mBluetoothGatt.writeCharacteristic(characteristic)) {
                            mWriteDone = true; // writeCharacteristic 返回 false，未进入回调
                            Log.w(TAG, "writeCharacteristic returned false");
                        }
                        // 相邻两包之间留一点间隔，降低丢包率
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException ignored) {
                        }
                    }
                } finally {
                    mSending = false;
                    // 收尾保护：期间又入队了新数据则再拉起一轮
                    boolean hasMore;
                    synchronized (mQueue) {
                        hasMore = !mQueue.isEmpty();
                    }
                    if (hasMore) {
                        startSend(characteristic);
                    }
                }
            }
        }).start();
    }
}
