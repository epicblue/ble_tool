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

- 🤸 **倒立**——坚持时间 + 稳定度
- 🧗 **靠墙倒立**——坚持时间 + 贴墙检测 + 稳定度
- 🪑 **折凳俯卧撑**——双手撑凳（上斜）/ 双脚垫凳（下斜）
- 💪 **俯卧撑**——有效次数
- ⚠️ **不标准俯卧撑**——不标准次数与原因（幅度不足 / 塌腰撅臀 / 跪姿）
- 🧘 **平板支撑**——坚持时间，塌腰时暂停计时并提醒
- 🏋 **深蹲**——次数（只抖膝不下蹲不计数）
- 🧲 **引体向上**（实验性）——次数（只弯手臂没上去不计数）

识别层做了抗抖动滤波、尺度无关判定（手机远近不影响阈值）与视角自适应
（正对/侧对镜头都能用，不再强制侧身）。

同时支持：
- **回放**：视频回放（叠加同步骨架）+ 纯骨架动画回放，支持进度拖动与 0.5x~2x 倍速
- **历史记录**：按动作筛选、缩略图、成绩、删除
- **账号切换**：多人共用一台手机时，训练数据彼此隔离

功能说明、判定阈值、数据格式与已知限制详见 **[docs/fitness-module.md](docs/fitness-module.md)**；
后续版本计划见 **[ROADMAP.md](ROADMAP.md)**。

## 构建

```bash
# Windows
gradlew.bat :app:testDebugUnitTest    # 动作判定单元测试（纯 JVM，无需手机）
gradlew.bat :app:assembleDebug        # 编译 APK

# macOS / Linux
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

- **先配置 SDK 路径**：复制 `local.properties.example` 为 `local.properties`，
  把 `sdk.dir` 改成自己的 SDK 目录，例如 `sdk.dir=E:/Android/Sdk`
- **Android Studio Hedgehog (2023.1)+，Gradle JDK 必须是 17 或 21**
  （Settings → Build Tools → Gradle → Gradle JDK → 选 `jbr-17`）
- AGP 8.7.3、Gradle 8.10.2、compileSdk 34、targetSdk 34、minSdk 21
- 需要 SDK Platform 34 + Build-Tools 34.0.0（Studio 会提示自动安装）
- 依赖：CameraX 1.1.0、ML Kit Pose Detection 18.0.0（离线模型，画面不上传）

> **不会覆盖手机上已安装的正式版**：debug 包的 `applicationId` 是 `com.zxw.dev`
> （见 `app/build.gradle` 的 `applicationIdSuffix '.dev'`），安装后与正式版并存，
> 桌面显示为「BLE助手·测试」。只有 `assembleRelease` 生成的包才会替换正式版。

> 遇到 `No matching variant of com.android.tools.build:gradle` 这类报错，基本都是
> **Gradle 用的 JDK 版本不对**（JDK 8/11 不行，需要 17/21）。
> 逐步排查见 [docs/fitness-module.md 第 7.1 节](docs/fitness-module.md#71-常见构建错误)。

## 目录

```
app/src/main/java/com/zxw/
├── MainActivity.java          # 首页（BLE 扫描 + 体能训练入口）
├── service/ toolkit/ ui/      # 原有 BLE 相关代码
└── fitness/                   # 新增：摄像头姿态捕捉模块
    ├── model/ pose/ analysis/ camera/ data/ util/ ui/
```
