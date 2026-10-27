# 判定算法离线实验台

`app/src/main/java/com/zxw/fitness/analysis` 与 `pose` 里所有阈值，都在这里有一份
**逐行对应的 Python 复刻**，目的是在没有手机、没有 Android SDK 的机器上也能：

- 用合成轨迹验证判定逻辑（回归）；
- 量化"机位/帧率/遮挡/横滚"变化对结果的影响（敏感性实验）；
- 调参时先在这里试，避免真机来回装包。

> 这不是 App 的依赖，只在开发机跑。改 Java 里的阈值时**请同步改 `replica.py`**，
> 否则回归数字会与真机行为脱节（这本身也是一种告警）。

## 用法

```bash
cd <repo 根目录>
python3 tools/algo_sim/regression.py     # 134 条行为断言，应全部 PASS
python3 tools/algo_sim/experiments.py    # 输出 docs/algorithm-plan.md 里的实验表
```

## 文件

| 文件 | 内容 |
| --- | --- |
| `replica.py` | 判定器复刻：`PushUp` / `Squat` / `PullUp` / `Plank` / `Handstand` / `WallSit` / `RunSteps`，以及 `body_metrics`、`PoseSmoother`、`FrameGapGuard`、`scene_inspect`、合成帧生成器 |
| `regression.py` | 与 Java 合成测试对应的行为断言（134 条，含深蹲横移恢复、异常点与靠墙蹲累计） |
| `experiments.py` | 横滚、镜头斜对、距离、帧率、单侧污染、走/跑、宽限期 七组实验 |

## 约定

- 坐标按画面宽度归一化，`x` 在 0~1、`y` 在 0~画面高/宽，`y` 向下（与 v2 `PoseFrame` 一致），角度单位度，时间毫秒。
- 合成帧的每个关键点都是 `(x, y, z, confidence)`，**置信度必须给**，
  否则 `BodyMetrics.valid == false`，判定器会静默不工作。
- 当前回归包含场景/越界、同侧躯干、单帧跳变、持续跳变重捕获、深蹲一致横移及异常膝点、候选中断、滤波帧重放、长时间停帧、脚踝/膝点遮挡等；
  Python 镜像并不是 Java 编译或 Android 设备测试。
- 复刻里出现的"镜头横滚/横向压缩"只是几何变换，用来模拟机位，不是真实拍摄数据；
  真实准确率仍以真机录制为准（见 `docs/algorithm-plan.md` 第 4 节 ⑫）。
