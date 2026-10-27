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
        self.sx = self.sy = self.hx = self.hy = 0.0


def body_metrics(f):
    m = BM()
    if f is None or not f.visible():
        return m
    if not (f.rel(LS) or f.rel(RS)) or not (f.rel(LH) or f.rel(RH)):
        return m
    sx = mid_of(f, LS, RS, X); sy = mid_of(f, LS, RS, Y)
    hx = mid_of(f, LH, RH, X); hy = mid_of(f, LH, RH, Y)
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
        ax = mid_of(f, LA, RA, X); ay = mid_of(f, LA, RA, Y)
        m.ankle_y = ay
        abx, aby = ax - sx, ay - sy
        l2 = abx * abx + aby * aby
        if l2 > 1e-8:
            t = ((hx - sx) * abx + (hy - sy) * aby) / l2
            px, py = sx + t * abx, sy + t * aby
            m.sag_ratio = dist((hx, hy), (px, py)) / m.body_scale
            cross = (ax - sx) * (hy - sy) - (ay - sy) * (hx - sx)
            m.sag_side = 0 if abs(cross) < 1e-6 else (1 if cross > 0 else -1)
    else:
        m.ankle_y = float('nan')
    wx = mid_of(f, LW, RW, X); wy = mid_of(f, LW, RW, Y)
    m.wrist_drop = (wy - sy) / m.body_scale if wy is not None else float('nan')
    m.valid = True
    return m


def torso_horizontal(m):
    dx = abs(m.sx - m.hx); dy = abs(m.sy - m.hy)
    vertical = math.degrees(math.atan2(dx, dy + 1e-5))
    return 90 - vertical


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

    def depth_angle(self):
        return self.DEPTH + 6 if (self.type == 'chair_pushup' and self.variant == 'hands') else self.DEPTH

    def partial_angle(self):
        return max(self.depth_angle() + 8, self.PARTIAL)

    def process(self, f):
        now = f.ts
        if not f.visible():
            self.dropout(now); return
        m = body_metrics(f)
        if not m.valid:
            self.dropout(now); return
        self.last_valid = now
        elbow = avg_angle(f, LS, LE, LW, RS, RE, RW)
        th = torso_horizontal(m)
        prone = not math.isnan(th) and th < self.MAX_TORSO_H
        hands = not math.isnan(m.wrist_drop) and m.wrist_drop > self.MIN_WRIST_DROP
        if not prone or not hands:
            self.drop_phase(); self.status = 'not pushup'; return
        elbow_top = math.isnan(elbow) or elbow >= self.TOP - 6
        if elbow_top and not math.isnan(m.wrist_drop):
            self.top_drop = m.wrist_drop if math.isnan(self.top_drop) else max(self.top_drop, m.wrist_drop)
        depth_by_angle = float('nan') if math.isnan(elbow) else clamp((self.TOP - elbow) / (self.TOP - self.depth_angle()), 0, 1.25)
        depth_by_drop = self.drop_depth(m.wrist_drop)
        depth = depth_by_angle
        if not math.isnan(depth_by_drop):
            depth = depth_by_drop if math.isnan(depth_by_angle) else max(depth_by_angle, depth_by_drop)
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

        if not self.down:
            if depth > self.DOWN_START:
                self.down = True; self.down_start = now
                self.rep_max_depth = depth; self.rep_min_elbow = elbow
                self.rep_max_drop = 0.0 if math.isnan(depth_by_drop) else depth_by_drop
                self.rep_max_sag = sag; self.rep_straight = straight; self.rep_kneeling = kneeling
            else:
                self.status = 'up'; return
        else:
            self.rep_max_depth = max(self.rep_max_depth, depth)
            if not math.isnan(elbow):
                self.rep_min_elbow = elbow if math.isnan(self.rep_min_elbow) else min(self.rep_min_elbow, elbow)
            if not math.isnan(depth_by_drop):
                self.rep_max_drop = max(self.rep_max_drop, depth_by_drop)
            self.rep_max_sag = max(self.rep_max_sag, sag)
            if not straight: self.rep_straight = False
            if kneeling: self.rep_kneeling = True
        if depth <= self.TOP_DEPTH:
            if self.down: self.finish(now - self.down_start)
            self.status = 'top'
        else:
            self.status = 'down'

    def drop_depth(self, wd):
        if math.isnan(wd) or math.isnan(self.top_drop) or self.top_drop <= self.MIN_WRIST_DROP:
            return float('nan')
        travel = self.top_drop * 0.72
        return clamp((self.top_drop - wd) / max(0.02, travel), 0, 1.25)

    def finish(self, dur):
        me, md = self.rep_min_elbow, self.rep_max_drop
        straight, kneeling, sag = self.rep_straight, self.rep_kneeling, self.rep_max_sag
        self.down = False
        self.rep_max_depth = 0.0; self.rep_min_elbow = float('nan'); self.rep_max_drop = 0.0
        self.rep_max_sag = 0.0; self.rep_straight = True; self.rep_kneeling = False
        if dur < self.MIN_REP:
            return
        deep = ((not math.isnan(me)) and me <= self.depth_angle()) or md >= self.FULL_DEPTH
        moved = ((not math.isnan(me)) and me <= self.partial_angle()) or md >= self.PARTIAL_DEPTH
        reason = None
        if kneeling: reason = 'kneel'
        elif not straight: reason = 'sag'
        elif not deep: reason = 'shallow'
        if (not straight) or kneeling:
            self.bad += 1; self.form += 1
        elif deep:
            self.good += 1
        elif moved:
            self.bad += 1; self.form += 1
        else:
            return
        self.events.append((reason is None, reason, round(me, 1) if not math.isnan(me) else None))

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


