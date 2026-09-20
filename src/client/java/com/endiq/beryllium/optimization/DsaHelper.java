package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;

/** DSA buffers and RBO depth handling. */
public final class DsaHelper {
    private DsaHelper(){}
    public static boolean useDsa() {
        return Beryllium.config() != null && Beryllium.config().enabled && Beryllium.config().dsaBuffers;
    }
    public static boolean useRboDepth() {
        return Beryllium.config() != null && Beryllium.config().enabled && Beryllium.config().rboDepth;
    }
}
