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


# H) 横滚补偿：手机歪着拿时成绩不再归零（Java: pose/GravityAlign）
G_ACC = 9.81


def _gravity(theta_deg):
    rad = math.radians(theta_deg)
    return G_ACC * math.sin(rad), G_ACC * math.cos(rad)


for theta in (30.0, -30.0, 45.0):
    ax, ay = _gravity(theta)
    roll = roll_degrees(ax, ay)
    check('roll from accelerometer (%+.0f°)' % theta, abs(roll + theta) < 0.1,
          'roll=%+.1f' % roll)

check('roll ignored when phone is flat',
      roll_degrees(0.5, 0.3) == 0.0 and roll_degrees(0.0, 0.0) == 0.0, '')


def _roll_hold(kind, theta):
    """先按 theta 把画面转歪（模拟手机歪着拿），再按传感器算出的角补偿。"""
    ax, ay = _gravity(theta)
    roll = roll_degrees(ax, ay)
    analyzer, ts, raw = None, 0, None
    for compensated in (False, True):
        if kind == 'plank':
            analyzer = Plank()
        elif kind == 'wall':
            analyzer = WallSit()
        else:
            analyzer = Handstand()
        ts = 0
        for _ in range(60):
            if kind == 'plank':
                f = plank(ts, 0.0)
            elif kind == 'wall':
                f = wall_sit(ts, 0.0)
            else:
                f = handstand(ts, 0.0, True)
            f = rotate_frame(f, theta)
            if compensated:
                f = align(f, roll)
            analyzer.process(f)
            ts += STEP
        if compensated:
            return raw, analyzer.best_ms()
        raw = analyzer.best_ms()


for kind, label in (('plank', 'plank'), ('wall', 'wall sit'), ('handstand', 'handstand')):
    raw, fixed = _roll_hold(kind, 30.0)
    check('roll compensation restores %s (+30°)' % label, raw == 0 and fixed >= 3000,
          'raw=%dms fixed=%dms' % (raw, fixed))

# I) 手动个人校准：即时记一次、回到起始位前不重复、同目标后续可自动计数
c = PushUp('pushup'); ts = 0
check('calibration needs a starting pose', c.capture(pushup(ts, 115)) is None)
for _ in range(5):
    c.process(pushup(ts, 168)); ts += STEP
check('calibration rejects top', c.capture(pushup(ts - STEP, 168)) is None)
for a in (155, 145, 135, 125, 115):
    for _ in range(3):
        c.process(pushup(ts, a)); ts += STEP
sample = pushup(ts - STEP, 115)
missing = F({i: (v[0], v[1], v[2], 0.05 if i in (LE, RE, LA, RA) else v[3])
             for i, v in sample.p.items()}, sample.ts)
check('calibration rejects occluded raw pose', c.capture(missing) is None)
check('calibration rejects moving/stale pose', c.capture(pushup(ts - STEP, 150)) is None)
cal = c.capture(sample)
check('115° personal target captured', cal is not None and abs(cal['angle'] - 115) < 1
      and 0.73 < cal['target'] < 0.75 and abs(cal['start'] - 168) < 1, str(cal))
c.apply_event(cal)
check('calibration counts immediately', c.good == 1 and c.bad == 0)
check('same repetition cannot be tapped twice', c.capture(sample) is None and c.good == 1)
# 模拟长时间丢失，回到低位仍不能重复点击。
c.process(frame(ts + 1200, {})); ts += 1266
c.process(pushup(ts, 115)); ts += STEP
check('dropout cannot unlock duplicate', c.capture(pushup(ts - STEP, 115)) is None and c.good == 1)
for a in (125, 135, 145, 155, 168, 168, 168):
    c.process(pushup(ts, a)); ts += STEP
check('return to top does not double count', c.good == 1 and c.bad == 0)
ts = feed_pushup(c, ts, 115, 0, False)
check('same personal depth counts later', c.good == 2 and c.bad == 0, f'good={c.good} bad={c.bad}')
ts = feed_pushup(c, ts, 130, 0, False)
check('shallower than personal target not effective', c.good == 2, f'good={c.good} bad={c.bad}')
restored = PushUp('pushup'); restored.personal = dict(cal)
ts2 = feed_pushup(restored, 0, 115, 0, False)
check('restored target applies in next session', restored.good == 1 and restored.bad == 0)
default = PushUp('pushup'); feed_pushup(default, 0, 115, 0, False)
check('other uncalibrated analyzer unchanged', default.good == 0 and default.bad == 1)

