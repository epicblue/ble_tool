# 蓝牙 BLE 助手 · 附体能训练模块

一个基于 Android（Java）的 BLE 调试小工具（扫描/连接/读写特征值），
并在其上新增了**摄像头人体姿态捕捉**的体能训练模块。

## 主要功能

### BLE 调试（原有）
- 扫描周边 BLE 设备、查看信号强度
- 连接设备、查看服务与特征值、收发数据
- 长按「扫描设备」进入调试页

### 体能训练（新增）
用摄像头实时识别 33 个人体关键点，自动统计并回放以下动作：

- 🤸 **倒立**——坚持时间
- 🧗 **靠墙倒立**——坚持时间 + 贴墙检测
- 🪑 **折凳俯卧撑**——双手撑凳（上斜）/ 双脚垫凳（下斜）
- 💪 **俯卧撑**——有效次数
- ⚠️ **不标准俯卧撑**——不标准次数与原因（幅度不足 / 塌腰撅臀 / 跪姿）

同时支持：
- **回放**：视频回放（叠加同步骨架）+ 纯骨架动画回放，支持进度拖动与 0.5x~2x 倍速
- **历史记录**：按动作筛选、缩略图、成绩、删除
- **账号切换**：多人共用一台手机时，训练数据彼此隔离

功能说明、判定阈值、数据格式与已知限制详见 **[docs/fitness-module.md](docs/fitness-module.md)**。

## 构建

```bash
./gradlew :app:assembleDebug          # 编译 APK
./gradlew :app:testDebugUnitTest      # 动作判定单元测试（纯 JVM）
```

- Android Studio Flamingo+ / JDK 11+
- AGP 7.4.2、Gradle 7.6.4、compileSdk 33、minSdk 21
- 依赖：CameraX 1.1.0、ML Kit Pose Detection 18.0.0（离线模型，画面不上传）

## 目录

```
app/src/main/java/com/zxw/
├── MainActivity.java          # 首页（BLE 扫描 + 体能训练入口）
├── service/ toolkit/ ui/      # 原有 BLE 相关代码
└── fitness/                   # 新增：摄像头姿态捕捉模块
    ├── model/ pose/ analysis/ camera/ data/ util/ ui/
```
