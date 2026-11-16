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
import com.elevate.model.SplitGroup;

/** Split groups (runs above 50 users). */
public interface SplitGroupRepository extends JpaRepository<SplitGroup, String> {

    /** The 50 newest groups, newest first. */
    List<SplitGroup> findTop50ByOrderByCreatedAtDesc();

    /** The baseline group of a target, if one is set. */
    Optional<SplitGroup> findFirstByTargetUrlAndBaselineTrue(String targetUrl);

    /** Clears the baseline flag of every group of a target. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SplitGroup g set g.baseline = false where g.targetUrl = :targetUrl")
    int clearBaseline(@Param("targetUrl") String targetUrl);

    /** Marks every group in one of {@code active} as interrupted (used once at startup). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SplitGroup g set g.status = com.elevate.model.RunStatus.INTERRUPTED, g.error = :reason,
                   g.completedAt = :now where g.status in :active""")
    int markInterrupted(@Param("active") Collection<RunStatus> active, @Param("reason") String reason,
                        @Param("now") Instant now);
}
