"""Java 判定器的 Python 逐行复刻（离线验证 / 参数调优用）。

**这不是 App 的依赖**：只在开发机上调参、跑回归、做敏感性实验时使用。
改动 `app/src/main/java/com/zxw/fitness/**` 里的阈值时，请同步修改本文件对应常量，
再跑 `python3 tools/algo_sim/regression.py` 与 `experiments.py`。

数值约定与 Java 一致：坐标为 0~1 归一化、y 向下、角度用度、时间用毫秒。
"""

"""Python 复刻新版 Java 判定器，用与单元测试相同的合成数据验证断言。"""
import math

LS, RS, LE, RE, LW, RW = 11, 12, 13, 14, 15, 16
LH, RH, LK, RK, LA, RA = 23, 24, 25, 26, 27, 28
NOSE = 0
N = 33
STEP = 66


def nan(x):
    return x is None or (isinstance(x, float) and math.isnan(x))


class F:
    def __init__(self, pts, ts):
        self.p = pts
        self.ts = ts

    def rel(self, i, thr=0.5):
        v = self.p.get(i)
        return v is not None and len(v) > 3 and v[3] >= thr

    def x(self, i):
        return self.p[i][0]

    def y(self, i):
        return self.p[i][1]

    def z(self, i):
        return self.p[i][2] if len(self.p[i]) > 2 else 0.0

    def conf(self, i):
        return self.p[i][3] if len(self.p[i]) > 3 else 0.0

    def visible(self):
        return any(v[3] > 0.3 for v in self.p.values())


def frame(ts, pts):
    full = {i: (0.5, 0.5, 0.0, 0.05) for i in range(N)}
    full.update(pts)
    return F(full, ts)


def mask_out_of_frame(f, width, height):
    """模拟相机帧进入判定前的画面边界校验（x/y 同除以画面宽）。"""
    if width <= 0 or height <= 0: return frame(f.ts, {})
    points = {}
    for i, (x, y, z, conf) in f.p.items():
        inside = math.isfinite(x) and math.isfinite(y) and 0 <= x <= 1 and 0 <= y <= height / width
        valid_conf = math.isfinite(conf) and 0 <= conf <= 1
        points[i] = (x, y, z, conf if inside and valid_conf else 0.0)
    return F(points, f.ts)


def scene_inspect(f, type_='pushup', width=720, height=1280, pixels=False):
    """场景提示的离线镜像；返回 (状态, 可信点数, 原因代码)。"""
    pairs = ((LS, RS), (LE, RE), (LW, RW), (LH, RH), (LK, RK), (LA, RA))
    if width <= 0 or height <= 0 or f is None or not f.visible(): return ('waiting', 0, 'no_frame')
    def inside(i):
        x, y = f.x(i), f.y(i)
        return (math.isfinite(x) and math.isfinite(y) and 0 <= x <= (width if pixels else 1)
                and 0 <= y <= (height if pixels else height / width))
    def good(i):
        return f.rel(i) and f.conf(i) <= 1 and inside(i)
    def outside(i):
        return f.conf(i) >= .3 and not inside(i)
    def side(side_no, *parts):
        return all(good(pairs[part][side_no]) for part in parts)
    count = sum(good(i) for pair in pairs for i in pair)
    if type_ in ('pushup', 'chair_pushup', 'bad_pushup'): req = (0, 1, 2, 3, 4, 5)
    elif type_ in ('squat', 'wall_sit'): req = (0, 3, 4, 5)
    elif type_ == 'pullup': req = (0, 1, 2, 3)
    elif type_ == 'plank': req = (0, 2, 3, 5)
    else: req = (0, 3, 5)
    missing, any_outside = False, False
    for part in req:
        l, r = pairs[part]; a, b = good(l), good(r)
        if (not a and not b) or (type_ == 'running' and part == 5 and not (a and b)):
            missing = True; any_outside |= (not a and outside(l)) or (not b and outside(r))
    if missing: return ('warning', count, 'outside' if any_outside else 'missing')
    left_torso, right_torso = side(0, 0, 3), side(1, 0, 3)
    if not left_torso and not right_torso: return ('warning', count, 'torso_chain')
    if type_ in ('pushup', 'chair_pushup', 'bad_pushup', 'pullup') and not (side(0,0,1,2) or side(1,0,1,2)):
        return ('warning', count, 'arm_chain')
    if type_ in ('squat', 'wall_sit') and not (side(0,3,4,5) or side(1,3,4,5)):
        return ('warning', count, 'leg_chain')
    if any(outside(i) for part in req for i in pairs[part]): return ('warning', count, 'outside')
    torso_side = 0 if left_torso else 1
    a, b = pairs[0][torso_side], pairs[3][torso_side]
    torso = math.hypot(f.x(a) - f.x(b), f.y(a) - f.y(b)) / (width if pixels else 1)
    if torso < .07: return ('warning', count, 'small')
    near = 0
    for part in req:
        for i in pairs[part]:
            if good(i):
                x = f.x(i) / (width if pixels else 1); y = f.y(i) / (height if pixels else height / width)
                near += x < .025 or x > .975 or y < .025 or y > .975
    return ('warning', count, 'edge') if near >= 2 else ('usable', count, 'usable')


def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def mid_of(f, a, b, axis):
    ra, rb = f.rel(a), f.rel(b)
    if ra and rb:
        return (axis(f, a) + axis(f, b)) / 2
    if ra:
        return axis(f, a)
    if rb:
        return axis(f, b)
    return None


X = lambda f, i: f.p[i][0]
Y = lambda f, i: f.p[i][1]


# ---------------- PoseSmoother ----------------
class PoseSmoother:
    """Java PoseSmoother 的离线复刻，用于验证跳变与恢复的时序。"""
    MIN_CONF = .3; BASE_ALPHA = .45; MAX_JUMP_RATIO = .6; HOLD_MS = 350
    REACQUIRE_MS = 700; MAX_CANDIDATE_GAP_MS = 350; LOST_TRACK_RESET_MS = 1000
    MIN_REACQUIRE_FRAMES = 3; REACQUIRE_CONF = .5

    def __init__(self):
        self.x = [0.] * N; self.y = [0.] * N; self.z = [0.] * N; self.conf = [0.] * N
        self.last_seen = [0] * N; self.last_observed = [0] * N; self.has = [False] * N
        self.last_scale = .2
        self.cx = [0.] * N; self.cy = [0.] * N; self.cstart = [0] * N
        self.clast = [0] * N; self.cframes = [0] * N
        self.initialized = False; self.last_ts = 0; self.rejected = 0; self.reacquired = 0

    def _scale(self, ts):
        pairs = [(LS, LH), (RS, RH)]
        lengths = [math.hypot(self.x[a] - self.x[b], self.y[a] - self.y[b])
                   for a, b in pairs if self.has[a] and self.has[b]
                   and ts - self.last_seen[a] <= self.HOLD_MS and ts - self.last_seen[b] <= self.HOLD_MS]
        if lengths: self.last_scale = sum(lengths) / len(lengths)
        return self.last_scale

    def _held_confidence(self, i, ts):
        age = max(0, ts - self.last_seen[i])
        return 0. if age > self.HOLD_MS else self.conf[i] * (1 - .6 * min(1., age / self.HOLD_MS))

    def _candidate(self, i, px, py, ts, tolerance):
        continuous = (self.cframes[i] > 0 and ts >= self.clast[i]
                      and ts - self.clast[i] <= self.MAX_CANDIDATE_GAP_MS
                      and (px - self.cx[i]) ** 2 + (py - self.cy[i]) ** 2 <= tolerance ** 2)
        if continuous:
            self.cframes[i] += 1
        else:
            self.cx[i] = px; self.cy[i] = py; self.cstart[i] = ts; self.cframes[i] = 1
        self.clast[i] = ts
        return self.cframes[i] >= self.MIN_REACQUIRE_FRAMES and ts - self.cstart[i] >= self.REACQUIRE_MS

    def pending(self):
        return [n > 0 for n in self.cframes]

    def smooth(self, raw):
        ts = raw.ts
        if not self.initialized or ts < self.last_ts:
            if self.initialized: self.__init__()
            self.initialized = True; self.last_ts = ts
            for i in range(N):
                px, py, pz, c = raw.p[i]
                self.x[i], self.y[i], self.z[i], self.conf[i] = px, py, pz, c
                self.has[i] = c >= self.MIN_CONF; self.last_seen[i] = ts; self.last_observed[i] = ts
            return raw
        scale = self._scale(ts); max_jump = max(.12, scale * self.MAX_JUMP_RATIO)
        output = {}
        for i in range(N):
            px, py, pz, c = raw.p[i]
            if c >= self.MIN_CONF:
                if self.has[i] and ts - self.last_observed[i] > self.LOST_TRACK_RESET_MS:
                    self.has[i] = False; self.cframes[i] = 0
                self.last_observed[i] = ts
                if self.has[i]:
                    dx, dy = px - self.x[i], py - self.y[i]
                    if math.hypot(dx, dy) > max_jump:
                        stable = c >= self.REACQUIRE_CONF and self._candidate(i, px, py, ts, max(.03, scale * .25))
                        if c < self.REACQUIRE_CONF: self.cframes[i] = 0
                        if stable:
                            self.x[i], self.y[i], self.z[i], self.conf[i] = px, py, pz, c
                            self.last_seen[i] = ts; self.cframes[i] = 0; self.reacquired += 1
                            output[i] = (px, py, pz, c)
                        else:
                            self.rejected += 1
                            output[i] = (self.x[i], self.y[i], self.z[i], self._held_confidence(i, ts) * .8)
                        continue
                    self.cframes[i] = 0
                    alpha = max(.25, min(.75, self.BASE_ALPHA + (c - self.MIN_CONF) * .5))
                    ox = self.x[i] + alpha * dx; oy = self.y[i] + alpha * dy
                    oz = self.z[i] + alpha * (pz - self.z[i])
                else:
                    self.cframes[i] = 0; ox, oy, oz = px, py, pz
                self.x[i], self.y[i], self.z[i], self.conf[i] = ox, oy, oz, c
                self.has[i] = True; self.last_seen[i] = ts
                output[i] = (ox, oy, oz, c)
            elif self.has[i] and ts - self.last_seen[i] <= self.HOLD_MS:
                self.cframes[i] = 0
                output[i] = (self.x[i], self.y[i], self.z[i], self._held_confidence(i, ts))
            else:
                self.cframes[i] = 0
                if ts - self.last_observed[i] > self.LOST_TRACK_RESET_MS: self.has[i] = False
                output[i] = (0., 0., 0., 0.)
        self.last_ts = ts
        return F(output, ts)


