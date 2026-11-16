package com.elevate.engine;

/** Why a load engine stopped. */
public enum StopReason {
    /** The planned duration elapsed. */
    COMPLETED,
    /** {@link LoadEngine#stop()} was called (the user pressed Stop). */
    CANCELLED,
    /** A capacity run's step missed the SLO and early stop was on. */
    EARLY_STOP
}
