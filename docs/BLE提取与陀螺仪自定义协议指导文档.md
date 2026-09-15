# Android BLE 通讯模块提取与陀螺仪自定义协议指导文档

> 基于演示工程 `epicblue/ble_tool`（commit `3abf5aa`）分析编写
> 目标：将蓝牙通讯部分提取并移植到其他 Android 项目，定义陀螺仪硬件控制的自定义协议
> 协议命令：设置纬度、寻北、进入导航、接收连续导航数据、退出导航
> 配套文档：《BLE协议时序图_状态图_异常处理与术语表》（时序图/状态图/异常处理/术语，Mermaid 编写）

---

## 目录

1. [演示项目分析](#1-演示项目分析)
2. [提取清单与移植步骤](#2-提取清单与移植步骤)
3. [陀螺仪自定义协议设计](#3-陀螺仪自定义协议设计)
4. [参考实现代码与使用方法](#4-参考实现代码与使用方法)
5. [联调测试清单](#5-联调测试清单)
6. [常见问题（FAQ）](#6-常见问题faq)

---

## 1. 演示项目分析

### 1.1 工程结构与职责

| 文件 | 职责 | 与 BLE 通讯的关系 |
|---|---|---|
| `MainActivity.java` | 打开蓝牙、扫描设备、列表展示、点击进入连接页 | ✅ 扫描逻辑可复用 |
| `ui/Ble_Activity.java` | 连接设备、绑定服务、发现特征值、分包发送、接收显示 | ✅ 核心业务逻辑所在 |
| `service/BluetoothLeService.java` | 后台 Service：GATT 连接/断开、读写特征值、使能通知、写队列 | ✅ **最核心，必须提取** |
| `toolkit/HexUtils.java` | byte[] ↔ 十六进制字符串 | ✅ 调试工具，可复用 |
| `toolkit/ParseLeAdvData.java` | 解析 BLE 广播包（设备名、16bit UUID 等） | ⭕ 可选（需要解析广播时才用） |
| `toolkit/BluetoothMessage.java` | 扫描结果封装（device + name） | ⭕ 可选 |
| `ui/BasActivity.java` | 全局异常捕获基类 | ❌ 与 BLE 无关 |
| `ui/DebugActivity.java` | 异常日志查看页 | ❌ 与 BLE 无关 |

### 1.2 数据流（原工程架构）

```
┌─────────────┐  bind   ┌──────────────────────┐  GATT   ┌──────────┐
│ Ble_Activity│────────▶│  BluetoothLeService  │◀───────▶│ 外设模块  │
│   (UI/业务) │         │  (连接/读写/通知使能) │         │          │
└─────────────┘         └──────────────────────┘         └──────────┘
      ▲                            │
      │  广播(ACTION_DATA_AVAILABLE │  Intent 广播
      │  / GATT_CONNECTED 等)      ▼
      └────────── BroadcastReceiver ◀──┘
```

**发送路径**（`Ble_Activity.sendDataThread`）：
文本/HEX → `byte[]` → 按 20 字节分包 → `BluetoothLeService.writeCharacteristic(chunk)` 入队
→ `startSend(target_chara)` 启动发送线程逐包写入 → 每包等待 `onCharacteristicWrite` 回调（`mSendState` 标志）再发下一包。

**接收路径**：外设 notification → `onCharacteristicChanged` 回调 → `broadcastUpdate` 发广播
（extra `BLE_BYTE_DATA`=原始字节）→ `Ble_Activity` 的 `BroadcastReceiver` 收到并显示。

**特征值选择**：`Ble_Activity.displayGattServices()` 遍历所有 GATT 服务，把 UUID 等于
`0000ffe1-0000-1000-8000-00805f9b34fb` 的特征值记为 `target_chara`（写数据用），并调用
`setCharacteristicNotification()` 使能通知（内部写 CCCD 描述符 `0x2902`）。

### 1.3 提取时必须修正的问题

| # | 问题 | 位置 | 处理方式 |
|---|---|---|---|
| 1 | **Service 直接引用 UI 类**：`broadcastUpdate()` 里有 `Ble_Activity.revDataForCharacteristic = data;` | `BluetoothLeService` | 删除该行，去掉对 `com.zxw.ui` 的 import，Service 层不能依赖任何 Activity |
| 2 | **发送长度恰为 20 整数倍时数据不发**：`startSend()` 只在 `sendDatalens[1]!=0` 的分支里调用 | `Ble_Activity.sendDataThread` | 入队完成后无条件调用 `startSend()`（参考实现已修复，并加了防重入） |
| 3 | **写回调丢失导致队列永久卡死**：原实现死等 `mSendState` | `BluetoothLeService.startSend` | 加超时保护（参考实现：最长等 1s） |
| 4 | `connectGatt` 四参数版（TRANSPORT_LE）需 API 23，工程 `minSdkVersion 18`，低版本会崩 | `BluetoothLeService.connect` | 按 `Build.VERSION` 分支调用 |
| 5 | `setCharacteristicNotification` 中描述符可能为 null（部分外设无 CCCD）导致 NPE | `BluetoothLeService` | 增加空判断 |
| 6 | `displayData()` 中 `data==null` 时仍调用 `new String(data,...)` 会 NPE | `Ble_Activity` | 接收处先判空 |
| 7 | 发送线程无异常兜底、`characteristic` 直接跨线程共享 | 多处 | 参考实现中统一收敛到 `BluetoothLeService` 内部串行队列 |

### 1.4 值得保留的设计

- **Service + 广播解耦**：连接层在后台 Service，业务层通过广播拿事件，页面销毁不影响连接；
- **写队列串行化**：BLE 写操作必须"写完一包、收到回调、再发下一包"，`mSendState`/队列的思路正确；
- **通知使能 = `setCharacteristicNotification` + 写 CCCD(0x2902)** 两步，缺一不可，原实现是完整的；
- **服务发现后延时 200ms 再操作特征值**：可规避一部分 GATT 133 错误，参考实现保留。

---

## 2. 提取清单与移植步骤

### 2.1 文件映射表

| 原工程文件 | 是否提取 | 新项目中的去向（参考实现包名 `com.example.gyroble`） |
|---|---|---|
| `service/BluetoothLeService.java` | ✅ 必提 | `com.example.gyroble.service.BluetoothLeService`（清理版，见 `docs/reference/`） |
| `ui/Ble_Activity.java` | ✅ 部分 | 其中的连接/收发逻辑拆入 `com.example.gyroble.GyroBleManager`；UI 代码丢弃，由宿主工程自己写 |
| `MainActivity.java` 扫描部分 | ⭕ 按需 | `com.example.gyroble.GyroBleScanner` |
| `toolkit/HexUtils.java` | ✅ 建议 | 改包名直接复制（调试日志用） |
| `toolkit/ParseLeAdvData.java` / `BluetoothMessage.java` | ⭕ 按需 | 仅当需要解析广播包/封装扫描结果时复制 |
| `ui/BasActivity.java` / `DebugActivity.java` | ❌ | 不提取 |

### 2.2 步骤一：复制核心文件

将 `docs/reference/java/com/example/gyroble/` 下 4 个文件复制到目标工程（按各自包名调整 `package` 与 `import`）：

```
com/example/gyroble/
├── GyroBleManager.java          // 业务门面：连接 + 5 条陀螺仪命令 + 接收回调
├── GyroBleScanner.java          // 扫描器（可选）
├── protocol/
│   └── GyroProtocol.java        // 自定义协议组帧/解帧（纯 Java）
└── service/
    └── BluetoothLeService.java  // BLE 连接层后台服务
```

依赖要求：`minSdkVersion ≥ 18`（BLE 最低要求），建议 `21+`；无第三方库依赖，仅 `androidx.annotation`（可选）。
参考实现全部使用**匿名内部类**编写，未使用 Lambda 表达式，兼容 Java 7 源码级别的旧工具链，无需额外配置即可编入目标工程。

### 2.3 步骤二：AndroidManifest 注册

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- BLE 硬件特性声明 -->
    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true"/>

    <!-- Android 11(API30) 及以下 -->
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30"/>
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30"/>
    <!-- Android 6~11 扫描 BLE 需要定位权限 -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
                     android:maxSdkVersion="30"/>

    <!-- Android 12(API31) 及以上 -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
                     android:usesPermissionFlags="neverForLocation"/>
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>

    <application ...>
        <!-- 必须注册 BLE 后台服务 -->
        <service android:name="com.example.gyroble.service.BluetoothLeService"
                 android:enabled="true"/>
        ...
    </application>
</manifest>
```

### 2.4 步骤三：运行时权限申请（宿主 Activity 中）

```java
private static final int REQ_BLE = 1001;

private String[] requiredBlePermissions() {
    if (Build.VERSION.SDK_INT >= 31) {
        // Android 12+：扫描用 BLUETOOTH_SCAN，连接用 BLUETOOTH_CONNECT
        return new String[]{Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT};
    }
    // Android 6~11：扫描需要定位权限，且系统"位置信息"开关必须打开
    return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
}

private void requestBlePermissions() {
    List<String> need = new ArrayList<>();
    for (String p : requiredBlePermissions()) {
        if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
    }
    if (!need.isEmpty()) {
        requestPermissions(need.toArray(new String[0]), REQ_BLE);
    }
}
```

各版本兼容要点一览：

| Android 版本 | API | 关键点 |
|---|---|---|
| 4.3 ~ 5.1 | 18~22 | 无运行时权限；`connectGatt` 只有 3 参版本 |
| 6.0 ~ 11 | 23~30 | 扫描需 `ACCESS_FINE_LOCATION` 运行时权限 + 系统定位开关打开；可用 `requestMtu`、`TRANSPORT_LE` |
| 12+ | 31+ | 扫描/连接分别需 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` 运行时权限；声明 `neverForLocation` 可免除定位权限 |
| 14+ | 34+ | 动态注册广播接收器必须指定 `RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED`（参考实现已处理） |

### 2.5 步骤四：修改目标 UUID

演示工程使用的 `0000ffe1-...`（服务 `0000ffe0-...`）是常见串口透传模块的默认 UUID。
**接入实际陀螺仪模块时，必须先确认固件实际提供的服务/特征值 UUID**（可用 nRF Connect 等工具查看），
然后在连接前调用：

```java
manager.setUuids("0000xxx0-0000-1000-8000-00805f9b34fb",  // 服务 UUID
                 "0000xxx1-0000-1000-8000-00805f9b34fb"); // 数据特征值 UUID
```

要求该特征值至少具备 `WRITE`（或 `WRITE_NO_RESPONSE`）+ `NOTIFY` 属性。

---

## 3. 陀螺仪自定义协议设计

### 3.1 设计原则

1. **二进制帧协议**：适合嵌入式固件实现，解析开销小；
2. **双字节帧头 + 长度 + 校验**：可抗线路噪声、可断帧重同步；
3. **命令—应答机制**：所有主机下行命令，设备均回通用应答帧，上层可据此做状态机与超时重试；
4. **连续数据用通知（Notification）推送**：陀螺仪导航数据频率高（建议 10~50 Hz），主机不轮询；
5. **帧长 ≤ 20 字节**：默认 ATT MTU=23（载荷 20），不依赖 MTU 协商也能工作；最长帧 13 字节，留有余量；
6. **字节序统一大端**，避免固件与 App 互相猜。

### 3.2 帧格式

```
┌────────┬────────┬───────┬───────┬─────────────────┬───────┐
│ HEAD1  │ HEAD2  │  LEN  │  CMD  │   DATA[LEN]     │  CHK  │
│  0xAA  │  0x55  │ 1字节 │ 1字节 │   0~64 字节     │ 1字节 │
└────────┴────────┴───────┴───────┴─────────────────┴───────┘
```

| 字段 | 长度 | 说明 |
|---|---|---|
| HEAD1 | 1 | 固定 `0xAA` |
| HEAD2 | 1 | 固定 `0x55` |
| LEN | 1 | **DATA 域的字节数**（不含 CMD、CHK）；LEN=0 表示无数据命令 |
| CMD | 1 | 命令码，见 3.3 |
| DATA | LEN | 数据域，各命令定义见 3.4 |
| CHK | 1 | 校验和 = `(LEN + CMD + DATA[0] + … + DATA[LEN-1]) & 0xFF`（模 256 累加和） |

整帧长度 = `5 + LEN`。最长命令帧（设置纬度）9 字节，最长数据帧（导航数据）13 字节，
均在默认 20 字节单包内，**无需分包**。

### 3.3 命令定义

| CMD | 名称 | 方向 | DATA | 说明 |
|---|---|---|---|---|
| `0x01` | 设置纬度 | 主机→设备 | 4 字节 | 寻北前置条件；设备回 ACK |
| `0x02` | 寻北 | 主机→设备 | 无 | 设备回 ACK 后开始寻北解算（耗时数十秒量级），完成后以 `0x06` 帧上报结果 |
| `0x03` | 进入导航 | 主机→设备 | 无 | 设备回 ACK 后开始连续推送 `0x04` 导航数据帧 |
| `0x04` | 导航数据 | 设备→主机 | 8 字节 | 连续推送（通知），周期建议 20~100 ms；**无应答** |
| `0x05` | 退出导航 | 主机→设备 | 无 | 设备回 ACK 并停止推送 `0x04` |
| `0x06` | 寻北结果 | 设备→主机 | 3 字节 | 寻北结束后的异步上报 |
| `0x7F` | 通用应答 | 设备→主机 | 2 字节 | 对 `0x01/0x02/0x03/0x05` 的应答 |

### 3.4 数据域编码

**0x01 设置纬度**（DATA 4 字节）：

| 偏移 | 长度 | 编码 | 说明 |
|---|---|---|---|
| 0 | 4 | int32 大端 | 纬度 = 数值 × 10⁻⁶ 度；北纬为正、南纬为负，有效范围 ±90°（±90,000,000） |

**0x04 导航数据**（DATA 8 字节）：

| 偏移 | 长度 | 编码 | 说明 |
|---|---|---|---|
| 0 | 2 | uint16 大端 | 航向角，单位 0.01°，范围 0~35999（0~359.99°） |
| 2 | 2 | int16 大端 | 俯仰角，单位 0.01°，范围 ±9000（±90.00°） |
| 4 | 2 | int16 大端 | 横滚角，单位 0.01°，范围 ±18000（±180.00°） |
| 6 | 1 | 状态字 | bit0 寻北完成；bit1 导航输出有效；bit2 陀螺自检通过；bit3 纬度已设置 |
| 7 | 1 | 帧序号 | 0~255 滚动递增，主机可据此检测丢帧 |

**0x06 寻北结果**（DATA 3 字节）：

| 偏移 | 长度 | 编码 | 说明 |
|---|---|---|---|
| 0 | 1 | 结果码 | `0x00` 成功 / `0x01` 超时 / `0x02` 被中断 / `0x04` 硬件故障 |
| 1 | 2 | uint16 大端 | 寻北方位角，单位 0.01°，仅结果码 `0x00` 时有效 |

**0x7F 通用应答**（DATA 2 字节）：

| 偏移 | 长度 | 说明 |
|---|---|---|
| 0 | 1 | 被应答命令的 CMD（`0x01/0x02/0x03/0x05`） |
| 1 | 1 | 结果码，见下表 |

**结果码定义**：

| 结果码 | 含义 |
|---|---|
| `0x00` | 成功 |
| `0x01` | 未设置纬度（未执行 0x01 就寻北/导航） |
| `0x02` | 状态错误（如重复进入导航、寻北过程中收到其他命令） |
| `0x03` | 参数超范围（如纬度超出 ±90°） |
| `0x04` | 传感器/硬件故障 |
| `0xFF` | 其他错误 |

### 3.5 帧示例

| 示例 | 报文（HEX） |
|---|---|
| 设置纬度 39.904200°（39904200 = 0x0260E3C8） | `AA 55 04 01 02 60 E3 C8 12` |
| 寻北 | `AA 55 00 02 02` |
| 进入导航 | `AA 55 00 03 03` |
| 退出导航 | `AA 55 00 05 05` |
| 应答：设置纬度成功 | `AA 55 02 7F 01 00 82` |
| 应答：未设置纬度即寻北（错误 0x01） | `AA 55 02 7F 02 01 84` |
| 导航数据：航向 123.45°、俯仰 -5.00°、横滚 2.50°、状态 0x03、序号 0x07 | `AA 55 08 04 30 39 FE 0C 00 FA 03 07 83` |
| 寻北结果：成功，方位角 122.50° | `AA 55 03 06 00 2F DA 12` |

> 校验和示例（导航数据帧）：`08 + 04 + 30 + 39 + FE + 0C + 00 + FA + 03 + 07 = 0x283`，取低 8 位 = `0x83`。

### 3.6 交互时序

```
 主机 (Android)                                设备 (陀螺仪)
      │                                           │
      │── BLE 连接 → 发现服务 → 使能通知 ────────▶│
      │                                           │
      │── 0x01 设置纬度 ─────────────────────────▶│
      │◀──────────────── 0x7F ACK(0x01, 0x00) ───│
      │                                           │
      │── 0x02 寻北 ─────────────────────────────▶│
      │◀──────────────── 0x7F ACK(0x02, 0x00) ───│
      │                            （寻北解算中，数十秒量级）
      │◀──────── 0x06 寻北结果(结果码+方位角) ────│
      │                                           │
      │── 0x03 进入导航 ─────────────────────────▶│
      │◀──────────────── 0x7F ACK(0x03, 0x00) ───│
      │◀────────── 0x04 导航数据（连续 10~50Hz）──│
      │◀────────── 0x04 导航数据 ─────────────────│
      │                …                          │
      │── 0x05 退出导航 ─────────────────────────▶│
      │◀──────────────── 0x7F ACK(0x05, 0x00) ───│
      │             （设备停止推送 0x04）          │
```

主机侧建议的业务状态机：`已连接 → 已设纬度 → 寻北中 → 寻北完成 → 导航中`，
收到非当前状态允许的应答/上报时按 `RESULT_STATE_ERROR` 处理。主机对每条下行命令
建议设置 **1~2 秒应答超时**，超时重发最多 2~3 次。

### 3.7 吞吐量与实时性说明

- 导航数据帧 13 字节 × 50 Hz ≈ 650 B/s，BLE 带宽（即使默认连接间隔）完全够用；
- 若需要更高频率，可在连接后调用 `BluetoothGatt.requestConnectionPriority(CONNECTION_PRIORITY_HIGH)`
  缩短连接间隔（API 21+）；
- 接收侧必须做**流式解帧**：一次 notification 可能是半帧，也可能粘连多帧，
  参考实现的 `GyroProtocol.Parser` 状态机已处理断帧续接；
- 主机可通过导航数据帧的**帧序号**统计丢帧率（相邻序号差 > 1 即丢帧）。

---

## 4. 参考实现代码与使用方法

完整源码见仓库 `docs/reference/java/com/example/gyroble/`，共 4 个文件，可直接复制进目标工程。

### 4.1 分层结构

```
┌────────────────────────────────────────────────────────┐
│ 宿主 App（Activity/ViewModel）                          │
│   实现 GyroBleManager.Callback，调用业务命令             │
├────────────────────────────────────────────────────────┤
│ GyroBleManager（业务门面）                              │
│   连接生命周期 / 发命令 / 收帧分发 / 主线程回调          │
│ GyroBleScanner（扫描，可选）                            │
├────────────────────────────────────────────────────────┤
│ GyroProtocol（协议编解码，纯 Java）                     │
│   buildSetLatitude / buildFrame / Parser 状态机         │
├────────────────────────────────────────────────────────┤
│ BluetoothLeService（BLE 连接层 Service）                │
│   connectGatt / 服务发现 / 通知使能 / 写队列串行发送     │
└────────────────────────────────────────────────────────┘
```

### 4.2 BluetoothLeService（连接层，清理版）

相对原工程的关键修改（详见源码注释）：

```java
// 1) API23+ 才使用 TRANSPORT_LE 重载，低版本走 3 参重载
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    mBluetoothGatt = device.connectGatt(this, false, mGattCallback,
            BluetoothDevice.TRANSPORT_LE);
} else {
    mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
}

// 2) 使能通知必须写 CCCD(0x2902)，并判空
BluetoothGattDescriptor clientConfig = characteristic.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG);
if (clientConfig == null) return;
clientConfig.setValue(enabled ? ENABLE_NOTIFICATION_VALUE : DISABLE_NOTIFICATION_VALUE);
mBluetoothGatt.writeDescriptor(clientConfig);

// 3) 写队列串行发送：等 onCharacteristicWrite 回调再发下一包，带 1s 超时保护
public void writeCharacteristic(byte[] bytes) { ... 入队 ... }
public void startSend(BluetoothGattCharacteristic characteristic) { ... 防重入的发送线程 ... }
```

### 4.3 GyroProtocol（协议层）

组帧：

```java
byte[] f1 = GyroProtocol.buildSetLatitude(39.9042);      // AA 55 04 01 02 60 E3 C8 12
byte[] f2 = GyroProtocol.buildStartNorthSeek();          // AA 55 00 02 02
byte[] f3 = GyroProtocol.buildEnterNavigation();         // AA 55 00 03 03
byte[] f4 = GyroProtocol.buildExitNavigation();          // AA 55 00 05 05
```

解帧（状态机，跨包累积）：

```java
GyroProtocol.Parser parser = new GyroProtocol.Parser();
parser.feed(notificationBytes, new GyroProtocol.Parser.FrameListener() {
    @Override
    public void onFrame(byte cmd, byte[] data) {
        if (cmd == GyroProtocol.CMD_NAV_DATA) {
            GyroProtocol.NavData nav = GyroProtocol.parseNavData(data);
        } else if (cmd == GyroProtocol.CMD_ACK) {
            int[] ack = GyroProtocol.parseAck(data);   // [被应答CMD, 结果码]
        } else if (cmd == GyroProtocol.CMD_NORTH_SEEK_RESULT) {
            int[] r = GyroProtocol.parseNorthSeekResult(data); // [结果码, 方位角x100]
        }
    }
});
```

> `GyroProtocol` 是纯 Java 类，建议为它编写 JUnit 单元测试（组帧→解帧闭环、
> 半帧/粘包、错误校验丢弃等用例），协议改动时固件与 App 可用同一套用例验证。

### 4.4 GyroBleManager（业务门面）

对外只暴露 4 个命令方法与 1 个回调接口：

| 方法 | 对应协议命令 |
|---|---|
| `sendSetLatitude(double)` | 0x01 设置纬度 |
| `startNorthSeek()` | 0x02 寻北 |
| `enterNavigation()` | 0x03 进入导航 |
| `exitNavigation()` | 0x05 退出导航 |

回调（均在主线程）：

| 回调 | 触发时机 |
|---|---|
| `onConnected()` | GATT 连接成功（服务尚未发现） |
| `onServiceReady()` | 特征值就绪、通知已使能，**可以开始下发命令** |
| `onAck(cmd, result)` | 收到 0x7F 通用应答 |
| `onNorthSeekResult(result, headingDeg)` | 收到 0x06 寻北结果 |
| `onNavData(nav)` | 收到一帧 0x04 导航数据 |
| `onDisconnected()` / `onError(msg)` | 断开 / 错误 |

内部已自动完成：绑定服务 → `connectGatt` → 服务发现 → 按配置 UUID 定位特征值 →
使能通知 → 发送时组帧、分包（默认 20 字节）、串行写队列。

### 4.5 扫描与 deviceAddress 的获取

`GyroBleManager.connect(deviceAddress)` 的参数是模块的**蓝牙 MAC 地址**
（形如 `"AA:BB:CC:DD:EE:FF"` 的 6 组十六进制字符串，不是设备名、也不是 UUID），
有三种获取方式：

| 方式 | 来源 | 适用场景 |
|---|---|---|
| ① 扫描获取（推荐） | `onDeviceFound` 回调中 `device.getAddress()` | 标准流程，见下方代码 |
| ② 已配对设备列表 | `BluetoothAdapter.getBondedDevices()` 遍历 | 仅已绑定的设备；很多 BLE 外设不配对 |
| ③ 硬编码 | 模块标签/串口 AT 指令/系统蓝牙设置中查 MAC | 仅开发调试；生产环境不推荐 |

> 注意：部分 BLE 外设使用**周期性轮换的随机地址**，之前扫到的地址可能失效。
> 生产环境建议每次连接前先扫描，或与模块执行绑定；
> 演示工程 `MainActivity` 即采用方式①，扫描后经 Intent 把地址传给连接页。

```java
GyroBleScanner scanner = new GyroBleScanner(context);
scanner.startScan("GYRO-", 10_000, new GyroBleScanner.ScanListener() {
    @Override public void onDeviceFound(BluetoothDevice device, int rssi, String name) {
        // device.getAddress() 即 connect() 所需的 deviceAddress
        // 按地址去重后展示到列表，用户点击再发起连接
    }
    @Override public void onScanFailed(int errorCode) { /* 提示用户 */ }
});
```

**扫描→选择→连接的完整示例**（单个 Activity 内闭环）：

```java
public class DeviceSelectActivity extends Activity {

    private GyroBleScanner scanner;
    private GyroBleManager manager;
    private final Map<String, BluetoothDevice> found = new HashMap<String, BluetoothDevice>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ...布局初始化（如一个列表 + 一个"扫描"按钮）...

        scanner = new GyroBleScanner(this);
        manager = new GyroBleManager(this, gyroCallback); // 见 §4.6 回调实现
    }

    /** "扫描"按钮点击 */
    private void onScanClick() {
        if (!scanner.isBluetoothEnabled()) {
            // 引导用户打开蓝牙（ACTION_REQUEST_ENABLE）
            return;
        }
        found.clear();
        scanner.startScan("GYRO-", 10_000, new GyroBleScanner.ScanListener() {
            @Override
            public void onDeviceFound(BluetoothDevice device, int rssi, String name) {
                String address = device.getAddress();        // ← deviceAddress 来源
                if (!found.containsKey(address)) {
                    found.put(address, device);
                    // 刷新列表 UI：显示 name + address + rssi
                }
            }
            @Override
            public void onScanFailed(int errorCode) { /* 提示用户 */ }
        });
    }

    /** 列表中某设备被点击 */
    private void onDeviceClick(BluetoothDevice device) {
        scanner.stopScan();                                  // 连接前停止扫描，提高成功率
        manager.connect(device.getAddress());                // ← 填入 deviceAddress
    }

    @Override
    protected void onDestroy() {
        scanner.stopScan();
        manager.disconnect();
        super.onDestroy();
    }
}
```

### 4.6 宿主 Activity 完整使用示例

```java
public class GyroActivity extends Activity {

    private GyroBleManager manager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ...布局与控件初始化...

        manager = new GyroBleManager(this, new GyroBleManager.Callback() {
            @Override public void onConnected() { toast("已连接，正在准备服务..."); }

            @Override public void onServiceReady() {
                // 特征值就绪：先设置纬度（以北京 39.9042°N 为例）
                manager.sendSetLatitude(39.9042);
            }

            @Override public void onAck(int ackedCmd, int result) {
                if (result != GyroProtocol.RESULT_OK) {
                    toast("命令 0x" + Integer.toHexString(ackedCmd)
                            + " 执行失败，错误码 0x" + Integer.toHexString(result));
                    return;
                }
                switch (ackedCmd) {
                    case GyroProtocol.CMD_SET_LATITUDE:
                        manager.startNorthSeek();               // 纬度已设 → 开始寻北
                        break;
                    case GyroProtocol.CMD_START_NORTH_SEEK:
                        toast("寻北已开始，请保持设备静止...");
                        break;
                    case GyroProtocol.CMD_ENTER_NAVIGATION:
                        toast("已进入导航");
                        break;
                    case GyroProtocol.CMD_EXIT_NAVIGATION:
                        toast("已退出导航");
                        break;
                }
            }

            @Override public void onNorthSeekResult(int result, double headingDeg) {
                if (result == GyroProtocol.SEEK_OK) {
                    toast("寻北完成，方位角 " + headingDeg + "°");
                    manager.enterNavigation();                  // 寻北成功 → 进入导航
                } else {
                    toast("寻北失败：" + result);
                }
            }

            @Override public void onNavData(GyroProtocol.NavData nav) {
                // 高频回调（10~50Hz），更新航向/俯仰/横滚显示
                tvHeading.setText(String.format("航向 %.2f°", nav.headingDeg));
                tvPitch.setText(String.format("俯仰 %.2f°", nav.pitchDeg));
                tvRoll.setText(String.format("横滚 %.2f°", nav.rollDeg));
            }

            @Override public void onDisconnected() { toast("连接已断开"); }
            @Override public void onError(String message) { toast(message); }
        });

        // 若实际硬件不是 FFE0/FFE1 透传模块，先替换 UUID：
        // manager.setUuids("0000fff0-...", "0000fff1-...");

        // deviceAddress = 模块 MAC 地址，获取方式见 §4.5：
        // 扫描回调 device.getAddress()（推荐）/ 已配对列表 / 调试期硬编码。
        // 若按 §4.5 在同一页面完成扫描选择，直接用所选设备的 getAddress() 即可。
        String deviceAddress = getIntent().getStringExtra("DEVICE_ADDRESS");
        manager.connect(deviceAddress);
    }

    private void exitNavigation() {
        if (manager.isReady()) manager.exitNavigation();
    }

    @Override
    protected void onDestroy() {
        manager.disconnect();   // 务必释放，避免泄漏 Service 与 GATT 连接
        super.onDestroy();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
```

---

## 5. 联调测试清单

### 5.1 协议层（无需硬件，纯软件可测）

- [ ] 组帧→解帧闭环：5 条命令与 3 类上报帧逐一验证字节完全一致（对照 3.5 帧示例）
- [ ] 校验和错误帧被丢弃且不影响后续帧解析
- [ ] 半帧喂入（把一帧拆成 1 字节/次喂给 Parser）能正确还原
- [ ] 粘包（两帧合并成一个 byte[] 喂入）能解出两帧
- [ ] 数据中出现 `AA 55` 不误判为新帧头
- [ ] 纬度边界值：+90 / -90 / 0 / 超界抛异常
- [ ] 负角度编码：俯仰 -5.00° 编码为 `FE 0C`，解码回 -5.00

### 5.2 真机联调

- [ ] 扫描能按名称前缀过滤出陀螺仪模块（Android 12+ 与 6~11 各测一台）
- [ ] 连接后 `onServiceReady` 回调正常，UUID 与实际模块一致（nRF Connect 复核）
- [ ] 设置纬度收到 ACK(0x01,0x00)；故意不设纬度直接寻北，设备应回错误码 0x01
- [ ] 寻北：ACK 立即返回，数十秒后收到 0x06 上报，方位角与基准对比
- [ ] 进入导航：导航数据连续到达，观察帧序号连续性（丢帧率应 < 1%）
- [ ] 退出导航：0x04 帧停止推送
- [ ] 导航中关闭手机蓝牙 → `onDisconnected` 回调 → 重连后流程可恢复
- [ ] 页面退出后无 Service 泄漏（`adb shell dumpsys activity services` 检查）

---

## 6. 常见问题（FAQ)

**Q1：连接回调 status=133 怎么办？**
经典 GATT 错误，常见诱因：上次连接未正常 `close()`、GATT 操作过快、未指定 `TRANSPORT_LE`。
处理：断开时依次 `disconnect()` → `close()`；服务发现后延时几百毫秒再操作特征值；
连接失败时等待 1~2 秒重试；必要时重启蓝牙适配器。

**Q2：发送数据设备收不全？**
确认：① 写操作是否串行（等上一包回调再发下一包，参考实现已保证）；
② 单包是否超过 20 字节（未协商 MTU 时）；③ 相邻包之间加 5~20ms 间隔；
④ 特征值写类型与固件一致（WRITE / WRITE_NO_RESPONSE）。

**Q3：使能了通知却收不到数据？**
检查是否写了 CCCD(0x2902) 描述符（只调 `setCharacteristicNotification` 不够）；
确认该特征值具备 `NOTIFY` 属性；确认设备端在相应命令后确实开始推送。

**Q4：中文设备名乱码？**
部分国产模块广播名是 GBK 编码，原工程 `MainActivity` 用 `new String(name, "GBK")` 处理，
可参考 `ParseLeAdvData.adv_report_parse()` 的解析方式。

**Q5：如何提高导航数据频率/吞吐？**
① `requestMtu(247)` 协商更大 MTU（需固件支持，成功后单包 = MTU-3）；
② `requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` 缩短连接间隔；
③ 协议帧本身 ≤ 13 字节，通常无需调整。

**Q6：能否不用 Service + 广播，直接回调？**
可以。广播方式与原工程一致、改动最小；若宿主项目有 MVVM 架构，可在 `GyroBleManager`
内部把广播接收替换为直接持有 `BluetoothGattCallback`，协议层与业务命令接口不变。

**Q7：这套协议如何与固件团队对齐？**
`GyroProtocol` 是纯 Java 类，可直接作为"协议参考实现"提供给固件工程师；
固件按 3.2~3.5 节实现编解码，双方共用第 5.1 节测试用例交叉验证即可。

---

*文档与参考实现基于分支 `arena/01a09ff4-ble-tool` 生成；参考代码位于 `docs/reference/`。*
