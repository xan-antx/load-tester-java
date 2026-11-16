package com.elevate.service;

import java.util.ArrayList;
import java.util.List;

/**
 * How a large run is split into engine instances: one per 50 users (rounded up), at most
 * 10, with the users shared out as evenly as possible.
 */
public final class SplitPlan {

    /** Runs above this many users are split. */
    public static final int SPLIT_THRESHOLD = 50;
    /** Most engine instances per run. */
    public static final int MAX_CHILDREN = 10;

    private SplitPlan() {
    }

    /** Whether a run with this many users is split. */
    public static boolean isSplit(int users) {
        return users > SPLIT_THRESHOLD;
    }

    /**
     * @param totalUsers users of the whole run
     * @return users per engine instance, e.g. 120 → [40, 40, 40]
     */
    public static List<Integer> split(int totalUsers) {
        int children = Math.min(MAX_CHILDREN, (totalUsers + SPLIT_THRESHOLD - 1) / SPLIT_THRESHOLD);
        int base = totalUsers / children;
        int remainder = totalUsers % children;
        List<Integer> shares = new ArrayList<>();
        for (int i = 0; i < children; i++) {
            shares.add(base + (i < remainder ? 1 : 0));
        }
        return shares;
    }
}
