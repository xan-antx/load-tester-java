package com.elevate.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.MappedSuperclass;

/**
 * What every kind of run has in common: a single job, a split group and a repeat series.
 * A {@link MappedSuperclass}: its fields become columns of each subclass's own table.
 *
 * <p>State changes go through {@link #changeStatus}, which also stamps {@code completedAt}
 * when the run reaches a terminal status, so the two can never disagree.
 */
@MappedSuperclass
public abstract class AbstractRun {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 2048)
    private String targetUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobType jobType;

    @Column(name = "user_count", nullable = false)
    private int users;

    @Column(nullable = false)
    private int spawnRate;

    @Column(nullable = false)
    private int durationSeconds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RunStatus status;

    @Lob
    private String error;

    @Convert(converter = StringListConverter.class)
    @Column(length = 2000)
    private List<String> headerNames = new ArrayList<>();

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    /** For JPA. */
    protected AbstractRun() {
    }

    /**
     * A new run in status {@code queued}.
     *
     * @param id       unique id (a UUID)
     * @param settings target, type and load settings
     * @param now      creation time
     */
    protected AbstractRun(String id, RunSettings settings, Instant now) {
        this.id = id;
        this.targetUrl = settings.targetUrl();
        this.jobType = settings.jobType();
        this.users = settings.users();
        this.spawnRate = settings.spawnRate();
        this.durationSeconds = settings.durationSeconds();
        this.headerNames = new ArrayList<>(settings.headerNames());
        this.status = RunStatus.QUEUED;
        this.createdAt = now;
    }

    /**
     * Moves the run to a new status.
     *
     * @param newStatus the status
     * @param message   error or explanation to keep, or {@code null} to keep the current one
     * @param now       the time, stamped as {@code completedAt} for a terminal status
     */
    public void changeStatus(RunStatus newStatus, String message, Instant now) {
        this.status = newStatus;
        if (message != null) {
            this.error = message;
        }
        if (newStatus.isTerminal() && completedAt == null) {
            this.completedAt = now;
        }
    }

    public String getId() {
        return id;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public JobType getJobType() {
        return jobType;
    }

    public int getUsers() {
        return users;
    }

    public int getSpawnRate() {
        return spawnRate;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public RunStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public List<String> getHeaderNames() {
        return List.copyOf(headerNames);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
