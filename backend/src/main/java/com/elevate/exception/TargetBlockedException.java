package com.elevate.exception;

import java.util.Map;

/** 400 with {@code "blocked_target": true}: the target guard refused the URL. */
public class TargetBlockedException extends BadRequestException {

    /** @param reason why the guard refused, e.g. {@code '10.0.0.5' resolves to ...} */
    public TargetBlockedException(String reason) {
        super("Target not allowed: " + reason, Map.of("blocked_target", true));
    }
}