class FrameGapGuard:
    """镜像训练及回放对相机长时间无回调的处理。"""
    MAX_FRAME_GAP_MS = 500

    def __init__(self): self.previous = None

    def process(self, analyzer, f):
        if self.previous is not None:
            if f.ts < self.previous:
                analyzer.process(frame(self.previous + 1000, {}))
            elif f.ts - self.previous > self.MAX_FRAME_GAP_MS:
                analyzer.process(frame(f.ts, {}))
        self.previous = f.ts
        analyzer.process(f)


# ---------------- BodyMetrics ----------------
class BM:
    MIN_SCALE = 0.04
    FRONTAL = 0.62

    def __init__(self):
        self.valid = False
        self.view = 'UNKNOWN'
        self.sag_ratio = float('nan')
        self.wrist_drop = float('nan')
        self.ankle_y = float('nan')
        self.body_scale = 0.04
        self.has_ankle = False
        # 下面两个比例量只用于诊断（实验输出），判定逻辑没有采用它们：
        # 镜头偏转时 |肩-腕| 在图像上反而变大，方向与"被压扁"相反，见 docs/algorithm-plan.md ③
        self.arm_ratio = float('nan')   # |肩中-腕中| / 躯干长度
        self.leg_ratio = float('nan')   # |髋中-踝中| / 躯干长度
        self.sx = self.sy = self.hx = self.hy = 0.0


def body_metrics(f):
    m = BM()
    if f is None or not f.visible():
        return m
    left_torso = f.rel(LS) and f.rel(LH); right_torso = f.rel(RS) and f.rel(RH)
    if not left_torso and not right_torso:
        return m
    use_left, use_right = left_torso, right_torso
    if left_torso and right_torso:
        lc, rc = min(f.conf(LS), f.conf(LH)), min(f.conf(RS), f.conf(RH))
        if abs(lc - rc) > .25:
            use_left, use_right = lc > rc, rc > lc
    def paired(a, b, axis):
        if use_left and not use_right and f.rel(a): return axis(f, a)
        if use_right and not use_left and f.rel(b): return axis(f, b)
        return mid_of(f, a, b, axis)
    sx = paired(LS, RS, X); sy = paired(LS, RS, Y)
    hx = paired(LH, RH, X); hy = paired(LH, RH, Y)
    m.sx, m.sy, m.hx, m.hy = sx, sy, hx, hy
    torso = dist((sx, sy), (hx, hy))
    m.body_scale = max(BM.MIN_SCALE, torso)
    has_sw = f.rel(LS) and f.rel(RS)
    m.shoulder_width = dist((f.x(LS), f.y(LS)), (f.x(RS), f.y(RS))) if has_sw else float('nan')
    m.view = 'UNKNOWN'
    if has_sw and torso > 0.02:
        m.view = 'FRONTAL' if m.shoulder_width / torso >= BM.FRONTAL else 'SIDE'
    m.has_ankle = f.rel(LA) or f.rel(RA)
    m.sag_ratio = float('nan'); m.sag_side = 0
    if m.has_ankle:
        ax = paired(LA, RA, X); ay = paired(LA, RA, Y)
        m.ankle_y = ay; m.ankle_x = ax
        abx, aby = ax - sx, ay - sy
        l2 = abx * abx + aby * aby
        if l2 > 1e-8:
            t = ((hx - sx) * abx + (hy - sy) * aby) / l2
            px, py = sx + t * abx, sy + t * aby
            m.sag_ratio = dist((hx, hy), (px, py)) / m.body_scale
            cross = (ax - sx) * (hy - sy) - (ay - sy) * (hx - sx)
            m.sag_side = 0 if abs(cross) < 1e-6 else (1 if cross > 0 else -1)
    else:
        m.ankle_y = float('nan'); m.ankle_x = float('nan')
    wx = paired(LW, RW, X); wy = paired(LW, RW, Y)
    m.wrist_drop = (wy - sy) / m.body_scale if wy is not None else float('nan')
    if wx is not None:
        m.arm_ratio = dist((sx, sy), (wx, wy)) / m.body_scale
    if m.has_ankle:
        m.leg_ratio = dist((hx, hy), (ax, ay)) / m.body_scale
    m.valid = True
    return m


def torso_horizontal(m):
    dx = abs(m.sx - m.hx); dy = abs(m.sy - m.hy)
    vertical = math.degrees(math.atan2(dx, dy + 1e-5))
    return 90 - vertical



# ---------------- 计时时钟：只累计"合格帧之间"的时间 ----------------
class HoldClock:
    """宽限期内的中断不再计入成绩。

    Java 对应 `analysis/HoldClock.java`。语义：
    - begin()：本组开始（含 ARM 起算窗口），把起算窗口算作有效时间；
    - accept()：本帧合格。只有在**上一帧也合格**时，才把两帧之间的时间计入；
    - invalidate()：本帧不合格（含识别失败），下一帧即使合格也不会把这段空白计入。
    """

    def __init__(self):
        self.active = False
        self.start = None
        self.valid = 0
        self.last_accepted = None
        self.prev_good = False

    def begin(self, now):
        self.active = True
        self.start = now
        self.valid = 0
        self.last_accepted = now
        self.prev_good = True

    def accept(self, now):
        if not self.active:
            return
        if self.prev_good and self.last_accepted is not None:
            self.valid += max(0, now - self.last_accepted)
        self.last_accepted = now
        self.prev_good = True

    def invalidate(self):
        self.prev_good = False

    def end(self):
        self.active = False
        self.start = None
        self.last_accepted = None
        self.prev_good = False

    def valid_ms(self):
        return self.valid

    def wall_ms(self, now):
        if not self.active or self.start is None:
            return self.valid
        return max(0, now - self.start)

    def interrupt_ms(self, now):
        return max(0, self.wall_ms(now) - self.valid)


# 连续三帧确认起始极值，与 Java StableExtremum 一致。
class StableExtremum:
    def __init__(self, maximum):
        self.maximum = maximum; self.values = []; self.best = float('nan')

    def clear(self):
        self.values = []

    def observe(self, value):
        if not math.isfinite(value):
            self.clear(); return self.best
        self.values = (self.values + [value])[-3:]
        if len(self.values) == 3:
            stable = min(self.values) if self.maximum else max(self.values)
            self.best = stable if math.isnan(self.best) else (max(self.best, stable) if self.maximum else min(self.best, stable))
        return self.best


