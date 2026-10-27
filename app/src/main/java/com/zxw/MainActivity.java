package com.zxw;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.zxw.toolkit.BluetoothMessage;
import com.zxw.toolkit.ParseLeAdvData;
import com.zxw.ui.BasActivity;
import com.zxw.ui.Ble_Activity;
import com.zxw.ui.DebugActivity;
import com.zxw.fitness.ui.ExerciseListActivity;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

/**
 * @Description: TODO<MainActivity类实现打开蓝牙、扫描蓝牙>
 * @author
 * @data:
 * @version:
 */
public class MainActivity extends BasActivity implements OnClickListener {
    // 扫描蓝牙按钮
    private Button scan_btn;
    // 蓝牙适配器
    private BluetoothAdapter mBluetoothAdapter;

    //21以上的扫描回调
    private ScanCallback mScanCallback;

    //扫描装置
    private BluetoothLeScanner mBluetoothLeScanner;

    // 蓝牙信号强度
    private ArrayList<Integer> rssis;
    // 自定义Adapter
    LeDeviceListAdapter mleDeviceListAdapter;
    // listview显示扫描到的蓝牙信息
    ListView lv;
    // 描述扫描蓝牙的状态
    private boolean mScanning;
    private boolean scan_flag;
    private Handler mHandler;
    int REQUEST_ENABLE_BT = 1;
    // 蓝牙扫描时间
    private static final long SCAN_PERIOD = 10000;
    private static final int REQUEST_BLE_PERMISSIONS = 2;
    private int scanGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 训练入口不依赖蓝牙或位置授权；仅在点击扫描时请求。
        // 初始化控件
        init();
        // 初始化蓝牙
        init_ble();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            setScanCallBack();
        scan_flag = true;
        // 自定义适配器
        mleDeviceListAdapter = new LeDeviceListAdapter();
        // 为listview指定适配器
        lv.setAdapter(mleDeviceListAdapter);