def avg_angle(f, aL, bL, cL, aR, bR, cR):
    l = angle(f, aL, bL, cL); r = angle(f, aR, bR, cR)
    hl = not math.isnan(l); hr = not math.isnan(r)
    if hl and hr: return (l + r) / 2
    return l if hl else r


# ---------------- 合成数据 ----------------
def pushup(ts, elbow_deg, sag=0.0, kneel=False):
    sx, sy, wx, wy = 0.40, 0.55, 0.40, 0.75
    half = (wy - sy) / 2
    h = half / math.tan(math.radians(elbow_deg) / 2)
    ex, ey = sx + h, (sy + wy) / 2
    hip_y = 0.55 + sag; ankle_y = 0.55
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
    shoulder_y = 0.22; hip_y = 0.42 + hip_drop * 0.25; ankle_y = 0.82
    half_h = (ankle_y - hip_y) / 2
    half = math.radians(clamp(knee_deg, 10, 178) / 2)
    fwd = half_h * math.tan(math.pi / 2 - half)
    knee_y = (hip_y + ankle_y) / 2
    pts = {NOSE: (0.45, 0.15, 0, 0.9)}
    for idx in ((LS, LE, LW, LH, LK, LA), (RS, RE, RW, RH, RK, RA)):
        pts[idx[0]] = (0.45, shoulder_y, 0, 0.9)
        pts[idx[1]] = (0.45, 0.32, 0, 0.9)
        pts[idx[2]] = (0.45, 0.40, 0, 0.9)
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

    def __init__(self):
        self.sm_knee = float('nan'); self.sm_hip = float('nan'); self.stand_hip = float('nan')
        self.down = False; self.down_start = 0; self.rep_min_knee = float('nan')
        self.rep_max_lean = 0.0; self.rep_max_travel = 0.0
        self.good = 0; self.bad = 0; self.form = 0; self.last_valid = None; self.status = ''

    def process(self, f):
        now = f.ts
        if not f.visible():
            self.last_valid = self.last_valid; return
        m = body_metrics(f)
        if not m.valid:
            return
        self.last_valid = now
        hip_below = m.hy > m.sy + 0.02
        ankle_below = (not m.has_ankle) or (m.ankle_y > m.hy - 0.02)
        if not hip_below or not ankle_below:
            self.down = False; self.status = 'not standing'; return
        knee = avg_angle(f, LH, LK, LA, RH, RK, RA)
        if math.isnan(knee):
            return
        self.sm_knee = knee if math.isnan(self.sm_knee) else self.sm_knee + self.SMOOTH * (knee - self.sm_knee)
        self.sm_hip = m.hy if math.isnan(self.sm_hip) else self.sm_hip + self.SMOOTH * (m.hy - self.sm_hip)
        if knee >= self.STAND:
            self.stand_hip = self.sm_hip if math.isnan(self.stand_hip) else min(self.stand_hip, self.sm_hip)
        travel = 0.0 if math.isnan(self.stand_hip) else (self.sm_hip - self.stand_hip) / max(0.04, m.body_scale)
        lean = math.degrees(math.atan2(abs(m.sx - m.hx), abs(m.sy - m.hy) + 1e-5))
        if not self.down:
            if self.sm_knee < self.START:
                self.down = True; self.down_start = now; self.rep_min_knee = knee
                self.rep_max_lean = lean; self.rep_max_travel = travel
            else:
                self.status = 'stand'; return
        else:
            self.rep_min_knee = min(self.rep_min_knee, knee)
            self.rep_max_lean = max(self.rep_max_lean, lean)
            self.rep_max_travel = max(self.rep_max_travel, travel)
        if self.sm_knee >= self.STAND:
            if self.down:
                self.finish(now - self.down_start)
            self.status = 'stand'
        else:
            self.status = 'down'

    def finish(self, dur):
        mk, ml, tr = self.rep_min_knee, self.rep_max_lean, self.rep_max_travel
        self.down = False; self.rep_min_knee = float('nan'); self.rep_max_lean = 0.0; self.rep_max_travel = 0.0
        if dur < self.MIN_REP:
            return
        deep = mk <= self.DEPTH
        moved = mk <= self.PARTIAL
        hip_moved = tr >= self.MIN_TRAVEL
        lean_ok = ml <= self.MAX_LEAN
        if not hip_moved:
            return
        if (not lean_ok) or (not deep):
            if (not moved) and (not lean_ok):
                return
            self.bad += 1; self.form += 1
        else:
            self.good += 1


