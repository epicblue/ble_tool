package com.example.gyroble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;

/**
 * BLE 扫描器（API 21+）。
 *
 * 对应演示工程 MainActivity 中的扫描逻辑，剥离了 UI 依赖。
 * 权限要求（调用方必须先申请，见指导文档 2.5 节）：
 *  - Android 12+ (API 31)：BLUETOOTH_SCAN（若声明 neverForLocation 则无需定位权限）
 *  - Android 6 ~ 11：ACCESS_FINE_LOCATION 且系统定位开关必须打开
 */
public class GyroBleScanner {

    private static final String TAG = "GyroBleScanner";

    public interface ScanListener {
        /** 扫描到设备（可能在同一设备上重复回调，调用方按地址去重） */
        void onDeviceFound(BluetoothDevice device, int rssi, String name);

        /** 扫描启动失败，错误码见 ScanCallback.SCAN_FAILED_* */
        void onScanFailed(int errorCode);
    }

    private final BluetoothAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScanCallback scanCallback;
    private volatile boolean scanning;
    private String namePrefix;

    public GyroBleScanner(Context context) {
        BluetoothManager bm = (BluetoothManager)
                context.getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm != null ? bm.getAdapter() : null;
    }

    public boolean isBluetoothAvailable() {
        return adapter != null;
    }

    public boolean isBluetoothEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    /**
     * 开始扫描。
     *
     * @param namePrefix 广播名前缀过滤（如陀螺仪模块广播名 "GYRO-"），传 null 不过滤
     * @param durationMs 自动停止时间（毫秒），<=0 表示不自动停止
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public void startScan(final String namePrefix, long durationMs, final ScanListener listener) {
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "BluetoothAdapter unavailable or disabled");
            return;
        }
        if (scanning) {
            stopScan();
        }
        this.namePrefix = namePrefix;
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                String name = device.getName();
                if (namePrefix != null && (name == null || !name.startsWith(namePrefix))) {
                    return;
                }
                listener.onDeviceFound(device, result.getRssi(), name);
            }

            @Override
            public void onScanFailed(int errorCode) {
                scanning = false;
                listener.onScanFailed(errorCode);
            }
        };
        scanning = true;
        adapter.getBluetoothLeScanner().startScan(scanCallback);
        if (durationMs > 0) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    stopScan();
                }
            }, durationMs);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public void stopScan() {
        if (!scanning || adapter == null) {
            return;
        }
        scanning = false;
        handler.removeCallbacksAndMessages(null);
        try {
            if (adapter.isEnabled() && scanCallback != null) {
                adapter.getBluetoothLeScanner().stopScan(scanCallback);
            }
        } catch (Exception e) {
            Log.w(TAG, "stopScan error: " + e.getMessage());
        }
        scanCallback = null;
    }

    public boolean isScanning() {
        return scanning;
    }
}
