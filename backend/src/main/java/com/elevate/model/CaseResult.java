package com.elevate.model;

import java.util.ArrayList;
import java.util.List;

import com.elevate.analysis.stats.CaseMeasurement;
import com.elevate.analysis.stats.FailureCount;
import com.elevate.analysis.stats.Histograms;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

/**
 * The stored measurement of one case in one run: counts, sums, extremes, the compressed
 * HdrHistogram of every response time, and the failure tallies.
 */
@Entity
@Table(name = "case_result")
public class CaseResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 512)
    private String caseName;

    @Column(nullable = false, length = 8)
    private String method;

    private long requests;
    private long failures;
    private long sumMicros;
    private long minMicros;
    private long maxMicros;
    private long contentBytes;

    @Lob
    private byte[] histogram;

    @ElementCollection
    @CollectionTable(name = "case_failure", joinColumns = @JoinColumn(name = "case_result_id"))
    @OrderColumn(name = "position")
    private List<FailureTally> failureTallies = new ArrayList<>();

    /** For JPA. */
    protected CaseResult() {
    }

    /**
     * @param m        the measurement
     * @param failures its failure counts
     * @return the stored form
     */
    public static CaseResult of(CaseMeasurement m, List<FailureCount> failures) {
        CaseResult r = new CaseResult();
        r.caseName = m.name();
        r.method = m.method();
        r.requests = m.requests();
        r.failures = m.failures();
        r.sumMicros = m.sumMicros();
        r.minMicros = m.minMicros();
        r.maxMicros = m.maxMicros();
        r.contentBytes = m.contentBytes();
        r.histogram = Histograms.encode(m.histogram());
        failures.forEach(f -> r.failureTallies.add(FailureTally.from(f)));
        return r;
    }

    /** Back to the analysis form (decodes the histogram). */
    public CaseMeasurement toMeasurement() {
        return new CaseMeasurement(caseName, method, requests, failures, sumMicros, minMicros, maxMicros, contentBytes,
                Histograms.decode(histogram));
    }

    /** The failure counts of this case. */
    public List<FailureCount> toFailureCounts() {
        return failureTallies.stream().map(t -> t.toFailureCount(caseName, method)).toList();
    }
}