# ---------------- PushUpCounter ----------------
class PushUp:
    TOP = 152.0; DEPTH = 100.0; PARTIAL = 122.0
    DOWN_START = 0.22; TOP_DEPTH = 0.10; FULL_DEPTH = 0.85; PARTIAL_DEPTH = 0.45
    SAG_LIMIT = 0.13; HIP_STRAIGHT = 150.0; KNEEL = 120.0
    MIN_WRIST_DROP = 0.02; MAX_TORSO_H = 55.0; MIN_REP = 350; DROPOUT = 500; SMOOTH = 0.5

    def __init__(self, type_='pushup', variant=''):
        self.type = type_; self.variant = variant
        self.sm_depth = float('nan'); self.top_drop = float('nan')
        self.down = False; self.down_start = 0
        self.rep_max_depth = 0.0; self.rep_min_elbow = float('nan')
        self.rep_max_drop = 0.0; self.rep_max_sag = 0.0
        self.rep_straight = True; self.rep_kneeling = False
        self.good = 0; self.bad = 0; self.form = 0
        self.last_valid = None; self.events = []; self.status = 'wait'
        self.personal = None; self.observed_top_angle = float('nan'); self.observed_top_drop = float('nan')
        self.await_top = False; self.sample = None; self.rep_min_hip = float('nan')
        self.top_angle_samples = StableExtremum(True); self.top_drop_samples = StableExtremum(True)

    def clear_calibration_samples(self):
        self.top_angle_samples.clear(); self.top_drop_samples.clear()

    def depth_angle(self):
        return self.DEPTH + 6 if (self.type == 'chair_pushup' and self.variant == 'hands') else self.DEPTH

    def partial_angle(self):
        return self.PARTIAL + 6 if (self.type == 'chair_pushup' and self.variant == 'hands') else self.PARTIAL

    def angle_top(self):
        return self.TOP if self.personal is None else self.personal['start']

    def angle_reference(self):
        return self.depth_angle() if self.personal is None else min(self.depth_angle(), self.personal['start'] - 20)

    def process(self, f):
        self.sample = None
        now = f.ts
        if not f.visible():
            self.clear_calibration_samples(); self.dropout(now); return
        m = body_metrics(f)
        if not m.valid:
            self.clear_calibration_samples(); self.dropout(now); return
        self.last_valid = now
        elbow = avg_angle(f, LS, LE, LW, RS, RE, RW)
        th = torso_horizontal(m)
        prone = not math.isnan(th) and th < self.MAX_TORSO_H
        hands = not math.isnan(m.wrist_drop) and m.wrist_drop > self.MIN_WRIST_DROP
        if not prone or not hands:
            self.clear_calibration_samples(); self.drop_phase(); self.status = 'not pushup'; return
        if math.isnan(elbow):
            self.clear_calibration_samples()
        else:
            self.observed_top_angle = self.top_angle_samples.observe(elbow)
            self.observed_top_drop = self.top_drop_samples.observe(m.wrist_drop)
        elbow_top = math.isnan(elbow) or elbow >= self.angle_top() - 6
        if elbow_top and not math.isnan(m.wrist_drop):
            self.top_drop = m.wrist_drop if math.isnan(self.top_drop) else max(self.top_drop, m.wrist_drop)
        depth_by_angle = float('nan') if math.isnan(elbow) else clamp((self.angle_top() - elbow) / (self.angle_top() - self.angle_reference()), 0, 1.25 if self.personal is None else 5.0)
        depth_by_drop = self.drop_depth(m.wrist_drop)
        # 两个深度信号都是"镜头斜对时的下限"：关节角被透视缩短会低估，
        # 手腕位移是沿重力方向量的、与横向透视无关，因此直接取两者较大值。
        if math.isnan(depth_by_angle):
            depth = depth_by_drop
        elif math.isnan(depth_by_drop):
            depth = depth_by_angle
        else:
            depth = max(depth_by_angle, depth_by_drop)
        if math.isnan(depth):
            self.status = 'arms missing'; return
        self.sm_depth = depth if math.isnan(self.sm_depth) else self.sm_depth + self.SMOOTH * (depth - self.sm_depth)

        sag = 0.0 if math.isnan(m.sag_ratio) else m.sag_ratio
        straight_by_ratio = math.isnan(m.sag_ratio) or sag <= self.SAG_LIMIT
        hip_angle = avg_angle(f, LS, LH, LA, RS, RH, RA)
        straight_by_angle = math.isnan(hip_angle) or m.view != 'SIDE' or hip_angle >= self.HIP_STRAIGHT
        straight = straight_by_ratio and straight_by_angle
        knee = avg_angle(f, LH, LK, LA, RH, RK, RA)
        kneeling = (not math.isnan(knee)) and knee < self.KNEEL
        candidate_drop = self.drop_depth(m.wrist_drop, self.observed_top_drop)
        candidate_angle = float('nan') if math.isnan(elbow) or math.isnan(self.observed_top_angle) else clamp(
            (self.observed_top_angle - elbow) / (self.observed_top_angle - min(self.depth_angle(), self.observed_top_angle - 20)), 0, 5.0)
        score = self.personal_depth(candidate_angle, candidate_drop)
        if all(not math.isnan(v) for v in (elbow, candidate_drop, m.sag_ratio, hip_angle, knee)):
            self.sample = (score, candidate_drop, sag, hip_angle, kneeling, straight and not kneeling, elbow)
        if self.await_top:
            if depth <= self.TOP_DEPTH:
                self.await_top = False; self.drop_phase(); self.status = 'up'
            else:
                self.status = 'calibrated, return to top'
            return
        if not self.down:
            start = self.DOWN_START if self.personal is None else max(
                self.TOP_DEPTH + 0.02, min(self.DOWN_START, self.personal['target'] * 0.5))
            if depth > start:
                self.down = True; self.down_start = now
                self.rep_max_depth = depth; self.rep_min_elbow = elbow
                self.rep_max_drop = 0.0 if math.isnan(depth_by_drop) else depth_by_drop
                self.rep_max_sag = sag; self.rep_straight = straight; self.rep_kneeling = kneeling
                self.rep_min_hip = hip_angle if m.view == 'SIDE' else float('nan')
            else:
                self.status = 'up'; return
        else:
            self.rep_max_depth = max(self.rep_max_depth, depth)
            if not math.isnan(elbow):
                self.rep_min_elbow = elbow if math.isnan(self.rep_min_elbow) else min(self.rep_min_elbow, elbow)
            if not math.isnan(depth_by_drop):
                self.rep_max_drop = max(self.rep_max_drop, depth_by_drop)
            self.rep_max_sag = max(self.rep_max_sag, sag)
            if m.view == 'SIDE' and not math.isnan(hip_angle):
                self.rep_min_hip = hip_angle if math.isnan(self.rep_min_hip) else min(self.rep_min_hip, hip_angle)
            if not straight: self.rep_straight = False
            if kneeling: self.rep_kneeling = True
        if depth <= self.TOP_DEPTH:
            if self.down: self.finish(now - self.down_start)
            self.status = 'top'
        else:
            self.status = 'down'

    def drop_depth(self, wd, baseline=None):
        baseline = self.top_drop if baseline is None else baseline
        if math.isnan(wd) or math.isnan(baseline) or baseline <= self.MIN_WRIST_DROP:
            return float('nan')
        travel = baseline * 0.72
        return clamp((baseline - wd) / max(0.02, travel), 0, 1.25)

    @classmethod
    def personal_depth(cls, angle_depth, drop_depth):
        return max(0.0 if math.isnan(angle_depth) else angle_depth,
                   0.0 if math.isnan(drop_depth) else drop_depth / cls.FULL_DEPTH)

    def capture(self, raw):
        if self.await_top or math.isnan(self.observed_top_angle) or math.isnan(self.observed_top_drop) or self.sample is None:
            return None
        m = body_metrics(raw)
        raw_elbow = avg_angle(raw, LS, LE, LW, RS, RE, RW)
        raw_hip = avg_angle(raw, LS, LH, LA, RS, RH, RA)
        raw_knee = avg_angle(raw, LH, LK, LA, RH, RK, RA)
        if not m.valid or any(math.isnan(v) for v in (m.sag_ratio, raw_elbow, raw_hip, raw_knee)):
            return None
        depth, drop, sag, hip, kneel, standard, elbow = self.sample
        raw_drop = self.drop_depth(m.wrist_drop, self.observed_top_drop)
        if (math.isnan(raw_drop) or abs(raw_elbow - elbow) > 12 or abs(raw_drop - drop) > .20 or
                abs(m.sag_ratio - sag) > .15 or abs(raw_hip - hip) > 20 or (raw_knee < self.KNEEL) != kneel):
            return None
        if depth < 0.18:
            return None
        return dict(type=self.type, variant=self.variant, start=self.observed_top_angle,
                    target=clamp(depth * 0.95, 0.15, 5.0), angle=elbow, movement=drop,
                    max_form=max(self.SAG_LIMIT, sag + 0.03), min_hip=min(self.HIP_STRAIGHT, max(0, hip - 4)),
                    min_movement=0, kneel=kneel, form_warning=not standard)

    def apply_event(self, calibration):
        if calibration is None or calibration['type'] != self.type or calibration['variant'] != self.variant:
            return
        self.personal = calibration
        if not math.isnan(self.observed_top_drop): self.top_drop = self.observed_top_drop
        self.drop_phase(); self.await_top = True
        if self.type == 'bad_pushup':
            self.bad += 1; self.form += 1
        else:
            self.good += 1
            if calibration['form_warning']: self.form += 1

    def finish(self, dur):
        me, md = self.rep_min_elbow, self.rep_max_drop
        straight, kneeling, sag, min_hip = self.rep_straight, self.rep_kneeling, self.rep_max_sag, self.rep_min_hip
        self.drop_phase()
        if dur < self.MIN_REP:
            return
        standard_depth = ((not math.isnan(me)) and me <= self.depth_angle()) or md >= self.FULL_DEPTH
        standard_form = straight and not kneeling
        angle_depth = float('nan') if math.isnan(me) else clamp((self.angle_top() - me) / (self.angle_top() - self.angle_reference()), 0, 1.25 if self.personal is None else 5.0)
        reached = self.personal_depth(angle_depth, md)
        deep = standard_depth if self.personal is None else reached >= self.personal['target']
        moved = ((not math.isnan(me)) and me <= self.partial_angle()) or md >= self.PARTIAL_DEPTH
        moved = moved or (self.personal is not None and reached >= self.personal['target'] * 0.65)
        form_ok = standard_form if self.personal is None else (
            sag <= self.personal['max_form'] and
            (math.isnan(min_hip) or min_hip >= self.personal['min_hip']) and
            (not kneeling or self.personal['kneel']))
        reason = None if form_ok and deep else ('form' if not form_ok else 'shallow')
        count_bad = not form_ok or not deep
        if self.personal is not None and self.type == 'bad_pushup' and deep and form_ok and (not standard_form or not standard_depth):
            count_bad = True; reason = 'personal bad'
        if count_bad:
            if not moved and form_ok:
                return
            self.bad += 1; self.form += 1
        else:
            self.good += 1
            if self.personal is not None and not standard_form: self.form += 1
        self.events.append((not count_bad, reason, round(me, 1) if not math.isnan(me) else None))

    def dropout(self, now):
        if self.last_valid is None:
            self.status = 'no person'; return
        if now - self.last_valid > self.DROPOUT:
            self.drop_phase(); self.status = 'no person'
        else:
            self.status = 'unstable'

    def drop_phase(self):
        self.down = False; self.rep_max_depth = 0.0; self.rep_min_elbow = float('nan')
        self.rep_max_drop = 0.0; self.rep_max_sag = 0.0; self.rep_straight = True; self.rep_kneeling = False
        self.rep_min_hip = float('nan')


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def angle(f, a, b, c):
    if not (f.rel(a) and f.rel(b) and f.rel(c)):
        return float('nan')
    v1 = (f.x(a) - f.x(b), f.y(a) - f.y(b)); v2 = (f.x(c) - f.x(b), f.y(c) - f.y(b))
    n1 = math.hypot(*v1); n2 = math.hypot(*v2)
    if n1 < 1e-5 or n2 < 1e-5:
        return float('nan')
    cos = max(-1, min(1, (v1[0] * v2[0] + v1[1] * v2[1]) / (n1 * n2)))
    return math.degrees(math.acos(cos))


