package com.elevate.model;

import com.elevate.analysis.stats.Histograms;
import com.elevate.analysis.stats.TimelineSample;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Lob;

/**
 * One stored second of a run's timeline (an embeddable record in the {@code timeline_point}
 * collection table). The histogram is kept so split runs can merge seconds exactly.
 *
 * @param epochSecond Unix second
 * @param users       active users
 * @param requests    requests in that second
 * @param failures    failures in that second
 * @param sumMicros   sum of their response times
 * @param histogram   compressed HdrHistogram of their response times
 */
@Embeddable
public record TimelinePoint(long epochSecond, int users, long requests, long failures, long sumMicros,
                            @Lob byte[] histogram) {

    /** The stored form of a sample. */
    public static TimelinePoint from(TimelineSample s) {
        return new TimelinePoint(s.epochSecond(), s.users(), s.requests(), s.failures(), s.sumMicros(),
                Histograms.encode(s.histogram()));
    }

    /** Back to the analysis form. */
    public TimelineSample toSample() {
        return new TimelineSample(epochSecond, users, requests, failures, sumMicros, Histograms.decode(histogram));
    }
}
