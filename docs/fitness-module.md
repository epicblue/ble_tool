# 体能训练 · 摄像头姿态捕捉模块

在原有 BLE 助手的基础上新增的运动记录模块：用手机摄像头实时识别人体关键点，自动统计
**倒立 / 靠墙倒立坚持时间**、**折凳俯卧撑 / 俯卧撑 / 不标准俯卧撑个数**，并把每次训练
（视频 + 骨架数据）保存下来，可以随时回放；训练数据按账号隔离，支持账号切换。

入口：App 首页底部的「体能训练 · 摄像头动作捕捉」按钮。

---

## 1. 功能一览

| 功能 | 说明 |
| --- | --- |
| 动作识别 | Google ML Kit Pose Detection（离线，画面不出手机），CameraX 负责预览与录像 |
| 倒立 | 身体倒置 + 躯干接近竖直 + 保持稳定才计时；记录每组时长与最久一组 |
| 靠墙倒立 | 同倒立，倾斜容忍略放宽，并统计「贴墙」比例（双脚横向几乎不动视为贴墙） |
| 折凳俯卧撑 | 支持两种姿势：双手撑凳（上斜）、双脚垫凳（下斜） |
| 俯卧撑 | 肘部下放到约 90°、身体成一条直线才计有效次数 |
| 不标准俯卧撑 | 专门统计不标准次数，并给出原因：幅度不足 / 塌腰撅臀 / 跪姿 |
| 实时反馈 | 骨骼叠加显示、深度进度条、语音式文字提示（"塌腰，请收紧核心"） |
| 回放 | ① 视频回放（mp4，叠加同步骨架）② 纯骨架动画回放（在没有视频或视频损坏时兜底） |
| 回放控制 | 播放/暂停、拖动进度条、0.5x/1x/1.5x/2x 倍速、同动作记录上一条/下一条 |
| 历史记录 | 按动作筛选、缩略图、成绩、视频/骨架标记、删除（同时删除视频与骨架文件） |
| 账号 | 新建 / 重命名 / 删除 / 切换；每位使用者的历史记录、视频完全独立 |

---

## 2. 使用步骤

1. 打开 App → 底部「体能训练」。
2. 点右上角账号卡片可以切换/新建账号。
3. 选择动作。折凳俯卧撑会先让你选「双手撑凳」或「双脚垫凳」。
4. 把手机**竖直固定**（靠墙、三脚架均可），人离手机 3~4 米，**侧对镜头**，
   保证全身在画面内。
5. 点「开始训练」：开始录像、记录骨架并实时计数/计时。
6. 点「结束并保存」：弹出成绩，可直接进入回放。
7. 首页「历史记录」里可以按动作筛选、回放或删除。

> 识别全部在手机本地完成（ML Kit 离线模型），不会上传任何画面。

---

## 3. 判定规则

判定逻辑在 `com.zxw.fitness.analysis` 中，全部只依赖 33 个归一化关键点，
因此**实时训练与回放用的是同一套代码**，回放看到的次数/时长与录制时一致。

### 3.1 俯卧撑类（`PushUpCounter`）

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `TOP_ANGLE` | 152° | 肩-肘-腕夹角大于它视为"撑起" |
| `START_DOWN_ANGLE` | 132° | 低于它开始记录一次下放 |
| `DEPTH_ANGLE` | 100° | 到位深度；折凳"手撑凳"放宽到 106° |
| `PARTIAL_ANGLE` | 122° | 明显下放但不到位的区间，记为"幅度不足" |
| `HIP_STRAIGHT_ANGLE` | 150° | 肩-髋-踝夹角低于它判定塌腰/撅臀 |
| `KNEEL_ANGLE` | 120° | 膝角低于它判定跪姿 |
| `MIN_REP_MS` | 350ms | 短于它的往返视为抖动，不计数 |

- 阶段切换用平滑后的肘角，**深度判定用原始肘角**，避免平滑滞后把标准动作误判成"幅度不足"。
- 只有躯干接近水平（与水平夹角 < 55°）且双手低于肩部时才计数，站着挥手不会被误识别。
- 一次动作若姿势有问题（塌腰/撅臀/跪姿）→ 计入"不标准"；幅度不足 → 也计入"不标准"；
  两者都会累加"姿势提醒"次数。

