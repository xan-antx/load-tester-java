package com.elevate.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle of a run: {@code queued → running → completed | failed | cancelled}, or
 * {@code interrupted} when the backend stopped while the run was in progress.
 */
public enum RunStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED;

    /** The lowercase name used in JSON, e.g. {@code "completed"}. */
    @JsonValue
    public String wire() {
        return name().toLowerCase();
    }

    /** A run in a terminal status never changes again. */
    public boolean isTerminal() {
        return this != QUEUED && this != RUNNING;
    }
}
