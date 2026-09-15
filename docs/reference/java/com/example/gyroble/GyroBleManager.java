package com.example.gyroble;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.example.gyroble.protocol.GyroProtocol;
import com.example.gyroble.service.BluetoothLeService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 陀螺仪 BLE 管理器（业务门面层）。
 *
 * 职责：
 *  - 绑定/解绑 {@link BluetoothLeService}，管理 GATT 连接生命周期；
 *  - 服务发现完成后自动定位目标特征值并使能通知；
 *  - 提供 5 条业务命令的发送入口（设置纬度/寻北/进入导航/退出导航 + 查询）；
 *  - 接收通知数据 → {@link GyroProtocol.Parser} 流式解帧 → 主线程回调。
 *
 * 说明：本类全部使用匿名内部类实现回调，未使用 Lambda 表达式，
 * 兼容 Java 7 源码级别的旧工具链，无需额外配置即可编入任何工程。
 *
 * 用法示例见指导文档第 4.6 节。
 */
public class GyroBleManager {

    private static final String TAG = "GyroBleManager";

    /** BLE 单包默认载荷（ATT MTU 23 - 3）。协商更大 MTU 后可调大 */
    private static final int DEFAULT_CHUNK_SIZE = 20;

    /* ------------------------------------------------------------------ */
    /* 回调接口（所有回调都在主线程）                                          */
    /* ------------------------------------------------------------------ */
    public interface Callback {
        /** GATT 已连接（尚未发现服务） */
        void onConnected();

        /** GATT 断开 */
        void onDisconnected();

        /** 服务发现完成、目标特征值已找到且通知已使能，可以开始下发命令 */
        void onServiceReady();

        /** 收到通用应答(0x7F)：ackedCmd=被应答命令，result=结果码(见 GyroProtocol.RESULT_*) */
        void onAck(int ackedCmd, int result);

        /** 收到寻北结果(0x06)：result 见 GyroProtocol.SEEK_*，headingDeg 成功时有效 */
        void onNorthSeekResult(int result, double headingDeg);

        /** 收到一帧连续导航数据(0x04) */
        void onNavData(GyroProtocol.NavData nav);

        /** 出错（特征值未找到、未连接就发送等） */
        void onError(String message);
    }

    /* ------------------------------------------------------------------ */
    /* 目标 UUID（按实际陀螺仪模块固件修改！）                                */
    /* 演示工程默认使用常见的透传模块 UUID（服务 FFE0 / 特征 FFE1）            */
    /* ------------------------------------------------------------------ */
    private String serviceUuid = "0000ffe0-0000-1000-8000-00805f9b34fb";
    private String charUuid = "0000ffe1-0000-1000-8000-00805f9b34fb";

    private final Context appContext;
    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final GyroProtocol.Parser parser = new GyroProtocol.Parser();

    private BluetoothLeService bleService;
    private boolean bound;
    private boolean receiverRegistered;
    private BluetoothGattCharacteristic targetChar;
    private String deviceAddress;
    private int chunkSize = DEFAULT_CHUNK_SIZE;

    public GyroBleManager(Context context, Callback callback) {
        this.appContext = context.getApplicationContext();
        this.callback = callback;
    }

    /** 按实际硬件修改服务/特征值 UUID（需要在 connect 之前调用） */
    public void setUuids(String serviceUuid, String charUuid) {
        this.serviceUuid = serviceUuid;
        this.charUuid = charUuid;
    }

    /** 协商到更大 MTU 后可调大单包长度（= MTU - 3） */
    public void setChunkSize(int chunkSize) {
        this.chunkSize = Math.max(20, chunkSize);
    }

    /* ------------------------------------------------------------------ */
    /* 连接管理                                                             */
    /* ------------------------------------------------------------------ */

