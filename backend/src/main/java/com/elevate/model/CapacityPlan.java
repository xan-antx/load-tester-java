package com.elevate.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * The step plan of a capacity run: {@code startUsers} for {@code stepDurationSeconds}, then
 * {@code stepUsers} more each step, up to and including {@code maxUsers}.
 *
 * <p>A record used directly as a JPA embeddable (supported since Hibernate 6.2): its
 * columns live in the run's table.
 *
 * @param startUsers          users in the first step
 * @param stepUsers           users added per step
 * @param stepDurationSeconds length of each step
 * @param maxUsers            highest load
 * @param earlyStop           stop after the first step that misses the SLO
 */
@Embeddable
public record CapacityPlan(
        @Column(name = "cap_start_users") Integer startUsers,
        @Column(name = "cap_step_users") Integer stepUsers,
        @Column(name = "cap_step_seconds") Integer stepDurationSeconds,
        @Column(name = "cap_max_users") Integer maxUsers,
        @Column(name = "cap_early_stop") Boolean earlyStop) {

    /** Users per step: {@code [start, start + step, ...]} up to {@code maxUsers}. */
    @JsonProperty("steps")
    public List<Integer> steps() {
        List<Integer> steps = new ArrayList<>();
        for (int users = startUsers; users <= maxUsers; users += stepUsers) {
            steps.add(users);
        }
        return steps;
    }

    /** Planned length of the whole run. */
    @JsonProperty("total_seconds")
    public int totalSeconds() {
        return steps().size() * stepDurationSeconds;
    }
}
