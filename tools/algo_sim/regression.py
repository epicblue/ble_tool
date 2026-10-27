"""判定器回归断言（与 PoseAnalyzerTest 的用例一一对应）。

用法：python3 tools/algo_sim/regression.py
任何一条 FAIL 都意味着：要么 Java 实现与复刻脱节，要么阈值改动破坏了既有行为。
"""
import math

from replica import *   # noqa: F401,F403

FPS = STEP          # 与 SyntheticPoses.FPS_STEP_MS = 66 对应

results = []


def check(name, cond, extra=''):
    results.append((name, bool(cond), extra))


results = []


def check(name, cond, extra=''):
    results.append((name, bool(cond), extra))


def feed_pushup(counter, ts, bottom, sag, kneel, per_half=8):
    for a in rep_profile(bottom, 168, per_half):
        counter.process(pushup(ts, a, sag, kneel)); ts += STEP
    for _ in range(3):
        counter.process(pushup(ts, 168, sag, kneel)); ts += STEP
    return ts


# 1) 5 个标准俯卧撑
c = PushUp('pushup')
ts = 0
for _ in range(5):
    ts = feed_pushup(c, ts, 90, 0, False)
check('pushup good=5', c.good == 5 and c.bad == 0, 'good=%d bad=%d' % (c.good, c.bad))

# 2) 不标准：幅度不足 / 塌腰 / 跪姿
c = PushUp('pushup')
ts = 0
ts = feed_pushup(c, ts, 110, 0, False)
ts = feed_pushup(c, ts, 90, 0.10, False)
ts = feed_pushup(c, ts, 90, 0, True)
check('pushup bad=3 form=3', c.good == 0 and c.bad == 3 and c.form == 3,
      'good=%d bad=%d form=%d events=%s' % (c.good, c.bad, c.form, c.events))

# 3) 站立不计数
c = PushUp('pushup')
ts = 0
for _ in range(30):
    c.process(standing(ts)); ts += STEP
check('standing no count', c.good + c.bad == 0, 'good=%d bad=%d' % (c.good, c.bad))

# 4) 抖动不计
c = PushUp('pushup')
ts = 0
c.process(pushup(ts, 160, 0, False)); ts += STEP
c.process(pushup(ts, 95, 0, False)); ts += 100
for _ in range(3):
    c.process(pushup(ts, 168, 0, False)); ts += 50
check('jitter ignored', c.good + c.bad == 0, 'good=%d bad=%d' % (c.good, c.bad))

# 5) 折凳手撑凳 vs 普通
chair = PushUp('chair_pushup', 'hands')
ts = 0
for a in rep_profile(104, 168, 8):
    chair.process(pushup(ts, a, 0, False)); ts += STEP
for _ in range(3):
    chair.process(pushup(ts, 168, 0, False)); ts += STEP
normal = PushUp('pushup')
ts = 0
for a in rep_profile(104, 168, 8):
    normal.process(pushup(ts, a, 0, False)); ts += STEP
for _ in range(3):
    normal.process(pushup(ts, 168, 0, False)); ts += STEP
check('chair hands good', chair.good == 1, 'good=%d bad=%d' % (chair.good, chair.bad))
check('normal 104 shallow', normal.good == 0 and normal.bad == 1, 'good=%d bad=%d' % (normal.good, normal.bad))

# 6) 深蹲
c = Squat()
ts = 0
for rep in range(3):
    for knee in [175, 160, 140, 120, 95, 95, 120, 140, 160, 175]:
        drop = (150 - knee) / 150 if knee < 150 else 0
        for _ in range(2):
            c.process(squat(ts, knee, drop)); ts += STEP
check('squat good=3', c.good == 3 and c.bad == 0, 'good=%d bad=%d' % (c.good, c.bad))

c = Squat()
ts = 0
for knee in [175, 160, 140, 130, 130, 140, 160, 175]:
    for _ in range(2):
        c.process(squat(ts, knee, 0.2 if knee < 150 else 0)); ts += STEP
check('squat shallow bad=1', c.good == 0 and c.bad == 1, 'good=%d bad=%d' % (c.good, c.bad))

