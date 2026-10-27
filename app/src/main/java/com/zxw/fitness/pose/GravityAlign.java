package com.zxw.fitness.pose;

import com.zxw.fitness.model.PoseFrame;

/**
 * 重力对齐（机位横滚补偿）。
 *
 * <p>为什么需要：判定器里大量用到"水平"和"竖直"——平板支撑要求躯干接近水平、
 * 倒立/靠墙蹲要求躯干接近竖直、深蹲用"躯干前倾角"。手机只要歪一点，这些角度就全被
 * 横滚吃掉。实测手机歪 30° 时，平板支撑、倒立、靠墙蹲的计时直接归零。</p>
 *
 * <p>做法：用加速度计得到重力方向，把它换算成"画面需要转多少度才能让重力朝下"，
 * 再把关键点绕画面中心反向旋转同样的角度。之后所有判定都在"重力对齐坐标系"里进行，
 * 与人怎么拿手机无关。</p>
 *
 * <h3>角度约定</h3>
 * <ul>
 *     <li>画面坐标：x 向右、y 向下（与 {@link PoseFrame} 一致）；</li>
 *     <li>角度为正表示"屏幕上顺时针"，旋转公式与数学上的逆时针相反（因为 y 向下）；</li>
 *     <li>{@link #rollDegrees(float, float)} 返回的正是"需要施加给关键点的旋转角"，
 *     手机竖直拿正时为 0，手机顺时针歪 30° 时约为 −30。</li>
 * </ul>
 *
 * <h3>坐标系推导（便于真机排查符号）</h3>
 * <pre>
 * 加速度计在静止时读到的是"上方向 × 9.81"（设备坐标：x 右、y 上、z 朝屏幕外）。
 * 后置摄像头画面：画面 x = 设备 x，画面 y = 设备 −y。
 * 于是"上方向"在画面里是 (ax, −ay)，它的方位角 α = atan2(−ay, ax)；
 * 手机拿正时 α = −90°，因此需要旋转 ψ = −90° − α 才能把"上"转回画面正上方。
 * </pre>
 *
 * <p>若真机上发现补偿方向相反（歪一点反而更歪），只需把 {@link #ROLL_SIGN} 改成 −1。</p>
 */
public final class GravityAlign {

    /** 补偿方向系数：真机上若方向相反，把它改成 -1。 */
    public static final float ROLL_SIGN = 1f;

    /** 小于该角度不做旋转（避免正常手持时引入无谓的计算与抖动）。 */
    public static final float DEAD_ZONE_DEG = 0.8f;

    /** 可补偿的最大横滚角；超过它认为手机接近横放，不做补偿（也提示用户竖着拿）。 */
    public static final float MAX_ROLL_DEG = 60f;

    /** 重力在 x/y 平面上的投影小于它时（手机接近平放），横滚不可信。 */
    public static final float MIN_GRAVITY_XY = 3.0f;

    private GravityAlign() {
    }

    /**
     * 由加速度计读数算出需要施加给关键点的旋转角（度）。
     *
     * @param ax 加速度计 x（m/s²，向右为正）
     * @param ay 加速度计 y（m/s²，向手机顶部为正）
     * @return 旋转角，范围 (−180, 180]；传感器不可靠时返回 0
     */
    public static float rollDegrees(float ax, float ay) {
        if (!isReliable(ax, ay)) {
            return 0f;
        }
        double alpha = Math.toDegrees(Math.atan2(-ay, ax));   // "上方向"在画面里的方位角
        double psi = (-90.0 - alpha) * ROLL_SIGN;
        psi = normalize(psi);
        if (Math.abs(psi) < DEAD_ZONE_DEG) {
            return 0f;
        }
        if (Math.abs(psi) > MAX_ROLL_DEG) {
            return 0f;
        }
        return (float) psi;
    }

    /** 重力在画面平面上的投影是否足够大（手机竖着/斜着拿时为真）。 */
    public static boolean isReliable(float ax, float ay) {
        return Math.hypot(ax, ay) >= MIN_GRAVITY_XY;
    }

    /** 把角度归一化到 (−180, 180]。 */
    public static double normalize(double degrees) {
        double value = degrees % 360.0;
        if (value > 180.0) {
            value -= 360.0;
        } else if (value <= -180.0) {
            value += 360.0;
        }
        return value;
    }

    /** 一帧关键点绕画面中心旋转 {@code degrees}（正值 = 屏幕上顺时针）。 */
    public static PoseFrame rotate(PoseFrame frame, float degrees) {
        if (frame == null) {
            return null;
        }
        return rotate(frame, degrees, 0.5f, 0.5f);
    }

    /** 绕指定中心旋转（中心用归一化坐标，便于单测）。 */
    public static PoseFrame rotate(PoseFrame frame, float degrees, float centerX, float centerY) {
        if (frame == null) {
            return null;
        }
        if (Math.abs(degrees) < 0.01f) {
            return frame;
        }
        double rad = Math.toRadians(degrees);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] confidence = new float[PoseFrame.LANDMARK_COUNT];
        for (int i = 0; i < PoseFrame.LANDMARK_COUNT; i++) {
            float dx = frame.getX(i) - centerX;
            float dy = frame.getY(i) - centerY;
            x[i] = centerX + dx * cos - dy * sin;
            y[i] = centerY + dx * sin + dy * cos;
            z[i] = frame.getZ(i);
            confidence[i] = frame.getConfidence(i);
        }
        return PoseFrame.of(frame.getTimestampMs(), x, y, z, confidence);
    }

    /** 按横滚角做重力对齐；角度在死区内时原样返回。 */
    public static PoseFrame align(PoseFrame frame, float rollDeg) {
        if (frame == null || Math.abs(rollDeg) < DEAD_ZONE_DEG) {
            return frame;
        }
        return rotate(frame, rollDeg);
    }
}
