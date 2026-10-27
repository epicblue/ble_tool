package com.zxw.fitness.analysis;

/** 连续三帧中最保守的极值，防止单帧关节角/关键点跳变污染整组个人起始位。 */
final class StableExtremum {
    private static final int WINDOW = 3;
    private final boolean maximum;
    private final float[] values = new float[WINDOW];
    private int count;
    private int next;
    private float best = Float.NaN;

    StableExtremum(boolean maximum) { this.maximum = maximum; }

    float observe(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            clearWindow();
            return best;
        }
        values[next] = value;
        next = (next + 1) % WINDOW;
        count = Math.min(WINDOW, count + 1);
        if (count == WINDOW) {
            float stable = values[0];
            for (int i = 1; i < WINDOW; i++) {
                stable = maximum ? Math.min(stable, values[i]) : Math.max(stable, values[i]);
            }
            best = Float.isNaN(best) ? stable : maximum ? Math.max(best, stable) : Math.min(best, stable);
        }
        return best;
    }

    void clearWindow() { count = 0; next = 0; }

    void reset() { clearWindow(); best = Float.NaN; }
}
