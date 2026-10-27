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
- 🧲 **引体向上**——次数（只弯手臂没上去不计数）
- 🧱 **靠墙蹲**——坚持时间 + 稳定度（大腿没水平 / 上身离开墙面不计时）
- 🏃 **跑步计步**——步数与步频（左右脚交替离地，站着晃动不计数）

识别层提供抗抖动滤波、尺度归一化与视角自适应；不同机位、光照和设备下仍需实测。
共 10 个动作：倒立、靠墙倒立、折凳俯卧撑、
俯卧撑、不标准俯卧撑、平板支撑、深蹲、引体向上、靠墙蹲、跑步计步。

同时支持：
- **回放**：视频回放（叠加骨架）+ 纯骨架动画回放，支持进度拖动与 0.5x~2x 倍速；设备上的时空对齐仍需验证
- **历史记录**：按动作筛选、缩略图、成绩、删除
- **账号切换**：多人共用一台手机时，训练数据彼此隔离
- **个人校准**：俯卧撑（含折凳/不标准）、深蹲、引体向上可将当前最佳幅度设为个人目标，点击即记一次；按账号和动作保存、可重置

### 骨骼标记与场景提示

1. 进入动作的训练页后，即使尚未开始计数也可以先看标记、调整机位；说明挡住画面时点「收起说明」。
2. 「标记：开/关」控制详细标记，关闭后基本骨架仍显示：绿点为置信度 ≥50%，黄点为 30–49%，
   旁边显示关键点名称和估计置信度；紫圈表示该点的新位置尚未被滤波器采纳，旧位置可能短暂保留。没有点可能是遮挡、出画或置信度过低。
3. 画面顶部会提示关键点缺失、出画、过小、靠近边缘等。置信度是模型对关键点入画的估计，**不是识别准确率**；
   多人同框时没有身份追踪，建议仅一人入镜。长时间无新帧时旧骨架会被清除；帧间隔过大时也会提示卡顿。
4. 历史回放也可开关详细标记；它展示的是**当时保存的滤波后骨架**，不是原始识别点，
   因此不显示训练时的紫色待定圈。视频和骨架的起点、前摄镜像仍需真机验证。

训练判定会屏蔽画面外的关键点，对持续的大跳变等待稳定后重新捕获，并优先使用同侧可信躯干；
倒立需看到抬起的脚踝，靠墙蹲可在一侧膝点被遮挡时使用可信侧。长时间丢帧或关键关节缺失时不会把空白当作已观察到的动作。详见 [功能说明](docs/fitness-module.md)。

### 个人校准怎么用

开始支持校准的动作后，先回到起始位，再保持在当前能够做到的最好位置，点击「**个人校准 +1**」
（可请同伴帮忙点击；独自训练可长按按钮，5 秒后采样，倒计时内点按可取消）。确认后立即记一次，并保存当前账号、该动作及折凳变体的目标；
回到起始位才能开始下一次。以后达到个人目标即可计数；在「账号 → 个人校准设置」可查看或恢复默认。
校准并不意味着动作标准或安全，原有姿势提醒仍会显示。计时类和跑步计步暂不支持此按钮。

功能说明、判定阈值、数据格式与已知限制详见 **[docs/fitness-module.md](docs/fitness-module.md)**；
判定算法的改进方向与实测证据见 **[docs/algorithm-plan.md](docs/algorithm-plan.md)**；
后续版本计划见 **[ROADMAP.md](ROADMAP.md)**。

## 构建

```bash
# Windows
gradlew.bat :app:testDebugUnitTest    # 动作判定单元测试（纯 JVM，无需手机）
gradlew.bat :app:assembleDebug        # 编译 APK
gradlew.bat :app:connectedDebugAndroidTest  # 可选：连接测试设备验证账号/历史持久化

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
- 手边没有 SDK 时，可以先跑一遍离线符号自查（需要 Python 3 + `pip install javalang`）：
  ```bash
  pip install javalang
  python3 tools/java_static_check.py
  ```
  它会检查“找不到符号”（未定义的本地方法/字段、`类名.成员`、`变量.成员`、`import com.zxw...`）、
  部分方法及本仓库构造器的参数个数、常量是否拼错等；**不检查类型兼容性或 Android API**，不能替代真正的编译。
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