        /* listview点击函数 */
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {

            @Override
            public void onItemClick(AdapterView<?> arg0, View v, int position,
                                    long id)
            {
                // TODO Auto-generated method stub
                if (!hasBlePermissions()) {
                    requestBlePermissions();
                    return;
                }
                final BluetoothMessage bluetoothMessage = mleDeviceListAdapter
                        .getDevice(position);
                if (bluetoothMessage == null)
                    return;
                final Intent intent = new Intent(MainActivity.this,
                        Ble_Activity.class);
                try {
                    intent.putExtra(Ble_Activity.EXTRAS_DEVICE_NAME,
                            bluetoothMessage.getName()!=null?bluetoothMessage.getName():bluetoothMessage.getDevice().getName());
                    intent.putExtra(Ble_Activity.EXTRAS_DEVICE_ADDRESS,
                            bluetoothMessage.getDevice().getAddress());
                } catch (SecurityException revoked) {
                    Toast.makeText(MainActivity.this, "蓝牙权限已撤销", Toast.LENGTH_SHORT).show();
                    return;
                }
                intent.putExtra(Ble_Activity.EXTRAS_DEVICE_RSSI,
                        rssis.get(position).toString());
                if (mScanning) scanLeDevice(false);

                try {
                    // 启动Ble_Activity
                    startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                    // TODO: handle exception
                }

            }
        });

        scan_btn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                startActivity(new Intent(MainActivity.this, DebugActivity.class));
                return true;
            }
        });

        // 进入体能训练（摄像头姿态捕捉）模块
        findViewById(R.id.fitness_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ExerciseListActivity.class));
            }
        });

    }

    /**
     * @Title: init
     * @Description: TODO(初始化UI控件)
     * @param 无
     * @return void
     * @throws
     */
    private void init()
    {
        scan_btn = (Button) this.findViewById(R.id.scan_dev_btn);
        scan_btn.setOnClickListener(this);
        lv = (ListView) this.findViewById(R.id.lv);
        mHandler = new Handler();
    }

    /**
     * @Title: init_ble
     * @Description: TODO(初始化蓝牙)
     * @param 无
     * @return void
     * @throws
     */
    private void init_ble() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "不支持BLE，仍可使用体能训练", Toast.LENGTH_SHORT).show();
            return;
        }
        // getBluetoothLeScanner/isEnabled 在 Android 12+ 需要运行时权限，延后到扫描。
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    /*
     * 按钮响应事件
     */
    @Override
    public void onClick(View v)
    {
        if (mScanning) {
            scanLeDevice(false);
            return;
        }
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (!mBluetoothAdapter.isEnabled()) {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT);
                return;
            }
        } catch (SecurityException e) {
            Toast.makeText(this, "需要蓝牙权限才能扫描", Toast.LENGTH_SHORT).show();
            return;
        }

        if(!isOpenGPS(this)){
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this,AlertDialog.THEME_DEVICE_DEFAULT_LIGHT);
            builder.setTitle("提示")
                    .setMessage("请前往打开手机的位置权限!")
                    .setCancelable(false)
                    .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                            startActivityForResult(intent, 10);
                        }
                    }).show();
            return;
        }

        if (scan_flag)
        {
            mleDeviceListAdapter = new LeDeviceListAdapter();
            lv.setAdapter(mleDeviceListAdapter);
            scanLeDevice(true);
        } else {
            scanLeDevice(false);
            scan_btn.setText("扫描设备");
        }
    }

    /**
     * @Title: scanLeDevice
     * @Description: TODO(扫描蓝牙设备 )
     * @param enable
     *            (扫描使能，true:扫描开始,false:扫描停止)
     * @return void
     * @throws
     */
    private void scanLeDevice(final boolean enable) {
        if (!hasBlePermissions()) {
            resetScanState();
            if (enable) requestBlePermissions();
            return;
        }
        try {
            if (mBluetoothAdapter == null) {
                resetScanState();
                return;
            }
            if (enable && mBluetoothLeScanner == null) {
                mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
            }
            if (enable && mBluetoothLeScanner == null) {
                resetScanState();
                Toast.makeText(this, "蓝牙未开启或扫描器不可用", Toast.LENGTH_SHORT).show();
                return;
            }
            if (enable) {
                mBluetoothLeScanner.startScan(mScanCallback);
                mScanning = true;
                scan_flag = false;
                scan_btn.setText("停止扫描");
                final int generation = ++scanGeneration;
                mHandler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (mScanning && generation == scanGeneration) scanLeDevice(false);
                    }
                }, SCAN_PERIOD);
            } else {
                if (mBluetoothLeScanner != null) mBluetoothLeScanner.stopScan(mScanCallback);
                resetScanState();
            }
        } catch (SecurityException e) {
            resetScanState();
            Toast.makeText(this, "蓝牙或位置权限已撤销，请重新授权", Toast.LENGTH_SHORT).show();
        }
    }

    private void resetScanState() {
        scanGeneration++;
        mScanning = false;
        scan_flag = true;
        scan_btn.setText("扫描设备");
    }

    /**
     * 蓝牙扫描回调函数 实现扫描蓝牙设备，回调蓝牙BluetoothDevice，可以获取name MAC等信息
     *
     * **/
    private BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback()
    {

        @Override
        public void onLeScan(final BluetoothDevice device, final int rssi,
                             byte[] scanRecord)
        {
            // TODO Auto-generated method stub

            runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    // 讲扫描到设备的信息输出到listview的适配器
                    BluetoothMessage bluetoothMessage = new BluetoothMessage(device);
                    if (!hasBlePermissions()) return;
                    try {
                        mleDeviceListAdapter.addDevice(bluetoothMessage, rssi);
                        mleDeviceListAdapter.notifyDataSetChanged();
                    } catch (SecurityException revoked) {
                        Log.w("BLE", "蓝牙权限已撤销", revoked);
                    }
                }
            });

        }
    };



    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void setScanCallBack(){
        mScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, final ScanResult result) {
                if (!hasBlePermissions() || result == null) return;
                final BluetoothDevice device = result.getDevice();
                if (device == null) return;
                final BluetoothMessage bluetoothMessage = new BluetoothMessage(device);
                if(null != device && null != result.getScanRecord()) {
                    try {
                        if (device.getName()!=null) {
                            byte[] name = ParseLeAdvData.adv_report_parse(ParseLeAdvData.BLE_GAP_AD_TYPE_COMPLETE_LOCAL_NAME,result.getScanRecord().getBytes());
                            if (name != null)
                                bluetoothMessage.setName(new String(name, "GBK"));
                        }
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    } catch (SecurityException revoked) {
                        return;
                    }
                }
                runOnUiThread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        /* 讲扫描到设备的信息输出到listview的适配器 */
                        if (!hasBlePermissions()) return;
                        try {
                            mleDeviceListAdapter.addDevice(bluetoothMessage, result.getRssi());
                            mleDeviceListAdapter.notifyDataSetChanged();
                        } catch (SecurityException revoked) {
                            Log.w("BLE", "蓝牙权限已撤销", revoked);
                        }
                    }
                });

            }
            @Override
            public void onScanFailed(final int errorCode) {
                super.onScanFailed(errorCode);
                runOnUiThread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        resetScanState();
                        Toast.makeText(MainActivity.this, "扫描出错:"+errorCode, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        };
    }

    /**
     * @Description: TODO<自定义适配器Adapter,作为listview的适配器>
     * @author
     * @data:
     * @version:
     */
    private class LeDeviceListAdapter extends BaseAdapter {
        private ArrayList<BluetoothMessage> mLeDevices;

        private LayoutInflater mInflator;

        public LeDeviceListAdapter()
        {
            super();
            rssis = new ArrayList<Integer>();
            mLeDevices = new ArrayList<BluetoothMessage>();
            mInflator = getLayoutInflater();
        }

        public void addDevice(BluetoothMessage device, int rssi)
        {

            for (BluetoothMessage mLeDevice : mLeDevices) {
                if(mLeDevice.getDevice().getAddress().equals(device.getDevice().getAddress())){
                    return;
                }
            }
            mLeDevices.add(device);
            rssis.add(rssi);
        }

        public BluetoothMessage getDevice(int position)
        {
            return mLeDevices.get(position);
        }

        public void clear()
        {
            mLeDevices.clear();
            rssis.clear();
        }

        @Override
        public int getCount()
        {
            return mLeDevices.size();
        }

        @Override
        public Object getItem(int i)
        {
            return mLeDevices.get(i);
        }

        @Override
        public long getItemId(int i)
        {
            return i;
        }

        /**
         * 重写getview
         *
         * **/
        @Override
        public View getView(int i, View view, ViewGroup viewGroup)
        {

            // General ListView optimization code.
            // 加载listview每一项的视图
            view = mInflator.inflate(R.layout.listitem, null);
            // 初始化三个textview显示蓝牙信息
            TextView deviceAddress = (TextView) view
                    .findViewById(R.id.tv_deviceAddr);
            TextView deviceName = (TextView) view
                    .findViewById(R.id.tv_deviceName);
            TextView rssi = (TextView) view.findViewById(R.id.tv_rssi);

            BluetoothMessage bluetoothMessage = mLeDevices.get(i);
            try {
                deviceAddress.setText(bluetoothMessage.getDevice().getAddress());
                deviceName.setText(bluetoothMessage.getName()!=null?bluetoothMessage.getName():bluetoothMessage.getDevice().getName());
            } catch (SecurityException revoked) {
                deviceAddress.setText("蓝牙权限已撤销");
                deviceName.setText("");
            }
            rssi.setText("" + rssis.get(i));

            return view;
        }
    }
    /** Android 12+ 附近设备权限；保留精确位置以免 BLE beacon 被系统过滤。 */
    private boolean hasBlePermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestBlePermissions() {
        // Android 12+ 申请精确位置时需同时申请粗略位置。
        String[] permissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
        ActivityCompat.requestPermissions(this, permissions, REQUEST_BLE_PERMISSIONS);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_BLE_PERMISSIONS) {
            Toast.makeText(this, hasBlePermissions() ? "权限已授予，请再次点击扫描设备"
                    : "扫描需要附近设备和精确位置权限；体能训练不受影响", Toast.LENGTH_LONG).show();
        }
    }

    //判断GPS是否开启，GPS或者AGPS开启一个就认为是开启的
    public static boolean isOpenGPS(final Context context) {
        LocationManager locationManager
                = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        // GPS定位
        boolean gps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        // 网络服务定位
        boolean network = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        if (gps || network) {
            return true;
        } return false;
    }


}