### 3.2 倒立类（`HandstandTimer`）

- 倒置：肩膀低于髋部，且双脚高于肩。
- 竖直：躯干与竖直方向夹角 ≤ 25°（靠墙倒立放宽到 32°）。
- 稳定：最近 10 帧髋部横移 ≤ 0.05（靠墙 0.08）。
- 同时满足并维持 300ms 开始计时；条件破坏后 700ms 内恢复不中断计时；
  超过宽限期则结束本组。成绩取"最久的一组"，同时记录组数与累计时长。
- 靠墙倒立额外统计贴墙比例（双脚横移 ≤ 0.06 视为贴墙），占比 ≥ 50% 记为"检测到贴墙"。

> 阈值都可以在对应类顶部的常量处调整，建议先在真实场地上用 `Logcat`/界面提示校准。

---

## 4. 数据存储

所有数据都在 App 私有目录，卸载即清除：

```
files/fitness/<userId>/sessions.json      # 训练汇总（成绩、时间、文件名）
files/fitness/<userId>/media/<id>.mp4      # 录像（若设备支持三路同开）
files/fitness/<userId>/media/<id>.jpg      # 缩略图
files/fitness/<userId>/media/<id>_pose.dat # 骨架数据（二进制）
```

账号列表保存在 `SharedPreferences`（`fitness_users`），当前账号 key 为 `current_user`。

骨架文件格式（`PoseLogWriter` / `PoseLog`）：

```
int  magic = 0x504F5345 ("POSE")
int  version = 1
int  viewWidth            // 摆正后的分析画面宽（用于骨架与画面等比对齐）
int  viewHeight
重复 N 次:
  int   timeOffsetMs      // 相对第一帧
  byte  visible           // 是否检测到人体
  17 × float              // x, y, z, confidence（见 PoseFrame.LANDMARK_SUBSET）
```

只记录 17 个关键点即可画出完整骨架，一段 5 分钟的训练约 1~2 MB。

---

## 5. 回放实现

- 有视频：`PoseVideoView`（`TextureView` + `MediaPlayer`，自行管理以便支持倍速，并按宽高比测量）+ 覆盖层按 `videoView.getCurrentPosition()`
  查骨架帧，保证画面与骨架同步；拖动进度条/倍速都会重新对齐。
- 无视频：用 `SystemClock` 驱动时间轴，循环播放骨架动画。
- `ReplayTimeline` 在打开记录时把骨架帧整体喂给判定器一遍，预先算出每一帧的计数/计时与提示，
  拖动进度条时直接取值，不需要重新计算。

---

## 6. 代码结构

```
com.zxw.fitness
├── model/      ExerciseType 动作定义、PoseFrame 关键点帧、Session 训练记录、UserProfile 账号
├── pose/       PoseMath 几何计算（角度、倒置、倾斜、稳定度）
├── analysis/   PushUpCounter、HandstandTimer、WorkoutRecorder、ReplayTimeline、ExerciseAnalyzer
├── camera/     CameraController（预览+分析+录像）、SkeletonOverlay（骨架绘制）
├── data/       UserStore、SessionStore、PoseLog / PoseLogWriter
├── util/       TimeUtils
└── ui/         ExerciseListActivity、TrainingActivity、ReplayActivity、HistoryActivity、
                ProfileActivity、PoseVideoView
```

复用的关键点：`CameraController` 只负责"相机 → PoseFrame"，
`ExerciseAnalyzer` 只负责"PoseFrame → 成绩"，两者互不依赖，新增动作只要实现一个判定器。

---

## 7. 构建要求

| 项目 | 版本 |
| --- | --- |
| Android Studio | Hedgehog (2023.1) 或更新（AGP 8.7 需要） |
| Gradle | 8.10.2（wrapper 已指定） |
| Android Gradle Plugin | 8.7.3 |
| **JDK** | **17 或 21**（必须；JDK 8 / 11 会直接报错） |
| compileSdk / targetSdk | 34（需安装 SDK Platform 34 + Build-Tools 34.0.0） |
| minSdk | 21 |

