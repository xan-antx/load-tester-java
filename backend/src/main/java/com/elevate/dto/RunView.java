package com.elevate.dto;

import com.elevate.model.RunStatus;

/**
 * Body of {@code /api/load-test-status/{id}}: one of three shapes, depending on what the
 * id names. A sealed interface, so code that handles a view must handle all three.
 */
public sealed interface RunView permits JobView, GroupView, SeriesView {

    /** The run's id. */
    String id();

    /** The run's status. */
    RunStatus status();
}