CONF_GAP = 0.25        # 两侧置信度差超过它，只信更可信的一侧
DISAGREE_ANGLE = 35.0  # 两侧角度差超过它，视为一侧关键点被污染


def _min_conf(f, a, b, c):
    return min(f.conf(a), f.conf(b), f.conf(c))


def avg_angle(f, aL, bL, cL, aR, bR, cR):
    """左右两侧融合（Java: PoseMath.averageAngle）。

    两侧都可信时：
    1. 置信度差 > CONF_GAP → 只取高的一侧；
    2. 角度差 > DISAGREE_ANGLE → 只取置信度高的一侧（单侧关键点被污染）；
    3. 其余按置信度加权。
    """
    l = angle(f, aL, bL, cL); r = angle(f, aR, bR, cR)
    hl = not math.isnan(l); hr = not math.isnan(r)
    if not (hl and hr):
        return l if hl else r
    cl = _min_conf(f, aL, bL, cL); cr = _min_conf(f, aR, bR, cR)
    if abs(cl - cr) > CONF_GAP:
        return l if cl > cr else r
    if abs(l - r) > DISAGREE_ANGLE:
        return l if cl >= cr else r
    if cl + cr <= 0:
        return (l + r) / 2
    return (l * cl + r * cr) / (cl + cr)


# ---------------- 重力对齐（Java: pose/GravityAlign） ----------------
ROLL_SIGN = 1.0
DEAD_ZONE_DEG = 0.8
MAX_ROLL_DEG = 60.0
MIN_GRAVITY_XY = 3.0


def normalize_deg(deg):
    v = math.fmod(deg, 360.0)
    if v > 180.0:
        v -= 360.0
    elif v <= -180.0:
        v += 360.0
    return v


def roll_degrees(ax, ay):
    """由加速度计读数算出需要施加给关键点的旋转角（度）。

    推导见 Java 的 GravityAlign：静止时加速度计读到的是"上方向 × 9.81"，
    画面 y 与设备 y 相反，手机拿正时方位角为 −90°，所以 ψ = −90 − α。
    设备顺时针歪 θ 时读数 (sinθ, cosθ)·g，算出 ψ = −θ。
    """
    if math.hypot(ax, ay) < MIN_GRAVITY_XY:
        return 0.0
    alpha = math.degrees(math.atan2(-ay, ax))
    psi = normalize_deg(-90.0 - alpha) * ROLL_SIGN
    if abs(psi) < DEAD_ZONE_DEG or abs(psi) > MAX_ROLL_DEG:
        return 0.0
    return psi


def rotate_frame(f, deg, cx=0.5, cy=0.5):
    """绕画面中心旋转关键点；正值 = 屏幕上顺时针（y 向下的坐标系）。"""
    if abs(deg) < 0.01:
        return f
    rad = math.radians(deg)
    c, s = math.cos(rad), math.sin(rad)
    pts = {}
    for i, (x, y, z, conf) in f.p.items():
        dx, dy = x - cx, y - cy
        pts[i] = (cx + dx * c - dy * s, cy + dx * s + dy * c, z, conf)
    return F(pts, f.ts)


def align(f, roll):
    return f if abs(roll) < DEAD_ZONE_DEG else rotate_frame(f, roll)


# ---------------- 合成数据 ----------------
def pushup(ts, elbow_deg, sag=0.0, kneel=False, chest_drop=0.0):
    """侧视角度俯卧撑。chest_drop>0 时整个身体随动作下沉（手/脚不动），
    这样"手腕-肩"的垂直距离会变化，用来验证正对/斜对镜头时的位移判据。"""
    sx, sy, wx, wy = 0.40, 0.55 + chest_drop, 0.40, 0.75
    half = (wy - sy) / 2
    h = half / math.tan(math.radians(elbow_deg) / 2)
    ex, ey = sx + h, (sy + wy) / 2
    # 身体绕脚踝转动：肩下沉 chest_drop，髋只下沉一半，保持肩-髋-踝近似一条直线
    hip_y = 0.55 + sag + chest_drop * 0.5; ankle_y = 0.55
    knee = (0.68, 0.62) if kneel else (0.70, 0.55)
    ankle = (0.78, 0.55) if kneel else (0.80, 0.55)
    pts = {NOSE: (0.34, 0.52, 0, 0.9)}
    for idx in ((LS, LE, LW, LH, LK, LA), (RS, RE, RW, RH, RK, RA)):
        pts[idx[0]] = (sx, sy, 0, 0.9)
        pts[idx[1]] = (ex, ey, 0, 0.9)
        pts[idx[2]] = (wx, wy, 0, 0.9)
        pts[idx[3]] = (0.60, hip_y, 0, 0.9)
        pts[idx[4]] = (knee[0], knee[1], 0, 0.9)
        pts[idx[5]] = (ankle[0], ankle[1], 0, 0.9)
    return frame(ts, pts)


def standing(ts):
    pts = {}
    for idx in ((LS, LE, LW, LH, LK, LA), (RS, RE, RW, RH, RK, RA)):
        pts[idx[0]] = (0.45, 0.30, 0, 0.9)
        pts[idx[1]] = (0.45, 0.45, 0, 0.9)
        pts[idx[2]] = (0.45, 0.55, 0, 0.9)
        pts[idx[3]] = (0.47, 0.50, 0, 0.9)
        pts[idx[4]] = (0.47, 0.70, 0, 0.9)
        pts[idx[5]] = (0.47, 0.85, 0, 0.9)
    return frame(ts, pts)


def squat(ts, knee_deg, hip_drop):
    """侧视/正视深蹲。hip_drop∈[0,1] 表示下蹲程度；躯干长度固定（肩随髋一起下沉）。"""
    hip_y = 0.42 + hip_drop * 0.25; ankle_y = 0.82
    shoulder_y = hip_y - 0.20          # 躯干长度恒定 0.20（真实下蹲时肩会跟着下沉）
    half_h = (ankle_y - hip_y) / 2
    half = math.radians(clamp(knee_deg, 10, 178) / 2)
    fwd = half_h * math.tan(math.pi / 2 - half)
    knee_y = (hip_y + ankle_y) / 2
    pts = {NOSE: (0.45, shoulder_y - 0.07, 0, 0.9)}
    for idx in ((LS, LE, LW, LH, LK, LA), (RS, RE, RW, RH, RK, RA)):
        pts[idx[0]] = (0.45, shoulder_y, 0, 0.9)
        pts[idx[1]] = (0.45, shoulder_y + 0.10, 0, 0.9)
        pts[idx[2]] = (0.45, shoulder_y + 0.18, 0, 0.9)
        pts[idx[3]] = (0.45, hip_y, 0, 0.9)
        pts[idx[4]] = (0.45 + fwd, knee_y, 0, 0.9)
        pts[idx[5]] = (0.45, ankle_y, 0, 0.9)
    return frame(ts, pts)