# 校准当前跪姿时按个人目标记数，仍保留姿势提醒；未校准的跪姿不会变标准。
k = PushUp('pushup'); t = 0
for _ in range(5):
    k.process(pushup(t, 168, 0, True)); t += STEP
for a in (155, 145, 135, 125, 115):
    for _ in range(3):
        k.process(pushup(t, a, 0, True)); t += STEP
kcal = k.capture(pushup(t - STEP, 115, 0, True)); k.apply_event(kcal)
for a in (125, 135, 145, 155, 168, 168, 168):
    k.process(pushup(t, a, 0, True)); t += STEP
feed_pushup(k, t, 115, 0, True)
check('captured kneeling allowed but warned', kcal['kneel'] and k.good == 2 and k.form >= 2,
      f'good={k.good} bad={k.bad} warnings={k.form}')
bad_mode = PushUp('bad_pushup'); t = 0
for _ in range(5):
    bad_mode.process(pushup(t, 168)); t += STEP
for a in (155, 145, 135, 125, 115):
    for _ in range(3):
        bad_mode.process(pushup(t, a)); t += STEP
bc = bad_mode.capture(pushup(t - STEP, 115)); bad_mode.apply_event(bc)
check('bad-pushup button increments bad score', bad_mode.bad == 1 and bad_mode.good == 0)
for a in (125, 135, 145, 155, 168, 168, 168):
    bad_mode.process(pushup(t, a)); t += STEP
feed_pushup(bad_mode, t, 115, 0, False)
check('bad-pushup next shallow rep counts', bad_mode.bad == 2 and bad_mode.good == 0)

# 深蹲：即使只蹲到 130°，确认后也能按个人目标识别；顶端不重复。
sq = Squat(); t = 0
for _ in range(5):
    sq.process(squat(t, 175, 0)); t += STEP
for a in (160, 150, 140, 130, 130, 130):
    for _ in range(3):
        sq.process(squat(t, a, 0.12 if a < 150 else 0)); t += STEP
sc = sq.capture(squat(t - STEP, 130, 0.12)); sq.apply_event(sc)
for a in (140, 150, 160, 175, 175):
    for _ in range(3):
        sq.process(squat(t, a, 0.12 if a < 150 else 0)); t += STEP
check('squat calibration counts once', sc is not None and sq.good == 1 and sq.bad == 0)
for a in (160, 150, 140, 130, 130, 130, 140, 150, 160, 175, 175):
    for _ in range(3):
        sq.process(squat(t, a, 0.12 if a < 150 else 0)); t += STEP
check('squat personal target counts later', sq.good == 2 and sq.bad == 0)

# 引体向上：必须真的有上升量，校准后同样幅度可以计数。
pu = PullUp(); t = 0
for _ in range(5):
    pu.process(pullup(t, 172, 0)); t += STEP
for a, rise in ((165, .02), (145, .10), (135, .15), (125, .20)):
    for _ in range(4):
        pu.process(pullup(t, a, rise)); t += STEP
pc = pu.capture(pullup(t - STEP, 125, .20)); pu.apply_event(pc)
for a, rise in ((135, .15), (145, .10), (165, .02), (172, 0)):
    for _ in range(4):
        pu.process(pullup(t, a, rise)); t += STEP
check('pullup calibration counts once', pc is not None and pu.good == 1 and pu.bad == 0)
for a, rise in ((165, .02), (145, .10), (135, .15), (125, .20),
                (135, .15), (145, .10), (165, .02), (172, 0)):
    for _ in range(4):
        pu.process(pullup(t, a, rise)); t += STEP
check('pullup personal target counts later', pu.good == 2 and pu.bad == 0)

# 起始位无法达到标准伸直角时，以本组观察到的起始角为基准；静止不动仍不能校准。
limited = PushUp('pushup'); t = 0
for _ in range(8):
    limited.process(pushup(t, 140)); t += STEP
check('limited pushup top alone is rejected', limited.capture(pushup(t - STEP, 140)) is None)
# 单帧角度跳变不应把起始位从 140° 错写成 175°。
limited.process(pushup(t, 175)); t += STEP
limited.process(pushup(t, 140)); t += STEP
for a in (138, 134, 130, 125, 120, 115, 115):
    for _ in range(3):
        limited.process(pushup(t, a)); t += STEP
