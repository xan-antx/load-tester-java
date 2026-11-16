package com.elevate.repository;

import java.time.Instant;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.elevate.model.RepeatSeries;
import com.elevate.model.RunStatus;

/** Repeat series. */
public interface RepeatSeriesRepository extends JpaRepository<RepeatSeries, String> {

    /** Marks every series in one of {@code active} as interrupted (used once at startup). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RepeatSeries s set s.status = com.elevate.model.RunStatus.INTERRUPTED, s.error = :reason,
                   s.completedAt = :now where s.status in :active""")
    int markInterrupted(@Param("active") Collection<RunStatus> active, @Param("reason") String reason,
                        @Param("now") Instant now);
}