首次构建会从 Maven 下载 CameraX 1.1.0 与 ML Kit Pose Detection（模型随 AAR 打包，
没有运行时下载，但 APK 体积会增加约 20 MB）。

```bash
# Windows
gradlew.bat :app:testDebugUnitTest    # 先跑动作判定单元测试（纯 JVM，不需要手机）
gradlew.bat :app:assembleDebug        # 再打 debug 包

# macOS / Linux
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

### 7.0 配置 Android SDK 路径（`local.properties`）

Gradle 需要知道 SDK 装在哪里。**推荐写在项目根目录的 `local.properties`**（已被 `.gitignore` 忽略，
只对你这台机器生效，不会提交到仓库）：

```properties
# 正斜杠最省事，不用管转义
sdk.dir=E:/Android/Sdk

# 或者用 Windows 原生写法，注意反斜杠在 properties 文件里是转义符，要写两次
sdk.dir=E:\Android\Sdk
```

仓库里提供了一份 `local.properties.example`，可以直接复制改名：

```bat
copy local.properties.example local.properties
```

其他两种方式（任选其一即可，优先级：`local.properties` > 环境变量 > Studio 设置）：

| 方式 | 做法 |
| --- | --- |
| 环境变量 | `set ANDROID_HOME=E:\Android\Sdk`（或 `ANDROID_SDK_ROOT`），重开终端生效 |
| Android Studio | `File → Settings → Languages & Frameworks → Android SDK` → 设置 *Android SDK Location* |

> 注意：`local.properties` 属于个人环境配置，**不要提交**（不同电脑路径不同，提交了会把别人的配置覆盖掉）。
> `settings.gradle` 里已加了 SDK 路径自检：找不到路径或目录里没有 `platforms` 时，会打印中文提示，
> 而不是只报一句 `SDK location not found`。

该目录下至少需要安装（`SDK Manager → SDK Platforms / SDK Tools`）：

- **Android SDK Platform 34**（对应 `compileSdk 34`）
- **Android SDK Build-Tools 34.0.0**
- **Android SDK Platform-Tools**（adb，装 APK 用）
- **Android SDK Command-line Tools**（可选，命令行构建用）

目录结构大致如下（用于自查）：

```
E:\Android\Sdk\
├── platforms\android-34\
├── build-tools\34.0.0\
├── platform-tools\adb.exe
└── cmdline-tools\
```

### 7.1 测试包与正式版共存（不覆盖已安装的程序）

Android 靠 **applicationId（包名）** 区分应用：包名相同就会被当成同一个 App。
仓库里的人 `defaultConfig.applicationId = "com.zxw"` 与正式版一致，为了测试时不覆盖它，
`app/build.gradle` 的 `debug` 构建类型里加了后缀：

```gradle
buildTypes {
    debug {
        applicationIdSuffix '.dev'      // 测试包 = com.zxw.dev
        versionNameSuffix '-dev'
    }
}
```

效果：

| 构建方式 | 包名 | 桌面名称 | 是否影响已安装的正式版 |
| --- | --- | --- | --- |
| `assembleDebug` / Studio 的 Run | `com.zxw.dev` | BLE助手·测试 | 不影响，两个 App 并存 |
| `assembleRelease` | `com.zxw` | 蓝牙BLE助手 | **会**替换正式版 |

- 两个包的数据目录完全独立，卸载测试包不会清掉正式版的 BLE 配置与训练数据。
- 桌面名称的差异来自 `app/src/debug/res/values/strings.xml`（debug 源集覆盖 `app_name`），
  删掉这个文件就只有包名不同、名字一样。
- 如果确实想让测试包就是正式包本身（覆盖安装），把 `applicationIdSuffix '.dev'` 那一行删掉。
- **安装命令**：`gradlew.bat installDebug`（或 Android Studio 直接 Run）。
  不要装 `app/build/outputs/apk/release/app-release.apk`，那会覆盖正式版。
- 如果之前已经装过同包名、不同签名的包，系统会报
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，先卸载旧的那个再装即可。

### 7.2 常见构建错误

**① `No matching variant of com.android.tools.build:gradle:...`**
Gradle 跑在 JDK 8（或 11）上，而 AGP 需要 JDK 17+。`settings.gradle` 已经内置了自检，
会直接打印中文修复步骤。修复方式：

- Android Studio：`Settings → Build, Execution, Deployment → Build Tools → Gradle`
  → **Gradle JDK** 选 `jbr-17`（Studio 自带）或本地 JDK 17 → Sync。
- 命令行（Windows）：
  ```bat
  set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
  gradlew.bat clean assembleDebug
  ```
- 只对本次生效：`gradlew.bat -Dorg.gradle.java.home="C:\Program Files\Android\Android Studio\jbr" clean assembleDebug`
- 永久生效（本机配置，勿提交）：在 `%USERPROFILE%\.gradle\gradle.properties`
  加 `org.gradle.java.home=C:\\Program Files\\Android\\Android Studio\\jbr`

**② `Unsupported class file major version 6x`**
JDK 太新（Gradle 8.10 最高支持 JDK 23）。切回 JDK 17 或 21。

**③ `Failed to find target with hash string 'android-34'`**
SDK Platform 34 没装：`Tools → SDK Manager → Android 34`，勾选 *SDK Platform* 与
*Android SDK Build-Tools 34*。

**④ `Plugin [id: 'com.android.application'] was not found`**
第一次同步时网络没连上 `google()` / `mavenCentral()`；挂上代理后
`File → Invalidate Caches / Restart` 再同步。

**⑤ 打开工程时提示 AGP 版本不兼容**
说明 Android Studio 太旧（低于 Hedgehog）。升级 Studio，或改回 AGP 7.4.2 + Gradle 7.6.4 +
JDK 17 这一组合（JDK 依旧是 17，不要用 8/11）。

**⑥ `Ble_Activity.java:537: 需要常量表达式 / constant expression required`（`case R.id.xxx`）**
AGP 8 起 `android.nonFinalResIds` 默认为 `true`，`R` 里的资源 id **不再是编译期常量**，
所以 `switch (v.getId()) { case R.id.send_btn: ... }` 会报"需要常量表达式"。
本项目已把 `Ble_Activity#onClick` 改成等价的 `if / else if`；以后新增代码同理，
不要对 `R.id` 用 `switch`。若确实想保留 switch 写法，可在 `gradle.properties` 里加：

