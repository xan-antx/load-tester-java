package com.elevate.engine;

import java.time.Duration;
import java.util.List;

/**
 * How many virtual users should be running at a given moment. A sealed interface: the
 * engine knows every kind of profile there is.
 */
public sealed interface LoadProfile permits LoadProfile.Fixed, LoadProfile.Steps {

    /** Users that should be active {@code elapsed} after the start. */
    int targetUsers(Duration elapsed);

    /** When the run ends. */
    Duration totalDuration();

    /** Users started per second while ramping up. */
    int spawnRate();

    /**
     * One load level: ramp up to {@code users} at {@code spawnRate}, then hold. As in Locust,
     * {@code duration} is the whole run, ramp-up included.
     *
     * @param users     users to reach
     * @param spawnRate users started per second
     * @param duration  length of the run
     */
    record Fixed(int users, int spawnRate, Duration duration) implements LoadProfile {
        @Override
        public int targetUsers(Duration elapsed) {
            return users;
        }

        @Override
        public Duration totalDuration() {
            return duration;
        }
    }

    /**
     * Step load for a capacity run: {@code users.get(i)} during step {@code i}.
     *
     * @param users        users per step
     * @param stepDuration length of each step
     * @param spawnRate    users started per second when stepping up
     */
    record Steps(List<Integer> users, Duration stepDuration, int spawnRate) implements LoadProfile {

        /** Defensive copy. */
        public Steps {
            users = List.copyOf(users);
        }

        /** The step running {@code elapsed} after the start (the last step once the plan is over). */
        public int stepAt(Duration elapsed) {
            int index = (int) (elapsed.toMillis() / stepDuration.toMillis());
            return Math.min(index, users.size() - 1);
        }

        @Override
        public int targetUsers(Duration elapsed) {
            return users.get(stepAt(elapsed));
        }

        @Override
        public Duration totalDuration() {
            return stepDuration.multipliedBy(users.size());
        }
    }
}
