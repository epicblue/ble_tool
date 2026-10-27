"""判定器敏感性实验：量化"机位不理想"时当前阈值会怎么失效。

这些数字是 docs/algorithm-plan.md 里各条改进项的依据。全部用合成轨迹，
不需要手机；改动判定逻辑后重跑，可以直接对比改前/改后。

用法：python3 tools/algo_sim/experiments.py
"""
import math

from replica import *   # noqa: F401,F403

STEP = 66


# ---------------- 变换工具 ----------------
def rotate(f, deg):
    """绕画面中心旋转（模拟手机歪放 / 横滚）。"""
    r = math.radians(deg)
    c, s = math.cos(r), math.sin(r)
    return F({i: (0.5 + c * (x - 0.5) - s * (y - 0.5),
                  0.5 + s * (x - 0.5) + c * (y - 0.5), z, conf)
              for i, (x, y, z, conf) in f.p.items()}, f.ts)


def squeeze(f, kx=1.0, ky=1.0):
    """坐标压缩：kx 模拟镜头斜对目标（yaw），ky 模拟俯仰。"""
    return F({i: (0.5 + (x - 0.5) * kx, 0.5 + (y - 0.5) * ky, z, conf)
              for i, (x, y, z, conf) in f.p.items()}, f.ts)


def scale_body(f, k):
    """人靠近/远离镜头。"""
    return F({i: (0.5 + (x - 0.5) * k, 0.5 + (y - 0.5) * k, z, conf)
              for i, (x, y, z, conf) in f.p.items()}, f.ts)


def distort(f, idx, dx=0.0, dy=0.0, conf=0.6):
    pts = dict(f.p)
    for i in idx:
        x, y, z, _ = pts[i]
        pts[i] = (x + dx, y + dy, z, conf)
    return F(pts, f.ts)


# ---------------- 各动作的固定脚本 ----------------
def pushup_reps(transform=lambda f: f, step=STEP, per_half=8, bottom=92, reps=3):
    a = PushUp()
    ts = 0
    frames = [pushup(0, 155)] * 5
    for _ in range(reps):
        frames += [pushup(0, e) for e in rep_profile(bottom, 155, per_half)]
    frames += [pushup(0, 155)] * 5
    for f in frames:
        f.ts = ts
        a.process(transform(f))
        ts += step
    return a.good, a.bad


def squat_reps(transform=lambda f: f, step=STEP, per_half=8, bottom=88, reps=3):
    a = Squat()
    ts = 0
    frames = [squat(0, 175, 0.0)] * 5
    for _ in range(reps):
        frames += [squat(0, k, (175 - k) / 75) for k in rep_profile(bottom, 175, per_half)]
    frames += [squat(0, 175, 0.0)] * 5
    for f in frames:
        f.ts = ts
        a.process(transform(f))
        ts += step
    return a.good, a.bad


def hold(analyzer, gen, ms=3500, transform=lambda f: f, step=STEP):
    ts = 0
    for _ in range(int(ms / step)):
        analyzer.process(transform(gen(ts)))
        ts += step
    return analyzer.best_ms()


def run_steps(transform=lambda f: f, step=STEP, cycles=4, per_side=5, lift=0.16):
    c = RunSteps()
    ts = 0
    for _ in range(cycles):
        for _ in range(per_side):
            c.process(transform(running(ts, lift, 0.0)))
            ts += step
        for _ in range(per_side):
            c.process(transform(running(ts, 0.0, lift)))
            ts += step
    return c.steps, c.cadence()


def section(title):
    print()
    print('=' * 78)
    print(title)
    print('=' * 78)


# ---------------- 实验 ----------------
section('实验 A：手机/机位横滚（绕画面中心旋转）——依赖"图像竖直方向"的量会失真')
print(f'{"roll":>5} {"俯卧撑":>11} {"深蹲":>11} {"平板ms":>8} {"倒立ms":>8} {"靠墙蹲ms":>9} {"跑步步数":>8}')
for deg in (0, 5, 10, 15, 20, 30):
    g, b = pushup_reps(lambda f, d=deg: rotate(f, d))
    sg, sb = squat_reps(lambda f, d=deg: rotate(f, d))
    plank_ms = hold(Plank(), lambda ts: plank(ts, 0.0), transform=lambda f, d=deg: rotate(f, d))
    hs_ms = hold(Handstand(), lambda ts: handstand(ts, 0.0), transform=lambda f, d=deg: rotate(f, d))
    ws_ms = hold(WallSit(), lambda ts: wall_sit(ts, 0.0), transform=lambda f, d=deg: rotate(f, d))
    steps, _ = run_steps(lambda f, d=deg: rotate(f, d))
    print(f'{deg:>5} {f"{g}好/{b}差":>11} {f"{sg}好/{sb}差":>11} '
          f'{plank_ms:>8.0f} {hs_ms:>8.0f} {ws_ms:>9.0f} {steps:>8}')

print('\n横滚会直接吃掉"上身竖直/水平"的阈值预算（深蹲 MAX_LEAN=55°，平板/靠墙蹲 30°，倒立 25°）：')
for deg in (0, 10, 20, 30):
    m = body_metrics(rotate(squat(0, 175, 0.0), deg))
    print(f'  站立 + roll={deg:>2}° → torsoAngleFromVertical = '
          f'{math.degrees(math.atan2(abs(m.sx - m.hx), abs(m.sy - m.hy) + 1e-5)):5.1f}°')