    /** 发起连接（异步）：绑定服务 → connectGatt → 发现服务 → 使能通知 → onServiceReady */
    public boolean connect(String address) {
        this.deviceAddress = address;
        parser.reset();
        registerReceiverIfNeeded();
        Intent intent = new Intent(appContext, BluetoothLeService.class);
        bound = appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            postError("绑定 BluetoothLeService 失败");
        }
        return bound;
    }

    /** 断开并释放所有资源（页面退出时务必调用） */
    public void disconnect() {
        unregisterReceiverIfNeeded();
        if (bleService != null) {
            bleService.disconnect();
        }
        if (bound) {
            try {
                appContext.unbindService(serviceConnection);
            } catch (Exception ignored) {
            }
            bound = false;
        }
        bleService = null;
        targetChar = null;
    }

    public boolean isReady() {
        return bleService != null && bleService.isConnected() && targetChar != null;
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            bleService = ((BluetoothLeService.LocalBinder) binder).getService();
            if (!bleService.initialize()) {
                postError("蓝牙初始化失败");
                return;
            }
            if (!bleService.connect(deviceAddress)) {
                postError("发起 GATT 连接失败");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bleService = null;
            targetChar = null;
        }
    };

    /* ------------------------------------------------------------------ */
    /* 接收 BluetoothLeService 的广播                                        */
    /* ------------------------------------------------------------------ */
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onConnected();
                    }
                });
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                targetChar = null;
                parser.reset();
                post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onDisconnected();
                    }
                });
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                setupTargetCharacteristic();
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                byte[] data = intent.getByteArrayExtra(BluetoothLeService.EXTRA_BYTE_DATA);
                if (data != null && data.length > 0) {
                    parser.feed(data, frameListener);
                }
            }
        }
    };

    private static IntentFilter makeIntentFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        filter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        filter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        filter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return filter;
    }

    private void registerReceiverIfNeeded() {
        if (receiverRegistered) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, makeIntentFilter(), Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(receiver, makeIntentFilter());
        }
        receiverRegistered = true;
    }

    private void unregisterReceiverIfNeeded() {
        if (!receiverRegistered) {
            return;
        }
        try {
            appContext.unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        receiverRegistered = false;
    }

    /* ------------------------------------------------------------------ */
    /* 服务发现 → 定位特征值 → 使能通知                                       */
    /* ------------------------------------------------------------------ */
    private void setupTargetCharacteristic() {
        if (bleService == null) {
            return;
        }
        List<BluetoothGattService> services = bleService.getSupportedGattServices();
        if (services == null) {
            postError("服务列表为空");
            return;
        }
        UUID charUuidObj = UUID.fromString(charUuid);
        for (BluetoothGattService service : services) {
            BluetoothGattCharacteristic ch = service.getCharacteristic(charUuidObj);
            if (ch != null) {
                targetChar = ch;
                bleService.setCharacteristicNotification(ch, true);
                // 与演示工程一致：稍作延时再通知上层，避免 GATT 操作过于密集引发 133 错误
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onServiceReady();
                            }
                        });
                    }
                }, 300);
                return;
            }
        }
        postError("未找到目标特征值 " + charUuid + "，请检查 UUID 配置");
    }

    /* ------------------------------------------------------------------ */
    /* 协议帧分发                                                           */
    /* ------------------------------------------------------------------ */
    private final GyroProtocol.Parser.FrameListener frameListener =
            new GyroProtocol.Parser.FrameListener() {
                @Override
                public void onFrame(byte cmd, byte[] data) {
                    if (cmd == GyroProtocol.CMD_ACK) {
                        final int[] ack = GyroProtocol.parseAck(data);
                        if (ack != null) {
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    callback.onAck(ack[0], ack[1]);
                                }
                            });
                        }
                    } else if (cmd == GyroProtocol.CMD_NAV_DATA) {
                        final GyroProtocol.NavData nav = GyroProtocol.parseNavData(data);
                        if (nav != null) {
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    callback.onNavData(nav);
                                }
                            });
                        }
                    } else if (cmd == GyroProtocol.CMD_NORTH_SEEK_RESULT) {
                        final int[] r = GyroProtocol.parseNorthSeekResult(data);
                        if (r != null) {
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    callback.onNorthSeekResult(r[0], r[1] / 100.0);
                                }
                            });
                        }
                    } else {
                        Log.w(TAG, "unknown cmd frame: " + String.format("%02X", cmd));
                    }
                }
            };

    /* ------------------------------------------------------------------ */
    /* 对外业务命令                                                          */
    /* ------------------------------------------------------------------ */

    /** 0x01 设置纬度（度，北纬为正、南纬为负，范围 ±90） */
    public boolean sendSetLatitude(double latitudeDeg) {
        return sendFrame(GyroProtocol.buildSetLatitude(latitudeDeg));
    }

    /** 0x02 寻北 */
    public boolean startNorthSeek() {
        return sendFrame(GyroProtocol.buildStartNorthSeek());
    }

    /** 0x03 进入导航（设备开始连续推送导航数据） */
    public boolean enterNavigation() {
        return sendFrame(GyroProtocol.buildEnterNavigation());
    }

    /** 0x05 退出导航（设备停止推送） */
    public boolean exitNavigation() {
        return sendFrame(GyroProtocol.buildExitNavigation());
    }

    /* ------------------------------------------------------------------ */
    /* 发送：组帧 → 分包 → 写队列 → 启动发送线程                              */
    /* ------------------------------------------------------------------ */
    private boolean sendFrame(byte[] frame) {
        if (!isReady()) {
            postError("连接未就绪，无法发送: " + GyroProtocol.toHex(frame));
            return false;
        }
        for (byte[] chunk : split(frame, chunkSize)) {
            bleService.writeCharacteristic(chunk);
        }
        bleService.startSend(targetChar);
        return true;
    }

    /** 按 BLE 单包长度切分（协议帧 ≤ 13 字节时默认不会触发切分） */
    private static List<byte[]> split(byte[] data, int size) {
        List<byte[]> chunks = new ArrayList<byte[]>();
        int offset = 0;
        while (offset < data.length) {
            int n = Math.min(size, data.length - offset);
            byte[] chunk = new byte[n];
            System.arraycopy(data, offset, chunk, 0, n);
            chunks.add(chunk);
            offset += n;
        }
        return chunks;
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                                */
    /* ------------------------------------------------------------------ */
    private void post(Runnable r) {
        mainHandler.post(r);
    }

    private void postError(final String message) {
        Log.e(TAG, message);
        post(new Runnable() {
            @Override
            public void run() {
                callback.onError(message);
            }
        });
    }
}