def pullup(ts, elbow_deg, body_rise):
    hand_y = 0.16
    shoulder_y = 0.36 - body_rise * 0.18
    hip_y = 0.60 - body_rise * 0.18
    ankle_y = 0.86 - body_rise * 0.18
    mid_y = (shoulder_y + hand_y) / 2
    half_reach = abs(shoulder_y - hand_y) / 2
    half = math.radians(clamp(elbow_deg, 10, 178) / 2)
    bulge = half_reach / math.tan(half)
    pts = {NOSE: (0.5, shoulder_y - 0.04, 0, 0.9)}
    for i, idx in enumerate(((LS, LE, LW, LH, LK, LA), (RS, RE, RW, RH, RK, RA))):
        sign = 1 if i == 0 else -1
        pts[idx[0]] = (0.5, shoulder_y, 0, 0.9)
        pts[idx[1]] = (0.5 + bulge * sign, mid_y, 0, 0.9)
        pts[idx[2]] = (0.5, hand_y, 0, 0.9)
        pts[idx[3]] = (0.5, hip_y, 0, 0.9)
        pts[idx[4]] = (0.5, (hip_y + ankle_y) / 2, 0, 0.9)
        pts[idx[5]] = (0.5, ankle_y, 0, 0.9)
    return frame(ts, pts)


def plank(ts, sag, arms=True):
    sx, hx, ax = 0.25, 0.55, 0.85
    sy, hy, ay = 0.55, 0.55 + sag, 0.58
    pts = {NOSE: (0.18, 0.53, 0, 0.9)}
    for idx in ((LS, LE, LW, LH, LK, LA), (RS, RE, RW, RH, RK, RA)):
        pts[idx[0]] = (sx, sy, 0, 0.9)
        pts[idx[1]] = (sx, (sy + 0.78) / 2, 0, 0.9)
        pts[idx[2]] = (sx, 0.78, 0, 0.9)
        pts[idx[3]] = (hx, hy, 0, 0.9)
        pts[idx[4]] = ((hx + ax) / 2, hy, 0, 0.9)
        pts[idx[5]] = (ax, ay, 0, 0.9)
    return frame(ts, pts)


def handstand(ts, tilt, inverted=True, ankle_drift=0.0, hip_x=0.5):
    hip_y = 0.45; shoulder_y = 0.62 if inverted else 0.30
    ankle_y = 0.18 if inverted else 0.75
    dx = math.tan(math.radians(tilt)) * (shoulder_y - hip_y)
    pts = {NOSE: (hip_x + dx, shoulder_y + 0.03, 0, 0.9)}
    for idx in ((LS, LE, LW, LH, LK, LA), (RS, RE, RW, RH, RK, RA)):
        pts[idx[0]] = (hip_x + dx, shoulder_y, 0, 0.9)
        pts[idx[1]] = (hip_x + dx + 0.03, shoulder_y + 0.06, 0, 0.9)
        pts[idx[2]] = (hip_x + dx + 0.03, 0.76, 0, 0.9)
        pts[idx[3]] = (hip_x, hip_y, 0, 0.9)
        pts[idx[4]] = (hip_x + ankle_drift / 2, (hip_y + ankle_y) / 2, 0, 0.9)
        pts[idx[5]] = (hip_x + ankle_drift, ankle_y, 0, 0.9)
    return frame(ts, pts)


def rep_profile(bottom, top, per_half=8):
    down = [top + (bottom - top) * (i + 1) / per_half for i in range(per_half)]
    up = [bottom + (top - bottom) * (i + 1) / per_half for i in range(per_half)]
    return down + up


# ---------------- 深蹲 ----------------
class Squat:
    DEPTH = 100.0; PARTIAL = 125.0; STAND = 155.0; START = 140.0
    MIN_TRAVEL = 0.12; MAX_LEAN = 55.0; MIN_REP = 320; DROPOUT = 500; SMOOTH = 0.5
    HIP_TRAVEL_DEEP = 0.35         # 髋部下移 ≥0.35×躯干 = 蹲到位（正对镜头时的主要判据）
    HIP_TRAVEL_PARTIAL = 0.18      # 明显下蹲但不到位

    def __init__(self):
        self.sm_knee = float('nan'); self.sm_hip = float('nan'); self.stand_hip = float('nan')
        self.down = False; self.down_start = 0; self.rep_min_knee = float('nan')
        self.rep_max_lean = 0.0; self.rep_max_travel = 0.0
        self.good = 0; self.bad = 0; self.form = 0; self.last_valid = None; self.status = ''
        self.personal = None; self.observed_stand_knee = float('nan'); self.observed_stand_hip = float('nan')
        self.await_stand = False; self.sample = None
        self.stand_knee_samples = StableExtremum(True); self.stand_hip_samples = StableExtremum(False)

    def clear_calibration_samples(self):
        self.stand_knee_samples.clear(); self.stand_hip_samples.clear()

    def process(self, f):
        self.sample = None
        now = f.ts
        def dropout():
            if self.last_valid is not None and now - self.last_valid > self.DROPOUT:
                self.down = False; self.rep_min_knee = float('nan')
                self.rep_max_lean = 0.; self.rep_max_travel = 0.
                self.sm_knee = float('nan'); self.sm_hip = float('nan')
        if not f.visible() or not body_metrics(f).valid:
            self.clear_calibration_samples(); dropout(); return
        m = body_metrics(f)
        hip_below = m.hy > m.sy + 0.02
        ankle_below = (not m.has_ankle) or (m.ankle_y > m.hy - 0.02)
        if not hip_below or not ankle_below:
            self.clear_calibration_samples(); self.down = False; self.status = 'not standing'; return
        knee = avg_angle(f, LH, LK, LA, RH, RK, RA)
        if math.isnan(knee):
            self.clear_calibration_samples(); dropout(); return
        dropout(); self.last_valid = now
        self.sm_knee = knee if math.isnan(self.sm_knee) else self.sm_knee + self.SMOOTH * (knee - self.sm_knee)
        self.sm_hip = m.hy if math.isnan(self.sm_hip) else self.sm_hip + self.SMOOTH * (m.hy - self.sm_hip)
        self.observed_stand_knee = self.stand_knee_samples.observe(knee)
        self.observed_stand_hip = self.stand_hip_samples.observe(self.sm_hip)
        if knee >= self.stand_threshold():
            self.stand_hip = self.sm_hip if math.isnan(self.stand_hip) else min(self.stand_hip, self.sm_hip)
        travel = 0.0 if math.isnan(self.stand_hip) else (self.sm_hip - self.stand_hip) / max(0.04, m.body_scale)
        lean = math.degrees(math.atan2(abs(m.sx - m.hx), abs(m.sy - m.hy) + 1e-5))
        if m.has_ankle and not math.isnan(self.observed_stand_hip):
            candidate_travel = (self.sm_hip - self.observed_stand_hip) / max(0.04, m.body_scale)
            self.sample = (knee, candidate_travel, lean)
        if self.await_stand:
            if self.sm_knee >= self.stand_threshold():
                self.await_stand = False; self.down = False; self.status = 'stand'
            else:
                self.status = 'calibrated, return to stand'
            return
        if not self.down:
            started = (self.sm_knee < self.START if self.personal is None else
                       self.depth_score(self.sm_knee, travel, self.personal['start']) >= max(0.10, self.personal['target'] * 0.5))
            if started:
                self.down = True; self.down_start = now; self.rep_min_knee = knee
                self.rep_max_lean = lean; self.rep_max_travel = travel
            else:
                self.status = 'stand'; return
        else:
            self.rep_min_knee = min(self.rep_min_knee, knee)
            self.rep_max_lean = max(self.rep_max_lean, lean)
            self.rep_max_travel = max(self.rep_max_travel, travel)
        if self.sm_knee >= self.stand_threshold():
            if self.down:
                self.finish(now - self.down_start)
            self.status = 'stand'
        else:
            self.status = 'down'

    def stand_threshold(self):
        return self.STAND if self.personal is None else self.personal['start'] - 2

    @classmethod
    def depth_score(cls, knee, travel, start):
        reference = min(cls.DEPTH, start - 20)
        angle = 0.0 if math.isnan(knee) else max(0, (start - knee) / (start - reference))
        return max(angle, max(0, travel) / cls.HIP_TRAVEL_DEEP)

    def capture(self, raw):
        if self.await_stand or math.isnan(self.observed_stand_knee) or self.sample is None:
            return None
        m = body_metrics(raw); raw_knee = avg_angle(raw, LH, LK, LA, RH, RK, RA)
        if not m.valid or not m.has_ankle or math.isnan(raw_knee): return None
        knee, travel, lean = self.sample
        raw_travel = (m.hy - self.observed_stand_hip) / max(0.04, m.body_scale)
        raw_lean = math.degrees(math.atan2(abs(m.sx - m.hx), abs(m.sy - m.hy) + 1e-5))
        if abs(raw_knee - knee) > 12 or abs(raw_travel - travel) > .15 or abs(raw_lean - lean) > 15: return None
        score = self.depth_score(knee, travel, self.observed_stand_knee)
        if score < 0.18 or travel < 0.04: return None
        return dict(type='squat', variant='', start=self.observed_stand_knee, target=clamp(score * 0.95, 0.15, 5.0),
                    angle=knee, movement=travel, max_form=max(self.MAX_LEAN, lean + 3), min_hip=0,
                    min_movement=min(self.MIN_TRAVEL, max(0.03, travel * 0.8)), kneel=False,
                    form_warning=lean > self.MAX_LEAN)

    def apply_event(self, calibration):
        if calibration is None or calibration['type'] != 'squat': return
        self.personal = calibration
        if not math.isnan(self.observed_stand_hip): self.stand_hip = self.observed_stand_hip
        self.down = False; self.await_stand = True; self.good += 1
        if calibration['form_warning']: self.form += 1

    def finish(self, dur):
        mk, ml, tr = self.rep_min_knee, self.rep_max_lean, self.rep_max_travel
        self.down = False; self.rep_min_knee = float('nan'); self.rep_max_lean = 0.0; self.rep_max_travel = 0.0
        if dur < self.MIN_REP:
            return
        # 膝角（侧对最准）+ 髋部下移比例（正对/斜对都可靠），取较大者
        standard_depth = (mk <= self.DEPTH) or (tr >= self.HIP_TRAVEL_DEEP)
        reached = self.depth_score(mk, tr, self.STAND if self.personal is None else self.personal['start'])
        deep = standard_depth if self.personal is None else reached >= self.personal['target']
        moved = ((mk <= self.PARTIAL) or (tr >= self.HIP_TRAVEL_PARTIAL) or
                 (self.personal is not None and reached >= self.personal['target'] * 0.65))
        hip_moved = tr >= (self.MIN_TRAVEL if self.personal is None else self.personal['min_movement'])
        lean_ok = ml <= (self.MAX_LEAN if self.personal is None else self.personal['max_form'])
        if not hip_moved:
            return
        if (not lean_ok) or (not deep):
            if (not moved) and (not lean_ok):
                return
            self.bad += 1; self.form += 1
        else:
            self.good += 1
            if self.personal is not None and ml > self.MAX_LEAN: self.form += 1