c = Squat()
ts = 0
for knee in [175, 150, 130, 130, 150, 175]:
    for _ in range(2):
        c.process(squat(ts, knee, 0)); ts += STEP
check('knee wiggle not counted', c.good + c.bad == 0, 'good=%d bad=%d' % (c.good, c.bad))

# 7) 引体向上
c = PullUp()
ts = 0
for _ in range(4):
    c.process(pullup(ts, 172, 0)); ts += STEP
for rep in range(2):
    for phase in [(165, 0.1), (140, 0.4), (110, 0.7), (80, 0.9), (110, 0.7), (140, 0.4), (172, 0.0)]:
        for _ in range(4):
            c.process(pullup(ts, phase[0], phase[1])); ts += STEP
check('pullup good=2', c.good == 2, 'good=%d bad=%d' % (c.good, c.bad))

# 8) 平板支撑
t = Plank()
ts = 0
for _ in range(60):
    t.process(plank(ts, 0)); ts += STEP
check('plank ~4s', 3000 <= t.best_ms() <= 4000, 'best=%d attempts=%d' % (t.best_ms(), t.attempts))

t = Plank()
ts = 0
for _ in range(15):
    t.process(plank(ts, 0)); ts += STEP
after = t.best_ms()
for _ in range(40):
    t.process(plank(ts, 0.16)); ts += STEP
check('plank sag stops timing', after > 0 and t.best_ms() <= after + 900 and t.sag_warn > 0,
      'after=%d best=%d warn=%d' % (after, t.best_ms(), t.sag_warn))

# 9) 倒立
h = Handstand(False)
ts = 0
for _ in range(10):
    h.process(handstand(ts, 0, False)); ts += STEP
for _ in range(45):
    h.process(handstand(ts, 3, True)); ts += STEP
for _ in range(4):
    h.process(handstand(ts, 0, False)); ts += STEP
for _ in range(30):
    h.process(handstand(ts, 3, True)); ts += STEP
check('handstand ~5s one attempt', 4600 <= h.best_ms() <= 5800 and h.attempts == 1,
      'best=%d attempts=%d stability=%.2f' % (h.best_ms(), h.attempts, h.stability()))
check('handstand stability in range', 0 <= h.stability() <= 1, 'stability=%.2f' % h.stability())

h = Handstand(False)
ts = 0
for _ in range(60):
    h.process(handstand(ts, 45, True)); ts += STEP
check('tilted rejected', h.best_ms() == 0 and h.attempts == 0, 'best=%d attempts=%d' % (h.best_ms(), h.attempts))

h = Handstand(True)
ts = 0
for _ in range(60):
    h.process(handstand(ts, 3, True, ankle_drift=0.01)); ts += STEP
check('wall handstand counts', h.best_ms() > 1000, 'best=%d' % h.best_ms())

# 10) 身体度量
m = body_metrics(pushup(0, 90))
check('metrics side view', m.valid and m.view == 'SIDE' and m.sag_ratio < 0.02 and m.wrist_drop > 0,
      'view=%s sag=%.3f drop=%.2f' % (m.view, m.sag_ratio, m.wrist_drop))
m2 = body_metrics(pushup(0, 90, 0.12))
check('metrics sag detected', m2.sag_ratio > 0.1, 'sag=%.3f' % m2.sag_ratio)

# ---- timesWallSit：8 帧站起 + 45 帧标准 + 15 帧站起 ----
t = WallSit()
ts = 0
for _ in range(8):
    t.process(wall_sit(ts, 0.25)); ts += FPS
for _ in range(45):
    t.process(wall_sit(ts, 0.0)); ts += FPS
for _ in range(15):
    t.process(wall_sit(ts, 0.25)); ts += FPS
best = t.best_ms()
check('timesWallSit', 2400 <= best <= 3200 and t.attempts == 1 and t.stability() > 0.8,
      f'best={best} attempts={t.attempts} stab={t.stability():.2f}')

# ---- wallSitRejectsHighHips ----
t = WallSit()
ts = 0
for _ in range(60):
    t.process(wall_sit(ts, 0.10)); ts += FPS
