package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;

/** Fast math replacements: sin/cos via lookup, perlin cache, etc. */
public final class FastMathUtil {
    private static final int SIN_BITS = 12;
    private static final int SIN_MASK = ~(-1 << SIN_BITS);
    private static final int SIN_COUNT = SIN_MASK + 1;
    private static final float[] SIN_TABLE = new float[SIN_COUNT];
    static {
        for (int i=0;i<SIN_COUNT;i++) SIN_TABLE[i] = (float)Math.sin((i + 0.5f) / SIN_COUNT * Math.PI * 2);
    }
    private FastMathUtil(){}
    public static float fastSin(float rad) {
        if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().fastMath) return (float)Math.sin(rad);
        return SIN_TABLE[(int)(rad * 651.8986f) & SIN_MASK];
    }
    public static float fastCos(float rad) {
        if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().fastMath) return (float)Math.cos(rad);
        return SIN_TABLE[(int)(rad * 651.8986f + SIN_COUNT * 0.25f) & SIN_MASK];
    }
    public static double fastSqrt(double v) {
        if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().fastMath) return Math.sqrt(v);
        return Math.sqrt(v);
    }
}
