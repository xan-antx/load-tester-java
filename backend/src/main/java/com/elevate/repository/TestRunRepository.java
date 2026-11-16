package com.elevate.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.elevate.model.RunStatus;
import com.elevate.model.TestRun;

/** Single runs (stand-alone runs, repeats of a series and children of split groups). */
public interface TestRunRepository extends JpaRepository<TestRun, String> {

    /** The 50 newest runs that are not part of a split group, newest first (the run history). */
    List<TestRun> findTop50ByGroupIsNullOrderByCreatedAtDesc();

    /** The baseline run of a target, if one is set. */
    Optional<TestRun> findFirstByTargetUrlAndBaselineTrue(String targetUrl);

    /** Clears the baseline flag of every run of a target. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TestRun r set r.baseline = false where r.targetUrl = :targetUrl")
    int clearBaseline(@Param("targetUrl") String targetUrl);

    /** Marks every run in one of {@code active} as interrupted (used once at startup). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TestRun r set r.status = com.elevate.model.RunStatus.INTERRUPTED, r.error = :reason,
                   r.completedAt = :now where r.status in :active""")
    int markInterrupted(@Param("active") Collection<RunStatus> active, @Param("reason") String reason,
                        @Param("now") Instant now);
}