# ---------------- 引体向上 ----------------
class PullUp:
    HANG = 150.0; PULL = 95.0; PARTIAL = 118.0; START = 145.0
    MIN_RISE = 0.10; MIN_ABOVE = 0.15; MIN_REP = 400; DROPOUT = 600; SMOOTH = 0.5

    def __init__(self):
        self.sm = float('nan'); self.hang_reach = float('nan')
        self.pull = False; self.pull_start = 0; self.rep_min = float('nan'); self.rep_max_rise = 0.0
        self.good = 0; self.bad = 0; self.form = 0; self.status = ''; self.last_valid = None
        self.personal = None; self.observed_hang_angle = float('nan'); self.observed_hang_reach = float('nan')
        self.await_hang = False; self.sample = None
        self.hang_angle_samples = StableExtremum(True); self.hang_reach_samples = StableExtremum(True)

    def clear_calibration_samples(self):
        self.hang_angle_samples.clear(); self.hang_reach_samples.clear()

    def process(self, f):
        self.sample = None
        m = body_metrics(f)
        def dropout():
            if self.last_valid is not None and f.ts - self.last_valid > self.DROPOUT:
                self.pull = False; self.rep_min = float('nan'); self.rep_max_rise = 0.
        if not f.visible() or not m.valid:
            self.clear_calibration_samples(); dropout(); return
        elbow = avg_angle(f, LS, LE, LW, RS, RE, RW)
        if math.isnan(elbow) or math.isnan(m.wrist_drop):
            self.clear_calibration_samples(); dropout(); return
        dropout(); self.last_valid = f.ts
        self.sm = elbow if math.isnan(self.sm) else self.sm + self.SMOOTH * (elbow - self.sm)
        decision = elbow
        if -m.wrist_drop < self.MIN_ABOVE:
            self.clear_calibration_samples(); self.pull = False; self.status = 'not hanging'; return
        self.observed_hang_angle = self.hang_angle_samples.observe(elbow)
        self.observed_hang_reach = self.hang_reach_samples.observe(-m.wrist_drop)
        if decision >= self.hang_threshold():
            self.hang_reach = (-m.wrist_drop) if math.isnan(self.hang_reach) else max(self.hang_reach, -m.wrist_drop)
        rise = 0.0 if math.isnan(self.hang_reach) else self.hang_reach - (-m.wrist_drop)
        if not math.isnan(self.observed_hang_reach): self.sample = (elbow, self.observed_hang_reach - (-m.wrist_drop))
        if self.await_hang:
            if decision >= self.hang_threshold():
                self.await_hang = False; self.pull = False; self.status = 'hang'
            else:
                self.status = 'calibrated, return to hang'
            return
        now = f.ts
        if not self.pull:
            start_pull = (self.START if self.personal is None else self.personal['start'] - max(3, min(5,
                (self.personal['start'] - min(self.PULL, self.personal['start'] - 20)) * self.personal['target'] * 0.45)))
            if decision < start_pull:
                self.pull = True; self.pull_start = now; self.rep_min = elbow; self.rep_max_rise = rise
            else:
                self.status = 'hang'; return
        else:
            self.rep_min = min(self.rep_min, elbow); self.rep_max_rise = max(self.rep_max_rise, rise)
        if decision >= self.hang_threshold():
            if self.pull:
                self.finish(now - self.pull_start)
            self.status = 'hang'
        else:
            self.status = 'pull'

    def hang_threshold(self):
        return self.HANG if self.personal is None else self.personal['start'] - 2

    @classmethod
    def depth_score(cls, elbow, start):
        return 0 if math.isnan(elbow) else max(0, (start - elbow) / (start - min(cls.PULL, start - 20)))

    def capture(self, raw):
        if self.await_hang or math.isnan(self.observed_hang_angle) or self.sample is None: return None
        m = body_metrics(raw); raw_elbow = avg_angle(raw, LS, LE, LW, RS, RE, RW)
        if not m.valid or math.isnan(m.wrist_drop) or math.isnan(raw_elbow): return None
        elbow, rise = self.sample
        raw_rise = self.observed_hang_reach - (-m.wrist_drop)
        if abs(raw_elbow - elbow) > 12 or abs(raw_rise - rise) > .15: return None
        score = self.depth_score(elbow, self.observed_hang_angle)
        if score < 0.18 or rise < 0.02: return None
        return dict(type='pullup', variant='', start=self.observed_hang_angle, target=clamp(score * 0.95, 0.15, 5.0),
                    angle=elbow, movement=rise, max_form=0, min_hip=0,
                    min_movement=min(self.MIN_RISE, max(0.02, rise * 0.8)), kneel=False,
                    form_warning=rise < self.MIN_RISE)

    def apply_event(self, calibration):
        if calibration is None or calibration['type'] != 'pullup': return
        self.personal = calibration
        if not math.isnan(self.observed_hang_reach): self.hang_reach = self.observed_hang_reach
        self.pull = False; self.await_hang = True; self.good += 1
        if calibration['form_warning']: self.form += 1

    def finish(self, dur):
        mn, rise = self.rep_min, self.rep_max_rise
        self.pull = False; self.rep_min = float('nan'); self.rep_max_rise = 0.0
        if dur < self.MIN_REP:
            return
        standard_depth = mn <= self.PULL; reached = self.depth_score(mn, self.HANG if self.personal is None else self.personal['start'])
        deep = standard_depth if self.personal is None else reached >= self.personal['target']
        moved = mn <= self.PARTIAL or (self.personal is not None and reached >= self.personal['target'] * 0.65)
        rose = rise >= (self.MIN_RISE if self.personal is None else self.personal['min_movement'])
        if not rose:
            return
        if not deep:
            if not moved:
                return
            self.bad += 1; self.form += 1
        else:
            self.good += 1
            if self.personal is not None and rise < self.MIN_RISE: self.form += 1