check('wallSitRejectsHighHips', t.best_ms() == 0 and t.attempts == 0,
      f'best={t.best_ms()} attempts={t.attempts} status={t.status}')

# ---- wallSitSurvivesShortWobble ----
t = WallSit()
ts = 0
for _ in range(30):
    t.process(wall_sit(ts, 0.0)); ts += FPS
for _ in range(4):
    t.process(wall_sit(ts, 0.25)); ts += FPS
for _ in range(20):
    t.process(wall_sit(ts, 0.0)); ts += FPS
check('wallSitSurvivesShortWobble', t.best_ms() >= 3000 and t.attempts == 1,
      f'best={t.best_ms()} attempts={t.attempts}')

# ---- countsRunningSteps：每侧 5 帧，4 个来回 ----
c = RunSteps()
ts = 0
for _ in range(4):
    for _ in range(5):
        c.process(running(ts, 0.16, 0.0)); ts += FPS
    for _ in range(5):
        c.process(running(ts, 0.0, 0.16)); ts += FPS
steps, cadence = c.steps, c.cadence()
check('countsRunningSteps', steps >= 6 and 150 < cadence < 220,
      f'steps={steps} cadence={cadence:.0f}')

# ---- standingDoesNotCountSteps ----
c = RunSteps()
ts = 0
for _ in range(60):
    c.process(standing(ts)); ts += FPS
check('standingDoesNotCountSteps', c.steps == 0, f'steps={c.steps}')

# ---- highFrequencyJitterIsNotCounted ----
c = RunSteps()
ts = 0
for i in range(80):
    left = i % 2 == 0
    c.process(running(ts, 0.16 if left else 0.0, 0.0 if left else 0.16))
    ts += 40
check('highFrequencyJitter', c.steps <= 3, f'steps={c.steps}')




# ================= 以下为"算法改进"后新增的回归用例 =================

# A0) HoldClock 单元语义（对应 PoseAnalyzerTest.holdClockSkipsInterrupt）
hc = HoldClock()
hc.begin(0); hc.accept(0); hc.accept(100); hc.accept(200)
hc.invalidate(); hc.accept(900); hc.accept(1000)
check('hold clock skips interrupt',
      hc.valid_ms() == 300 and hc.wall_ms(1000) == 1000 and hc.interrupt_ms(1000) == 700,
      'valid=%d wall=%d interrupt=%d' % (hc.valid_ms(), hc.wall_ms(1000), hc.interrupt_ms(1000)))
hc.end()
check('hold clock keeps value after end', hc.valid_ms() == 300 and hc.wall_ms(9999) == 300,
      'valid=%d' % hc.valid_ms())

# A) 计时只累计有效时间：中断不再算进成绩
def _plank_valid(gap_frames):
    a = Plank(); ts = 0
    for _ in range(30):
        a.process(plank(ts, 0)); ts += STEP
    for _ in range(gap_frames):
        a.process(plank(ts, 0.30)); ts += STEP
    for _ in range(20):
        a.process(plank(ts, 0)); ts += STEP
    return a

no_gap = _plank_valid(0).best_ms()            # 同一段 50 帧连续保持：3234ms
with_gap = _plank_valid(9).best_ms()          # 中间塌腰 9 帧（≈594ms）
# 修复前：墙钟 3828ms 全部算进成绩；修复后应退回到"50 帧有效时间"附近
check('hold skips grace gap',
      3000 <= with_gap <= no_gap + 66,
      'no_gap=%d with_gap=%d（修复前会是 3828）' % (no_gap, with_gap))

t = WallSit(); ts = 0
for _ in range(30):
    t.process(wall_sit(ts, 0.0)); ts += STEP
for _ in range(9):
    t.process(wall_sit(ts, 0.25)); ts += STEP     # 站起来约 0.6s
for _ in range(20):
    t.process(wall_sit(ts, 0.0)); ts += STEP
check('wall sit skips gap', 3000 <= t.best_ms() <= 3300 and t.attempts == 1,
      'best=%d attempts=%d' % (t.best_ms(), t.attempts))