# ---------------- 引体向上 ----------------
class PullUp:
    HANG = 150.0; PULL = 95.0; PARTIAL = 118.0; START = 145.0
    MIN_RISE = 0.10; MIN_ABOVE = 0.15; MIN_REP = 400; SMOOTH = 0.5

    def __init__(self):
        self.sm = float('nan'); self.hang_reach = float('nan')
        self.pull = False; self.pull_start = 0; self.rep_min = float('nan'); self.rep_max_rise = 0.0
        self.good = 0; self.bad = 0; self.form = 0; self.status = ''

    def process(self, f):
        m = body_metrics(f)
        if not m.valid:
            return
        elbow = avg_angle(f, LS, LE, LW, RS, RE, RW)
        if math.isnan(elbow):
            return
        self.sm = elbow if math.isnan(self.sm) else self.sm + self.SMOOTH * (elbow - self.sm)
        decision = elbow
        if -m.wrist_drop < self.MIN_ABOVE:
            self.pull = False; self.status = 'not hanging'; return
        if decision >= self.HANG:
            self.hang_reach = (-m.wrist_drop) if math.isnan(self.hang_reach) else max(self.hang_reach, -m.wrist_drop)
        rise = 0.0 if math.isnan(self.hang_reach) else self.hang_reach - (-m.wrist_drop)
        now = f.ts
        if not self.pull:
            if decision < self.START:
                self.pull = True; self.pull_start = now; self.rep_min = elbow; self.rep_max_rise = rise
            else:
                self.status = 'hang'; return
        else:
            self.rep_min = min(self.rep_min, elbow); self.rep_max_rise = max(self.rep_max_rise, rise)
        if decision >= self.HANG:
            if self.pull:
                self.finish(now - self.pull_start)
            self.status = 'hang'
        else:
            self.status = 'pull'

    def finish(self, dur):
        mn, rise = self.rep_min, self.rep_max_rise
        self.pull = False; self.rep_min = float('nan'); self.rep_max_rise = 0.0
        if dur < self.MIN_REP:
            return
        deep = mn <= self.PULL; moved = mn <= self.PARTIAL; rose = rise >= self.MIN_RISE
        if not rose:
            return
        if not deep:
            if not moved:
                return
            self.bad += 1; self.form += 1
        else:
            self.good += 1