# ---------------- 平板支撑 ----------------
class Plank:
    MAX_TORSO_H = 30.0; SAG_LIMIT = 0.15; MIN_WRIST_DROP = 0.05; MIN_HIP_LIFT = 0.02
    ARM = 500; GRACE = 800; DROPOUT = 600

    def __init__(self):
        self.hold_start = None; self.cand = None; self.last_good = None; self.last_valid = None
        self.cur = 0; self.best = 0; self.total = 0; self.attempts = 0
        self.sag_frames = 0; self.good_frames = 0; self.sag_warn = 0; self.status = ''
        self.clock = HoldClock()

    def process(self, f):
        now = f.ts
        if not f.visible():
            self.cand = None
            self.clock.invalidate()
            if self.last_valid is not None and now - self.last_valid > self.DROPOUT:
                self.interrupt(now)
            return
        m = body_metrics(f)
        if not m.valid:
            self.clock.invalidate(); self.interrupt(now); return
        self.last_valid = now
        th = torso_horizontal(m)
        prone = (not math.isnan(th)) and th < self.MAX_TORSO_H
        hands = (not math.isnan(m.wrist_drop)) and m.wrist_drop > self.MIN_WRIST_DROP
        hip_lifted = m.has_ankle and m.hy < m.ankle_y - self.MIN_HIP_LIFT
        aligned = math.isnan(m.sag_ratio) or m.sag_ratio <= self.SAG_LIMIT
        if prone and hands and not aligned:
            self.sag_frames += 1
            if self.sag_frames % 15 == 1:
                self.sag_warn += 1
            self.status = 'sagging'
            self.cand = None
            self.clock.invalidate()
            if self.hold_start is not None:
                if self.last_good is not None and now - self.last_good <= self.GRACE:
                    pass
                else:
                    self.finish()
            return

        if prone and hands and hip_lifted and aligned:
            if self.hold_start is None:
                if self.cand is None:
                    self.cand = now
                if now - self.cand >= self.ARM:
                    self.hold_start = self.cand; self.attempts += 1
                    self.clock.begin(self.hold_start)
                    self.clock.accept(now)
                self.status = 'arming'
            else:
                self.clock.accept(now)
                self.cur = self.clock.valid_ms(); self.last_good = now
                self.good_frames += 1; self.status = 'holding'
            return
        self.status = 'bad'
        self.cand = None
        self.clock.invalidate()
        if self.hold_start is not None:
            if self.last_good is not None and now - self.last_good <= self.GRACE:
                self.status += ' (grace)'
            else:
                self.finish()

    def interrupt(self, now):
        self.cand = None
        self.clock.invalidate()
        if self.hold_start is not None and (self.last_good is None or now - self.last_good > self.GRACE):
            self.finish()

    def finish(self):
        dur = self.clock.valid_ms()
        if dur > 0:
            self.best = max(self.best, dur); self.total += dur; self.cur = dur
        self.hold_start = None; self.last_good = None; self.cand = None
        self.clock.end()

    def current(self):
        if self.hold_start is None:
            return 0
        return self.clock.valid_ms()

    def interrupted_ms(self, now):
        return self.clock.interrupt_ms(now)

    def best_ms(self):
        return max(self.best, self.current())


# ---------------- 倒立 ----------------
class Handstand:
    TILT_FREE = 25.0; TILT_WALL = 32.0; DRIFT_FREE = 0.18; DRIFT_WALL = 0.30
    ANKLE_DRIFT = 0.25; ARM = 300; GRACE = 700; HISTORY = 12

    def __init__(self, wall=False):
        self.wall = wall
        self.hip = []; self.ankle = []; self.scale = []
        self.hold_start = None; self.cand = None; self.last_good = None
        self.best = 0; self.total = 0; self.attempts = 0; self.cur = 0
        self.clock = HoldClock()
        self.wall_frames = 0; self.hold_frames = 0; self.tilt_sum = 0.0

    def process(self, f):
        now = f.ts
        if not f.visible():
            self.clock.invalidate()
            if self.hold_start is not None and self.last_good is not None and now - self.last_good > self.GRACE:
                self.finish()
            return
        m = body_metrics(f)
        if not m.valid:
            self.clock.invalidate()
            return
        left_ankle, right_ankle = f.rel(LA), f.rel(RA)
        ankles_above = ((left_ankle or right_ankle) and (not left_ankle or f.y(LA) < m.sy)
                        and (not right_ankle or f.y(RA) < m.sy))
        inverted = m.sy > m.hy + 0.02 and ankles_above
        tilt = math.degrees(math.atan2(abs(m.sx - m.hx), abs(m.sy - m.hy) + 1e-5))
        ankle_x = None
        if m.has_ankle:
            ankle_x = m.ankle_x
        self.hip.append(m.hx); self.ankle.append(m.hx if ankle_x is None else ankle_x)
        self.scale.append(max(0.04, m.body_scale))
        for arr in (self.hip, self.ankle, self.scale):
            if len(arr) > self.HISTORY:
                arr.pop(0)
        hip_drift = (max(self.hip) - min(self.hip)) / (sum(self.scale) / len(self.scale))
        ankle_drift = (max(self.ankle) - min(self.ankle)) / (sum(self.scale) / len(self.scale))
        max_tilt = self.TILT_WALL if self.wall else self.TILT_FREE
        max_drift = self.DRIFT_WALL if self.wall else self.DRIFT_FREE
        vertical = tilt <= max_tilt
        steady = len(self.hip) < 3 or hip_drift <= max_drift
        if ankle_x is not None and ankle_drift <= self.ANKLE_DRIFT:
            self.wall_frames += 1
        if inverted and vertical and steady:
            if self.hold_start is None:
                if self.cand is None:
                    self.cand = now
                if now - self.cand >= self.ARM:
                    self.hold_start = self.cand; self.attempts += 1
                    self.clock.begin(self.hold_start)
                    self.clock.accept(now)
            else:
                self.clock.accept(now)
                self.cur = self.clock.valid_ms(); self.last_good = now
                self.hold_frames += 1; self.tilt_sum += tilt
        else:
            self.cand = None
            self.clock.invalidate()
            if self.hold_start is not None:
                if self.last_good is not None and now - self.last_good <= self.GRACE:
                    self.cur = self.clock.valid_ms()
                else:
                    self.finish()

    def finish(self):
        dur = self.clock.valid_ms()
        if dur > 0:
            self.best = max(self.best, dur); self.total += dur; self.cur = dur
        self.hold_start = None; self.last_good = None; self.cand = None
        self.clock.end()

    def current(self):
        if self.hold_start is None:
            return 0
        return self.clock.valid_ms()

    def best_ms(self):
        return max(self.best, self.current())

    def stability(self):
        if self.hold_frames == 0 or len(self.hip) < 3:
            return 0.6 if self.hold_frames > 0 else 0.0
        max_drift = self.DRIFT_WALL if self.wall else self.DRIFT_FREE
        drift = (max(self.hip) - min(self.hip)) / (sum(self.scale) / len(self.scale))
        drift_score = 1 - min(1, drift / max(0.01, max_drift))
        avg_tilt = self.tilt_sum / max(1, self.hold_frames)
        max_tilt = self.TILT_WALL if self.wall else self.TILT_FREE
        tilt_score = 1 - min(1, avg_tilt / max(1, max_tilt))
        return max(0, min(1, drift_score * 0.6 + tilt_score * 0.4))


# ---------------- 靠墙蹲计时 ----------------
class WallSit:
    MIN_KNEE = 60.0; MAX_KNEE = 110.0      # 有效坐姿的膝角范围
    MAX_THIGH_RISE = 0.30                   # 大腿接近水平（髋膝高度差 / 躯干长度）
    MAX_TORSO_LEAN = 30.0                   # 上身竖直（背靠墙）
    ARM = 400; GRACE = 800; DROPOUT = 600

    def __init__(self):
        self.hold_start = None; self.cand = None; self.last_good = None; self.last_valid = None
        self.cur = 0; self.best = 0; self.total = 0; self.attempts = 0
        self.good_frames = 0; self.bad_frames = 0; self.knee_sum = 0.0; self.knee_sq = 0.0
        self.status = ''; self.last_knee = float('nan')
        self.clock = HoldClock()

    def process(self, f):
        now = f.ts
        if not f.visible():
            self.clock.invalidate(); self.interrupt(now); self.status = 'no person'; return
        m = body_metrics(f)
        if not m.valid:
            self.clock.invalidate(); self.interrupt(now); self.status = 'no body'; return
        self.last_valid = now

        knee = avg_angle(f, LH, LK, LA, RH, RK, RA)
        self.last_knee = knee
        if math.isnan(knee):
            self.status = 'legs missing'; self.clock.invalidate(); self.interrupt(now); return

        hip_below_shoulder = m.hy > m.sy + 0.02
        ankle_below_knee = m.has_ankle and m.ankle_y > (m.hy + m.ankle_y) / 2 - 0.05
        if f.rel(LK) and f.rel(RK):
            lc, rc = f.conf(LK), f.conf(RK)
            knee_y = (f.y(LK) if lc > rc else f.y(RK)) if abs(lc - rc) > .25 else (f.y(LK) * lc + f.y(RK) * rc) / (lc + rc)
        else:
            knee_y = mid_of(f, LK, RK, Y)
        thigh_rise = abs(m.hy - knee_y) / max(0.04, m.body_scale)
        lean = math.degrees(math.atan2(abs(m.sx - m.hx), abs(m.sy - m.hy) + 1e-5))

        sitting = (self.MIN_KNEE <= knee <= self.MAX_KNEE) and hip_below_shoulder \
            and thigh_rise <= self.MAX_THIGH_RISE and lean <= self.MAX_TORSO_LEAN

        if sitting:
            if self.hold_start is None:
                if self.cand is None:
                    self.cand = now
                if now - self.cand >= self.ARM:
                    self.hold_start = self.cand; self.attempts += 1
                    self.clock.begin(self.hold_start)
                    self.clock.accept(now)
                self.status = 'arming'
            else:
                self.clock.accept(now)
                self.cur = self.clock.valid_ms(); self.last_good = now
                self.good_frames += 1; self.knee_sum += knee; self.knee_sq += knee * knee
                self.status = 'holding'
            return

        # 不合格帧
        self.cand = None
        self.clock.invalidate()
        if knee > self.MAX_KNEE:
            self.status = 'stood up'
        elif thigh_rise > self.MAX_THIGH_RISE:
            self.status = 'thigh not level'
        elif lean > self.MAX_TORSO_LEAN:
            self.status = 'leaning forward'
        else:
            self.status = 'bad pose'
        self.bad_frames += 1
        if self.hold_start is not None:
            if self.last_good is not None and now - self.last_good <= self.GRACE:
                self.status += ' (grace)'
            else:
                self.finish()

    def interrupt(self, now):
        self.cand = None
        self.clock.invalidate()
        if self.hold_start is not None and (self.last_good is None or now - self.last_good > self.DROPOUT):
            self.finish()

    def finish(self):
        dur = self.clock.valid_ms()
        if dur > 0:
            self.best = max(self.best, dur); self.total += dur; self.cur = dur
        self.hold_start = None; self.last_good = None; self.cand = None
        self.clock.end()

    def current(self):
        if self.hold_start is None:
            return 0
        return self.clock.valid_ms()

    def interrupted_ms(self, now):
        return self.clock.interrupt_ms(now)

    def best_ms(self):
        return max(self.best, self.current())

    def stability(self):
        if self.good_frames == 0:
            return 0.0
        mean = self.knee_sum / self.good_frames
        var = max(0.0, self.knee_sq / self.good_frames - mean * mean)
        std = math.sqrt(var)
        return max(0.0, min(1.0, 1.0 - std / 20.0))


