package com.elevate.service;

import java.util.List;

/**
 * A run that has been stored and can be executed: a single job, or a split group with its
 * children.
 *
 * @param id       job or group id
 * @param childIds the group's child job ids (empty for a single job)
 */
public record PreparedRun(String id, List<String> childIds) {

    /** Defensive copy. */
    public PreparedRun {
        childIds = List.copyOf(childIds);
    }

    /** Whether this is a split group. */
    public boolean isGroup() {
        return !childIds.isEmpty();
    }
}