section('实验 B：镜头斜对目标（横向压缩 = 关节角的透视缩短）')
print('真实动作：俯卧撑 3 次（最低点 92°）、深蹲 3 次（最低点 88°）')
print(f'{"kx":>5} {"≈镜头偏角":>10} {"俯卧撑":>11} {"深蹲":>11}')
for kx in (1.0, 0.75, 0.5, 0.35):
    g, b = pushup_reps(lambda f, k=kx: squeeze(f, k))
    sg, sb = squat_reps(lambda f, k=kx: squeeze(f, k))
    yaw = math.degrees(math.acos(max(-1.0, min(1.0, kx))))
    print(f'{kx:>5} {yaw:>9.0f}° {f"{g}好/{b}差":>11} {f"{sg}好/{sb}差":>11}')

print('\n同一个最低点，不同机位下量出来的角度（真值 92° / 88°）：')
for kx in (1.0, 0.75, 0.5):
    f1, f2 = squeeze(pushup(0, 92), kx), squeeze(squat(0, 88, 1.0), kx)
    m1, m2 = body_metrics(f1), body_metrics(f2)
    elbow = avg_angle(f1, LS, LE, LW, RS, RE, RW)
    knee = avg_angle(f2, LH, LK, LA, RH, RK, RA)
    print(f'  kx={kx}: 肘角 {elbow:5.1f}°（阈值 ≤100° 才算到位）  '
          f'膝角 {knee:5.1f}°（阈值 ≤100°）  '
          f'腕下降 {m1.wrist_drop:4.2f}  髋下降比例见下')

print('\n"沿重力方向的位移"不受横向压缩影响（俯卧撑用腕-肩，深蹲用髋-踝基准）：')
for kx in (1.0, 0.75, 0.5):
    up, down = body_metrics(squeeze(squat(0, 175, 0.0), kx)), body_metrics(squeeze(squat(0, 88, 1.0), kx))
    print(f'  kx={kx}: 髋部下移 {(down.hy - up.hy) / max(0.04, up.body_scale):5.2f} × 躯干长度')

section('实验 C：人离镜头远近（整体缩放）——应保持不变')
for k in (0.5, 1.0, 2.0):
    g, b = pushup_reps(lambda f, s=k: scale_body(f, s))
    sg, sb = squat_reps(lambda f, s=k: scale_body(f, s))
    print(f'  缩放 {k}x → 俯卧撑 {g}好/{b}差，深蹲 {sg}好/{sb}差')

section('实验 D：低帧率 + 快速动作')
print('MIN_REP_MS=350 会把"两个方向各 2 帧"的极快动作按抖动丢掉，这是预期行为；')
print('真正的问题是帧率低时平滑滞后，快速动作的有效幅度被低估：')
for step in (66, 100, 200):
    for per_half in (3, 8):
        g, b = pushup_reps(step=step, per_half=per_half)
        print(f'  {1000 / step:>4.1f} fps，半个行程 {per_half} 帧'
              f'（{per_half * step / 1000:.2f}s，整次 {2 * per_half * step / 1000:.2f}s）'
              f' → 俯卧撑 {g}好/{b}差')

section('实验 E：单侧关键点被污染时，双侧直接平均会拖偏判定')
f = pushup(0, 155)                     # 撑起位，真实肘角 155°
bad = distort(f, (LE,), dx=0.05, dy=-0.02, conf=0.6)
left_true = angle(f, LS, LE, LW)
left_bad = angle(bad, LS, LE, LW)
right = angle(f, RS, RE, RW)
print(f'  左臂真实 {left_true:.1f}° → 被污染后 {left_bad:.1f}°（置信度 0.6），右臂 {right:.1f}°')
print(f'  直接平均            → {(left_bad + right) / 2:6.1f}°（偏差 {(left_bad - left_true) / 2:+.1f}°）')
print(f'  置信度加权          → {(left_bad * 0.6 + right * 0.9) / 1.5:6.1f}°'
      f'（偏差 {(left_bad * 0.6 + right * 0.9) / 1.5 - (left_true + right) / 2:+.1f}°）')
print(f'  只取更可信的一侧    → {right:6.1f}°（偏差 0°）')
print('  注：躯干水平/竖直、sagRatio 都用中点，单侧污染影响比关节角小；'
      '关节角是最脆的一环。')

section('实验 F：走路和跑步用同一套逻辑，无法区分')
for label, ms in (('慢走 ~90 步/分', 660), ('快走 ~120 步/分', 500), ('慢跑 ~160 步/分', 375)):
    c = RunSteps()
    ts = 0
    for _ in range(6):
        for _ in range(4):
            c.process(running(ts, 0.10, 0.0)); ts += ms / 4
        for _ in range(4):
            c.process(running(ts, 0.0, 0.10)); ts += ms / 4
    print(f'  {label}: 计到 {c.steps} 步，步频 {c.cadence():.0f} 步/分')
print('  → 动作名是"跑步计步"，但走路的步数同样会被计入；'
      '要么改名"计步"，要么按步频+躯干上下振幅做走/跑分类。')

section('实验 G：计时类的宽限期会把真实中断"吞掉"')
for gap_ms in (300, 600, 900, 1500):
    t = WallSit()
    ts = 0
    for _ in range(30):
        t.process(wall_sit(ts, 0.0)); ts += STEP
    broken_ms = 0
    while broken_ms < gap_ms:                 # 站起来（姿势破坏）
        t.process(wall_sit(ts, 0.25)); ts += STEP
        broken_ms += STEP
    for _ in range(20):
        t.process(wall_sit(ts, 0.0)); ts += STEP
    print(f'  中断 {gap_ms:>5}ms（GRACE_MS=800）→ 仍算 {t.best_ms():>5}ms，'
          f'{"未被算作中断" if t.attempts == 1 else "已切分为新的一组"}')