```properties
android.nonFinalResIds=false
```

（但 AGP 9 会移除该开关，建议直接用 if-else。）

**⑦ 提示 `Could not install Gradle distribution` / wrapper 版本过旧**
仓库里的 `gradle-wrapper.jar` 是 2019 年的老文件，正常情况下仍能下载 8.10.2；若报错，
重新生成 wrapper（会同时更新 jar）：

```bash
gradlew.bat wrapper --gradle-version 8.10.2 --distribution-type bin
```

或者用 Android Studio 打开工程后点 `Sync`，让它自动补齐 wrapper。

单元测试（`app/src/test/java/com/zxw/fitness/PoseAnalyzerTest.java`）用合成关键点验证：
标准俯卧撑计数、幅度不足/塌腰/跪姿的识别、站立不误计数、抖动过滤、倒立计时与倾斜拒绝、
骨架文件读写、历史记录 key 稳定性。

---

## 8. 已知限制

> 这些限制的解决计划见 [ROADMAP.md](../ROADMAP.md)（v1.2 ~ v1.4 里程碑）。


1. **靠墙倒立**的"贴墙"是启发式判断（双脚横向是否稳定），不是真的检测墙面；
   建议手机侧向放置，让人与墙面都在画面里。
2. 视频与骨架的时间基准略有差异（录制启动约几十到一两百毫秒），回放时可能有轻微错位。
3. 建议**侧对镜头**：正对镜头时"身体是否成一条直线"的判断会明显变弱。
4. 只有部分设备支持"预览 + 分析 + 录像"三路同时运行；不支持时会自动降级为
   "预览 + 分析"，此时只保存骨架回放（历史记录里不会显示「视频」标记）。
5. 录像保存在 App 私有目录，暂未提供导出到相册/分享功能。
6. 光照不足、深色衣服、背景杂乱会降低关键点置信度，界面会提示"未检测到人体/手臂完整入镜"。
