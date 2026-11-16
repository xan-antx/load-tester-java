package com.elevate.engine;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import com.elevate.analysis.capacity.StepRecord;
import com.elevate.analysis.headers.RequestHeaders;

/**
 * Everything one engine instance needs to run. Built with {@link #builder()}: most settings
 * have sensible defaults, and the builder checks the required ones.
 *
 * @param templates      the requests virtual users cycle through
 * @param profile        how many users when
 * @param headers        extra request headers (values are kept in memory only)
 * @param thinkTime      pause between requests
 * @param requestTimeout how long one request may take before it counts as "no response"
 * @param stepJudge      decides after each capacity step whether to stop early
 * @param clock          wall clock for timeline timestamps
 */
public record EngineSpec(List<RequestTemplate> templates, LoadProfile profile, RequestHeaders headers,
                         ThinkTime thinkTime, Duration requestTimeout, StepJudge stepJudge, Clock clock) {

    /** Decides whether a finished capacity step should end the run. */
    @FunctionalInterface
    public interface StepJudge {
        /** A judge that never stops early. */
        StepJudge NEVER = step -> false;

        /**
         * @param step the step that just finished
         * @return {@code true} to stop the run now
         */
        boolean shouldStop(StepRecord step);
    }

    /** Validates and copies. */
    public EngineSpec {
        templates = List.copyOf(templates);
        if (templates.isEmpty()) {
            throw new IllegalArgumentException("at least one request template is needed");
        }
        Objects.requireNonNull(profile, "profile");
    }

    /** A new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link EngineSpec} (the Builder pattern: many optional settings, one immutable result). */
    public static final class Builder {
        private List<RequestTemplate> templates = List.of();
        private LoadProfile profile;
        private RequestHeaders headers = RequestHeaders.NONE;
        private ThinkTime thinkTime = ThinkTime.LOCUST_DEFAULT;
        private Duration requestTimeout = Duration.ofSeconds(30);
        private StepJudge stepJudge = StepJudge.NEVER;
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        /** The requests to send (required). */
        public Builder templates(List<RequestTemplate> value) {
            this.templates = value;
            return this;
        }

        /** The load profile (required). */
        public Builder profile(LoadProfile value) {
            this.profile = value;
            return this;
        }

        /** Extra request headers (default none). */
        public Builder headers(RequestHeaders value) {
            this.headers = value;
            return this;
        }

        /** Pause between requests (default 0.5–2 s). */
        public Builder thinkTime(ThinkTime value) {
            this.thinkTime = value;
            return this;
        }

        /** Per-request timeout (default 30 s). */
        public Builder requestTimeout(Duration value) {
            this.requestTimeout = value;
            return this;
        }

        /** Early-stop rule for capacity runs (default never). */
        public Builder stepJudge(StepJudge value) {
            this.stepJudge = value;
            return this;
        }

        /** Wall clock (default UTC system clock). */
        public Builder clock(Clock value) {
            this.clock = value;
            return this;
        }

        /** The validated specification. */
        public EngineSpec build() {
            return new EngineSpec(templates, profile, headers, thinkTime, requestTimeout, stepJudge, clock);
        }
    }
}
