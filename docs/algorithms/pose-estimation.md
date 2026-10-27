# 人体骨架与姿态识别算法

> [算法索引](README.md) · 对照当前代码：恢复连续跟踪及旧逐点滤波，增加深蹲冻结恢复和版本化回放，资料检索至 2026-10-27。本文区分模型输出、应用显示、动作判定及回放；以下方案对比不是本项目的实测排名。

## 1. 任务与使用场景

输入为手机摄像头的连续画面，输出一人的身体关键点、骨架图及供各动作算法使用的坐标。适用于单人、镜头固定、光线充足且人体关键部位入镜的训练辅助，不是人体三维扫描、多人身份识别或医疗测量。

项目依赖 ML Kit `pose-detection-accurate:18.0.0-beta3`。各动作默认均使用 `STREAM_MODE` 连续跟踪，深蹲仅在原始骨架满足严格的持续冻结条件时尝试新建 STREAM 重新定位；不再默认 `SINGLE_IMAGE_MODE`。官方说明 STREAM 先检测人物再跟踪，必要时重检；SINGLE 每张图重找人、没有人物跟踪且延迟较高 [1](https://developers.google.com/ml-kit/vision/pose-detection/android)。**应用显示层没有再做关键点平滑**，模型最多返回单人的 33 个点，多人同框不能保证始终选中同一人 [2](https://developers.google.com/ml-kit/vision/pose-detection)。

## 2. 当前数据流

```text
CameraX ImageAnalysis（16:9 目标比例、保留最新帧）
  → ML Kit Accurate（STREAM；深蹲疑似冻结时尝试新 STREAM），异步输出 33 点
  → 像素坐标按摆正后画面宽归一化
  ├─ 原始帧 → 实时骨架 + 场景诊断（不经本应用的 PoseSmoother）
  └─ 剔除画面外点 → 按最近传感器横滚角旋转
       → 旧版逐点平滑、丢失保持、跳变暂拒/重捕获
       → 帧间隔保护 → 各动作判定器
       └─ 保存滤波后的 17 点及横滚角 + 逐帧结果/算法版本 → 同版直接回放
原 MP4 → 按视频时间近似抽帧 → Accurate → 原始点 / 越界过滤与当前滤波 → 临时回放
```

若预览、分析和录像三路绑定失败，代码尝试退化为预览 + 分析，可能没有视频。实际分析分辨率由 CameraX 协商，并非代码固定数值。模型输出的 33 点包括头面、肩肘腕、髋膝踝、脚跟和脚尖；日志只保存鼻及左右肩、肘、腕、髋、膝、踝、脚跟、脚尖共 **17 点**，不是 COCO 的另一套 17 点。

`x/W`、`y/W` 保留图像二维几何比例；`z/W` 也被保存，但当前动作规则主要使用 `x/y`，不能把该 `z` 当成真实测距或已验证的三维关节坐标。入画值来自 `getInFrameLikelihood()`：高值不证明肩点的位置或左右身份正确。

## 3. 核心处理

### 3.1 边界、显示与时间

- 动作输入在旋转前将越界或无效点的可信度置零；实时诊断仍查看原始帧，绘制时不画越界点。开启详细标记时显示绿点 ≥0.5、黄点 0.3–0.49；关闭后仅显示 ≥0.5 的基本骨架。紫圈表示训练滤波器正在等待该点的大跳变候选，不表示原始点已被修正。
- `PreviewView` 和 `SkeletonOverlay` 都按 `FIT_CENTER` 居中适配；前摄骨架额外水平镜像。代码没有通过共同 `ViewPort` / CameraX 坐标变换明确统一预览与分析裁剪，机型差异可能导致偏移，**尚未证实在哪些设备发生**。
- 识别异步完成后覆盖在独立刷新的预览上，没有按采集帧同步预览与骨架；推理慢或动作快时可能看见滞后。当前没有真实设备的延迟/FPS 基线。
- v2 骨架文件存相对首骨架帧的时间、横滚角及 17 点；录制骨架回放显示时逆旋转，判定仍使用对齐后的滤波帧。旧 v1 无横滚数据，历史日志也无法恢复当时的原始 33 点。视频与首骨架帧的起点、前摄录像镜像仍需真机验证。
- 算法版本相同且逐帧缓存与骨架完整匹配时直接复用录制结果；旧记录、不同版本或缓存不可用时回退到当前算法重算。有视频可主动从 MP4 重识别：每 100ms 近似抽帧（长录像最多 350ms、最多 6000 帧），连续跟踪，结果只存内存。重识别无历史重力时间轴，只采用初始个人目标、不重放组中校准；采样、旋转、镜像与对齐仍需设备验证。


### 3.2 重力对齐

`GravityAlign` 由加速度计 `ax、ay` 计算横滚，图像平面投影小于 `3.0 m/s²`、角度绝对值小于 `0.8°` 或大于 `60°` 时不补偿。补偿只作用于动作判定和记录，**不作用于实时原始骨架**。应用代码没有额外的传感器低通，也没有按相机采样时间插值：手机晃动、加速度、传感器方向和回放逆变换的影响需真机验证。

### 3.3 训练判定用滤波

- 每个点独立处理：输入入画值 ≥`0.3` 时，普通移动采用 `输出 = 旧值 + α × (新值 − 旧值)`；`α = clamp(0.45 + (入画值 − 0.3) × 0.5, 0.25, 0.75)`。高入画值下更跟手，也会保留更多抖动；它不是定位准确率权重。
- 与上次滤波位置的二维距离超过 `max(0.12, 0.6 × 最近可靠躯干尺度)` 时暂拒；`0.12` 是按**画面宽**归一化的下限。候选位置需要入画值 ≥`0.5`、至少 3 帧、持续 ≥700 ms、候选间隔 ≤350 ms 且位置稳定才接纳；未有新观测超过 1000 ms 可丢弃旧轨迹。
- 点暂时不可用时最多保持最近位置 350 ms，并衰减可信度；这不是运动预测。`FrameGapGuard` 对大于 500 ms 的帧间隔通知动作判定器一帧失效；单帧间隔稍大并不保证各动作立即重置。
- 深蹲冻结检测不修改滤波器：仅原始模型点持续满足 1–2 点明显运动、其余可靠点几乎不动等条件时尝试重建 STREAM；候选缺少完整一侧或变化不足不替换。完整阈值与误报/漏报风险见[深蹲](squat.md)。
- 所有动作未对左右肩进行身份锁定、骨长约束或专门纠错；不能把稳定的错误位置变成真实肩点。


### 3.4 共同几何量

关节角由同一侧三个点的二维向量夹角求得；双侧融合优先可靠侧，否则按可信度加权。`BodyMetrics` 计算肩/髋代表点、躯干长度、手腕相对肩高度、髋到肩—踝直线的距离、躯干倾角和肩宽/躯干的视角提示。`0.62` 是简单的正/侧视比例分界，并不是真实相机方位测量，也不保证透视修正。

## 4. 已知问题和优缺点

**优点**：端侧运行、无需联网发送画面；模型提供较完整的手脚点；几何判据与个人校准可解释；丢点与大跳变有保护，历史同时支持视频与纯骨架回放。

**缺点与待验证风险**：

1. 实时骨架绕过应用层滤波；训练滤波的响应与延迟随入画值、帧率变化，小于跳变门槛的抖动不会被拒绝。有限的合成轨迹不能代表真机表现。
2. `BodyMetrics` 在两侧躯干入画值差跨越 `0.25` 时，可从双侧中点切到单侧，当前没有切换迟滞。例如两肩 x 分别为 `0.40/0.60`、两侧躯干值为 `0.90/0.651` 和 `0.90/0.649` 时，在点坐标不变的合成帧里肩代表 x 可从 `0.50` 跳到 `0.40`。这是**内部动作度量**，不是显示点在移动。
3. 单目投影、遮挡、低光、小目标、倒立、左右重叠及快速动作都可能使点偏移；模型是否在用户设备上混淆左右肩，需视频和原始轨迹验证。静止的身体整体偏移、单个肩点偏移、动作时滞后需分别排查。
4. 原有 `smootherRemovesJitterAndHoldsShortDropouts` 测试的肩 x 在输入里没有变化；临时让普通移动不平滑，该测试仍可通过。它不能作为肩部抗抖有效的证据；应增加真实或明确变化的肩点轨迹测试。
5. 不识别墙、折凳、横杆接触或身体受力，不保证动作安全。个人目标可放宽阈值，不代表标准动作。

## 5. 同类人体姿态模型比较

下表基于公开文档的能力和工程取舍；关键点定义、硬件、输入分辨率、数据集和计算后端不同，不能直接用各家宣传 FPS / mAP 排名。除当前 ML Kit 外，均**未集成、未在本项目或用户手机上对照测试**。

| 方案 | 适合的方向 | 相对本项目的取舍 |
| --- | --- | --- |
| ML Kit Accurate（当前） | Android 单人端侧训练 | 33 点；所有动作连续跟踪，深蹲仅对疑似冻结尝试重新定位。单人且模型内部可调性有限，实际延迟、冻结恢复和遮挡效果需测 [1](https://developers.google.com/ml-kit/vision/pose-detection/android) [2](https://developers.google.com/ml-kit/vision/pose-detection) |
| MediaPipe Pose Landmarker Lite / Full / Heavy | 希望选择模型档位、输出世界坐标或配置多个姿态 | 33 点，提供模型档位和 `num_poses`；需要重新集成与适配。所谓世界坐标仍是单目估计，不能直接当作已验证的测量 [1](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker)。其 BlazePose 技术与 ML Kit 有关联，不应假设换用后误差独立或必然更准 [2](https://github.com/google-ai-edge/mediapipe/blob/master/docs/solutions/pose.md) |
| MoveNet Lightning / Thunder、MultiPose | 优先考察速度/精度档位或多人场景 | 单人/多人变体均以 17 个身体点为主，缺本项目日志中的脚跟、脚尖；坐标和过滤语义需适配。Lightning 更偏速度，Thunder 更偏精度，Android 整体性能需实测 [1](https://github.com/tensorflow/tfjs-models/blob/master/pose-detection/src/movenet/README.md) |
| RTMPose / RTMO | 可训练、多人或更可控的部署 | RTMPose 常配合人物检测，RTMO 是单阶段多人姿态；模型尺寸、关键点集、转换与手机推理管线需另选，端侧成本不应拿桌面指标代替 [3](https://github.com/open-mmlab/mmpose/tree/main/projects/rtmpose) [9](https://github.com/open-mmlab/mmpose/tree/main/projects/rtmo) |
| Ultralytics YOLO26-pose | 多人关键点与目标检测体系 | 默认 COCO 关键点与本项目 33/17 点不同；模型尺寸、移动部署、跟踪与功耗须实测。官方列有 AGPL-3.0 和 Enterprise 许可，产品使用须分别核查代码与权重的许可条件 [1](https://docs.ultralytics.com/tasks/pose) [2](https://docs.ultralytics.com/models/yolo26) |

**建议**：优先测原始点误差、显示映射和帧耗时，再用同一真实视频/标注/设备比较候选模型；另行评估单独的显示平滑、肩点质量与动作规则。不要仅靠换模型、降低显示抖动或厂商基准宣称识别更准。2026 年的一项手机机位观察研究也表明，动作、机位和距离会影响结果，但其数据不能直接当成本项目准确率 [12](https://mhealth.jmir.org/2026/1/e82412)。

## 6. 代码与验证入口

- 相机和模型：[CameraController.java](../../app/src/main/java/com/zxw/fitness/camera/CameraController.java)、[app/build.gradle](../../app/build.gradle)；绘制：[SkeletonOverlay.java](../../app/src/main/java/com/zxw/fitness/camera/SkeletonOverlay.java)、[TrainingActivity.java](../../app/src/main/java/com/zxw/fitness/ui/TrainingActivity.java)。
- 数据、几何与滤波：[PoseFrame.java](../../app/src/main/java/com/zxw/fitness/model/PoseFrame.java)、[PoseMath.java](../../app/src/main/java/com/zxw/fitness/pose/PoseMath.java)、[BodyMetrics.java](../../app/src/main/java/com/zxw/fitness/pose/BodyMetrics.java)、[PoseSmoother.java](../../app/src/main/java/com/zxw/fitness/pose/PoseSmoother.java)、[FrozenPoseRecovery.java](../../app/src/main/java/com/zxw/fitness/pose/FrozenPoseRecovery.java)、[GravityAlign.java](../../app/src/main/java/com/zxw/fitness/pose/GravityAlign.java)。
- 回放：[PoseLog.java](../../app/src/main/java/com/zxw/fitness/data/PoseLog.java)、[PoseLogWriter.java](../../app/src/main/java/com/zxw/fitness/data/PoseLogWriter.java)、[VideoPoseReanalyzer.java](../../app/src/main/java/com/zxw/fitness/camera/VideoPoseReanalyzer.java)、[VideoReanalysisRunner.java](../../app/src/main/java/com/zxw/fitness/analysis/VideoReanalysisRunner.java)；合成测试：[VideoReanalysisRunnerTest.java](../../app/src/test/java/com/zxw/fitness/VideoReanalysisRunnerTest.java)、[PoseDiagnosticsTest.java](../../app/src/test/java/com/zxw/fitness/PoseDiagnosticsTest.java)、[PoseAnalyzerTest.java](../../app/src/test/java/com/zxw/fitness/PoseAnalyzerTest.java)。
- 真机验证应包含：深蹲站起时观察冻结、恢复提示、单肢活动误报、识别间隔和计数；静止及运动时左右肩标注误差、抖动与换侧；前/后摄的空间映射和镜像；同一片段实时、视频及骨架回放对齐。模型仍可能一直输出折叠骨架，不能把合成结果当作修复保证。合成回归和安全边界见[索引](README.md)。