# B) 双侧融合：一侧关键点被污染时不再拖偏
def _squeeze(f, kx=1.0):
    return F({i: (0.5 + (x - 0.5) * kx, y, z, c) for i, (x, y, z, c) in f.p.items()}, f.ts)

f = pushup(0, 155)
pts = dict(f.p)
lx, ly = pts[LE][0], pts[LE][1]
pts[LE] = (lx + 0.05, ly - 0.02, 0, 0.6)      # 左肘被拉偏，置信度仅 0.6
bad = F(pts, 0)
fused = avg_angle(bad, LS, LE, LW, RS, RE, RW)
check('fused angle ignores polluted side', abs(fused - 155.0) <= 5.0,
      'fused=%.1f（直接平均会到 131）' % fused)

# C) 正对镜头深蹲：膝角被压扁，靠髋部下移仍能判到位
for kx in (1.0, 0.75, 0.5):
    c = Squat(); ts = 0
    for _ in range(3):
        for knee in [175, 160, 140, 120, 95, 95, 120, 140, 160, 175]:
            drop = (150 - knee) / 150 if knee < 150 else 0
            for _ in range(2):
                c.process(_squeeze(squat(ts, knee, drop), kx)); ts += STEP
    check('frontal squat counts (kx=%.2f)' % kx, c.good == 3 and c.bad == 0,
          'good=%d bad=%d' % (c.good, c.bad))

# D) 斜对镜头俯卧撑：关节角被缩短，靠手腕位移仍能判到位
def _pushup_profile(per_half=8, top=155, bottom=92, drop=0.13):
    down = [(top + (bottom - top) * (i + 1) / per_half, drop * (i + 1) / per_half)
            for i in range(per_half)]
    up = [(bottom + (top - bottom) * (i + 1) / per_half, drop * (1 - (i + 1) / per_half))
          for i in range(per_half)]
    return down + up

for kx in (1.0, 0.75):
    c = PushUp(); ts = 0
    frames = [pushup(0, 155, 0, False, 0.0)] * 5
    for _ in range(3):
        frames += [pushup(0, e, 0, False, d) for e, d in _pushup_profile()]
    frames += [pushup(0, 155, 0, False, 0.0)] * 5
    for fr in frames:
        fr.ts = ts
        c.process(_squeeze(fr, kx)); ts += STEP
    check('oblique pushup counts (kx=%.2f)' % kx, c.good == 3 and c.bad == 0,
          'good=%d bad=%d' % (c.good, c.bad))

# E) 走 / 跑分类
def _feed_run(cadence_ms, bob=0.0, cycles=6, per_side=4, lift=0.10):
    c = RunSteps(); ts = 0
    for i in range(cycles):
        for k in range(per_side):
            c.process(running(ts, lift, 0.0, bob if (i + k) % 2 == 0 else 0.0))
            ts += cadence_ms / per_side
        for k in range(per_side):
            c.process(running(ts, 0.0, lift, bob if (i + k) % 2 == 0 else 0.0))
            ts += cadence_ms / per_side
    return c

check('gait walk 90', _feed_run(660).gait() == 'WALK', _feed_run(660).gait())
check('gait walk 120', _feed_run(500).gait() == 'WALK', _feed_run(500).gait())
check('gait run 160', _feed_run(375).gait() == 'RUN', _feed_run(375).gait())
check('gait 135 with bob → run', _feed_run(444, 0.06).gait() == 'RUN', _feed_run(444, 0.06).gait())
check('gait 135 no bob → walk', _feed_run(444, 0.0).gait() == 'WALK', _feed_run(444, 0.0).gait())
c = RunSteps(); ts = 0
for _ in range(40):
    c.process(standing(ts)); ts += STEP
check('gait unknown when standing', c.gait() == 'UNKNOWN', c.gait())


failed = [r for r in results if not r[1]]
for name, ok, extra in results:
    print(('PASS  ' if ok else 'FAIL  ') + name.ljust(34) + ('[' + extra + ']' if extra else ''))
print()
print('%d/%d passed' % (len(results) - len(failed), len(results)))
raise SystemExit(1 if failed else 0)