# ---------------- 跑步计步 ----------------
class RunSteps:
    """用左右脚交替抬起判定步数（关节高度差的符号翻转 + 迟滞 + 步频约束）。"""
    SIGNAL_ON = 0.16        # 触发所需的左右脚高度差（×躯干长度）
    MIN_STEP_MS = 180       # 最快步频 ~333 步/分
    MAX_STEP_MS = 1600      # 超过视为停止/换动作，重新开始而不计数
    DROPOUT_MS = 700

    MIN_STABLE = 2
    RUN_CADENCE = 150.0     # 步频 ≥150 直接判跑步
    WALK_CADENCE = 120.0    # 步频 ≤120 直接判走路
    HIP_BOB_RUN = 0.05      # 中间的模糊区间，用髋部上下起伏（×躯干）区分
    BOB_WINDOW = 20         # 起伏统计窗口（帧）

    def __init__(self):
        self.steps = 0
        self.last_sign = 0
        self.stable_frames = 0
        self.held_frames = 0
        self.last_step_ms = None
        self.intervals = []
        self.last_valid = None
        self.status = ''
        self.max_signal = 0.0
        self.hip_hist = []
        self.last_scale = 0.04

    def process(self, f):
        now = f.ts
        if self.last_valid is not None and now - self.last_valid > self.DROPOUT_MS:
            self.last_sign = self.stable_frames = self.held_frames = 0
            self.last_step_ms = None; self.intervals.clear(); self.hip_hist.clear()
        if not f.visible():
            self.status = 'no person'
            return
        m = body_metrics(f)
        if not m.valid or not m.has_ankle:
            self.status = 'no legs'
            return
        if not (f.rel(LA) and f.rel(RA)):
            self.status = 'one leg hidden'
            return
        hip_below_shoulder = m.hy > m.sy + 0.02
        if not hip_below_shoulder:
            self.status = 'not running posture'
            return
        self.last_valid = now

        scale = max(0.04, m.body_scale)
        self.last_scale = scale
        self.hip_hist.append(m.hy)
        if len(self.hip_hist) > self.BOB_WINDOW:
            self.hip_hist.pop(0)
        signal = (f.y(RA) - f.y(LA)) / scale   # >0 表示左脚更高
        self.max_signal = max(self.max_signal, abs(signal))

        sign = 0
        if signal >= self.SIGNAL_ON:
            sign = 1
        elif signal <= -self.SIGNAL_ON:
            sign = -1

        if sign == 0:
            self.held_frames = self.stable_frames
            self.stable_frames = 0
            self.status = 'running'
            return

        if sign == self.last_sign:
            self.stable_frames += 1
            self.status = 'running'
            return

        previous = max(self.held_frames, self.stable_frames)
        trustworthy = self.last_sign == 0 or previous >= self.MIN_STABLE
        if trustworthy:
            # 与 Java 一致：第一次出现某一侧时只记录时间，不计数
            since = 10 ** 9 if self.last_step_ms is None else now - self.last_step_ms
            if since >= self.MIN_STEP_MS:
                if since <= self.MAX_STEP_MS:
                    self.steps += 1
                    self.intervals.append(since)
                    if len(self.intervals) > 10:
                        self.intervals.pop(0)
                self.last_step_ms = now
        self.held_frames = self.stable_frames
        self.stable_frames = 1
        self.last_sign = sign
        self.status = 'running'

    def hip_bob(self):
        """躯干上下起伏（峰峰值 / 躯干长度）；数据不足返回 NaN。"""
        if len(self.hip_hist) < 4:
            return float('nan')
        return (max(self.hip_hist) - min(self.hip_hist)) / max(0.04, self.last_scale)

    def gait(self):
        """走 / 跑分类：步频为主，模糊区间用躯干起伏。"""
        if self.steps < 2:
            return 'UNKNOWN'
        c = self.cadence()
        if c <= 0:
            return 'UNKNOWN'
        if c >= self.RUN_CADENCE:
            return 'RUN'
        if c <= self.WALK_CADENCE:
            return 'WALK'
        bob = self.hip_bob()
        if math.isnan(bob):
            return 'RUN' if c >= (self.RUN_CADENCE + self.WALK_CADENCE) / 2 else 'WALK'
        return 'RUN' if bob >= self.HIP_BOB_RUN else 'WALK'

    def cadence(self):
        if not self.intervals:
            return 0
        avg = sum(self.intervals) / len(self.intervals)
        return 60000.0 / avg if avg > 0 else 0


# ---------------- 断言 ----------------
res = []



# ---------------- 跑步 / 靠墙蹲的合成帧（与 SyntheticPoses.running / wallSit 同构） ----------------
def running(ts, lift_left=0.0, lift_right=0.0, hip_bob=0.0):
    """左右脚交替抬起；lift 越大该侧脚踝越高，hip_bob 是躯干上下起伏（跑步比走路大）。"""
    hip_y, shoulder_y = 0.50 - hip_bob, 0.24 - hip_bob
    pts = {NOSE: (0.5, shoulder_y - 0.06, 0, 0.9)}
    for base_idx, lift in (((LS, LE, LW, LH, LK, LA), lift_left),
                           ((RS, RE, RW, RH, RK, RA), lift_right)):
        pts[base_idx[0]] = (0.44, shoulder_y, 0, 0.9)
        pts[base_idx[1]] = (0.45, shoulder_y + 0.14, 0, 0.9)
        pts[base_idx[2]] = (0.45, shoulder_y + 0.24, 0, 0.9)
        pts[base_idx[3]] = (0.5, hip_y, 0, 0.9)
        pts[base_idx[4]] = (0.5, hip_y + 0.16 - lift * 0.5, 0, 0.9)
        pts[base_idx[5]] = (0.5, hip_y + 0.34 - lift, 0, 0.9)
    return frame(ts, pts)


def wall_sit(ts, hip_lift=0.0):
    """蹲姿：hip_lift = 髋部高于膝盖的量（0 = 大腿水平，标准姿势）。"""
    knee_x, knee_y = 0.62, 0.70
    hip_x = knee_x - 0.18
    hip_y = knee_y - hip_lift
    shoulder_y = hip_y - 0.28
    pts = {NOSE: (hip_x, shoulder_y - 0.06, 0, 0.9)}
    for base_idx in ((LS, LE, LW, LH, LK, LA), (RS, RE, RW, RH, RK, RA)):
        pts[base_idx[0]] = (hip_x, shoulder_y, 0, 0.9)
        pts[base_idx[1]] = (hip_x + 0.02, shoulder_y + 0.10, 0, 0.9)
        pts[base_idx[2]] = (hip_x + 0.02, shoulder_y + 0.17, 0, 0.9)
        pts[base_idx[3]] = (hip_x, hip_y, 0, 0.9)
        pts[base_idx[4]] = (knee_x, knee_y, 0, 0.9)
        pts[base_idx[5]] = (knee_x, knee_y + 0.20, 0, 0.9)
    return frame(ts, pts)