# ---------------- 平板支撑 ----------------
class Plank:
    MAX_TORSO_H = 30.0; SAG_LIMIT = 0.15; MIN_WRIST_DROP = 0.05; MIN_HIP_LIFT = 0.02
    ARM = 500; GRACE = 800; DROPOUT = 600

    def __init__(self):
        self.hold_start = None; self.cand = None; self.last_good = None; self.last_valid = None
        self.cur = 0; self.best = 0; self.total = 0; self.attempts = 0
        self.sag_frames = 0; self.good_frames = 0; self.sag_warn = 0; self.status = ''

    def process(self, f):
        now = f.ts
        if not f.visible():
            if self.last_valid is not None and now - self.last_valid > self.DROPOUT:
                self.interrupt(now)
            return
        m = body_metrics(f)
        if not m.valid:
            self.interrupt(now); return
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
                self.status = 'arming'
            else:
                self.cur = now - self.hold_start; self.last_good = now
                self.good_frames += 1; self.status = 'holding'
            return
        self.status = 'bad'
        self.cand = None
        if self.hold_start is not None:
            if self.last_good is not None and now - self.last_good <= self.GRACE:
                self.status += ' (grace)'
            else:
                self.finish()

    def interrupt(self, now):
        self.cand = None
        if self.hold_start is not None and (self.last_good is None or now - self.last_good > self.GRACE):
            self.finish()

    def finish(self):
        base = self.last_good if self.last_good is not None else self.hold_start
        dur = max(0, base - self.hold_start)
        if dur > 0:
            self.best = max(self.best, dur); self.total += dur; self.cur = dur
        self.hold_start = None; self.last_good = None; self.cand = None

    def current(self):
        if self.hold_start is None:
            return 0
        base = self.last_good if self.last_good is not None else self.hold_start
        return max(self.cur, base - self.hold_start)

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
        self.wall_frames = 0; self.hold_frames = 0; self.tilt_sum = 0.0

    def process(self, f):
        now = f.ts
        if not f.visible():
            if self.hold_start is not None and self.last_good is not None and now - self.last_good > self.GRACE:
                self.finish()
            return
        m = body_metrics(f)
        if not m.valid:
            return
        inverted = m.sy > m.hy + 0.02
        ankle_y = m.ankle_y if m.has_ankle else None
        if ankle_y is None:
            inverted = inverted
        else:
            inverted = inverted and ankle_y < m.sy + 0.05
        tilt = math.degrees(math.atan2(abs(m.sx - m.hx), abs(m.sy - m.hy) + 1e-5))
        ankle_x = None
        if m.has_ankle:
            ankle_x = mid_of(f, LA, RA, X)
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
            else:
                self.cur = now - self.hold_start; self.last_good = now
                self.hold_frames += 1; self.tilt_sum += tilt
        else:
            self.cand = None
            if self.hold_start is not None:
                if now - self.last_good <= self.GRACE:
                    self.cur = self.last_good - self.hold_start
                else:
                    self.finish()

    def finish(self):
        dur = max(0, self.last_good - self.hold_start)
        if dur > 0:
            self.best = max(self.best, dur); self.total += dur; self.cur = dur
        self.hold_start = None; self.last_good = None; self.cand = None

    def current(self):
        if self.hold_start is None:
            return 0
        return max(self.cur, self.last_good - self.hold_start)

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

    def process(self, f):
        now = f.ts
        if not f.visible():
            self.interrupt(now); self.status = 'no person'; return
        m = body_metrics(f)
        if not m.valid:
            self.interrupt(now); self.status = 'no body'; return
        self.last_valid = now

        knee = avg_angle(f, LH, LK, LA, RH, RK, RA)
        self.last_knee = knee
        if math.isnan(knee):
            self.status = 'legs missing'; self.interrupt(now); return

        hip_below_shoulder = m.hy > m.sy + 0.02
        ankle_below_knee = m.has_ankle and m.ankle_y > (m.hy + m.ankle_y) / 2 - 0.05
        thigh_rise = abs(m.hy - mid_of(f, LK, RK, Y)) / max(0.04, m.body_scale)
        lean = math.degrees(math.atan2(abs(m.sx - m.hx), abs(m.sy - m.hy) + 1e-5))

        sitting = (self.MIN_KNEE <= knee <= self.MAX_KNEE) and hip_below_shoulder \
            and thigh_rise <= self.MAX_THIGH_RISE and lean <= self.MAX_TORSO_LEAN

        if sitting:
            if self.hold_start is None:
                if self.cand is None:
                    self.cand = now
                if now - self.cand >= self.ARM:
                    self.hold_start = self.cand; self.attempts += 1
                self.status = 'arming'
            else:
                self.cur = now - self.hold_start; self.last_good = now
                self.good_frames += 1; self.knee_sum += knee; self.knee_sq += knee * knee
                self.status = 'holding'
            return

        # 不合格帧
        self.cand = None
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
        if self.hold_start is not None and (self.last_good is None or now - self.last_good > self.DROPOUT):
            self.finish()

    def finish(self):
        dur = max(0, (self.last_good if self.last_good is not None else self.hold_start) - self.hold_start)
        if dur > 0:
            self.best = max(self.best, dur); self.total += dur; self.cur = dur
        self.hold_start = None; self.last_good = None; self.cand = None

    def current(self):
        if self.hold_start is None:
            return 0
        return max(self.cur, (self.last_good if self.last_good is not None else self.hold_start) - self.hold_start)

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

    def process(self, f):
        now = f.ts
        if not f.visible():
            if self.last_valid is not None and now - self.last_valid > self.DROPOUT_MS:
                self.last_sign = 0
            self.status = 'no person'
            return
        m = body_metrics(f)
        if not m.valid or not m.has_ankle:
            self.status = 'no legs'
            return
        if not (f.rel(LA) and f.rel(RA)):
            self.status = 'one leg hidden'
            return
        self.last_valid = now

        hip_below_shoulder = m.hy > m.sy + 0.02
        if not hip_below_shoulder:
            self.status = 'not running posture'
            return

        scale = max(0.04, m.body_scale)
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

    def cadence(self):
        if not self.intervals:
            return 0
        avg = sum(self.intervals) / len(self.intervals)
        return 60000.0 / avg if avg > 0 else 0


# ---------------- 断言 ----------------
res = []



# ---------------- 跑步 / 靠墙蹲的合成帧（与 SyntheticPoses.running / wallSit 同构） ----------------
def running(ts, lift_left=0.0, lift_right=0.0):
    """左右脚交替抬起；lift 越大该侧脚踝越高。"""
    hip_y, shoulder_y = 0.50, 0.24
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