lc = limited.capture(pushup(t - STEP, 115)); limited.apply_event(lc)
for a in (120, 125, 130, 135, 140, 140, 140):
    for _ in range(2):
        limited.process(pushup(t, a)); t += STEP
for a in rep_profile(115, 140, 8) + [140] * 4:
    limited.process(pushup(t, a)); t += STEP
check('limited pushup start and depth count', lc is not None and abs(lc['start'] - 140) < 1
      and limited.good == 2 and limited.bad == 0, f'cal={lc} good={limited.good} bad={limited.bad}')
limited_sq = Squat(); t = 0
for _ in range(8):
    limited_sq.process(squat(t, 140, 0)); t += STEP
limited_sq.process(squat(t, 175, 0)); t += STEP
limited_sq.process(squat(t, 140, 0)); t += STEP
for a in (138, 134, 130, 125, 120, 115, 115):
    for _ in range(3):
        limited_sq.process(squat(t, a, .12 if a < 135 else 0)); t += STEP
lsc = limited_sq.capture(squat(t - STEP, 115, .12)); limited_sq.apply_event(lsc)
for a in (120, 125, 130, 135, 140, 140, 140, 138, 134, 130, 125, 120, 115, 115,
          120, 125, 130, 135, 140, 140, 140):
    for _ in range(3):
        limited_sq.process(squat(t, a, .12 if a < 135 else 0)); t += STEP
check('limited squat start and depth count', lsc is not None and abs(lsc['start'] - 140) < 1
      and limited_sq.good == 2 and limited_sq.bad == 0)
limited_pull = PullUp(); t = 0
for _ in range(8):
    limited_pull.process(pullup(t, 140, 0)); t += STEP
limited_pull.process(pullup(t, 175, 0)); t += STEP
limited_pull.process(pullup(t, 140, 0)); t += STEP
down = ((138, .02), (134, .05), (130, .08), (125, .12), (120, .16), (115, .2))
up = ((120, .16), (125, .12), (130, .08), (135, .04), (140, 0))
for a, rise in down:
    for _ in range(4):
        limited_pull.process(pullup(t, a, rise)); t += STEP
lpc = limited_pull.capture(pullup(t - STEP, 115, .2)); limited_pull.apply_event(lpc)
for a, rise in up + down + up:
    for _ in range(4):
        limited_pull.process(pullup(t, a, rise)); t += STEP
check('limited pullup start and depth count', lpc is not None and abs(lpc['start'] - 140) < 1
      and limited_pull.good == 2 and limited_pull.bad == 0)

check('single-frame spikes do not raise start angle',
      all(c is not None and abs(c['start'] - 140) < 1 for c in (lc, lsc, lpc)),
      f"push={lc['start']}, squat={lsc['start']}, pull={lpc['start']}")

# 组内多次校准：按帧回放按钮事件，不受后来个人设置变化影响。
frames = []; t = 0
for a in [168] * 5 + [155, 145, 135, 125, 115] * 3:
    frames.append(pushup(t, a)); t += STEP
first_event = len(frames) - 1
for a in [125, 135, 145, 155, 168, 168, 168]:
    frames.append(pushup(t, a)); t += STEP
for a in [155, 145, 135, 125, 110] * 3:
    frames.append(pushup(t, a)); t += STEP
second_event = len(frames) - 1
for a in [125, 135, 145, 155, 168, 168, 168] + rep_profile(110, 168, 8) + [168] * 3:
    frames.append(pushup(t, a)); t += STEP
live = PushUp('pushup'); changes = {}; expected = []
for i, f in enumerate(frames):
    live.process(f)
    if i in (first_event, second_event):
        changes[i] = live.capture(f); live.apply_event(changes[i])
    expected.append((live.good, live.bad))
replay = PushUp('pushup'); actual = []
for i, f in enumerate(frames):
    replay.process(f)
    if i in changes: replay.apply_event(changes[i])
    actual.append((replay.good, replay.bad))
check('replay restores calibration event timing', expected == actual and actual[first_event] == (1, 0)
      and actual[second_event] == (2, 0) and actual[-1] == (3, 0),
      f'events={actual[first_event]}, {actual[second_event]}; final={actual[-1]}')

failed = [r for r in results if not r[1]]
for name, ok, extra in results:
    print(('PASS  ' if ok else 'FAIL  ') + name.ljust(34) + ('[' + extra + ']' if extra else ''))
print()
print('%d/%d passed' % (len(results) - len(failed), len(results)))
raise SystemExit(1 if failed else 0)
