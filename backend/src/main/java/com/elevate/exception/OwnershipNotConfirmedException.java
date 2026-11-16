package com.elevate.exception;

import java.util.Map;

/** 400: a start request without {@code "i_own_target": true}. */
public class OwnershipNotConfirmedException extends BadRequestException {

    /** Creates the exception with the standard message. */
    public OwnershipNotConfirmedException() {
        super("Confirm that you own this target or have permission to load-test it "
                + "(send \"i_own_target\": true).", Map.of("missing_confirmation", "i_own_target"));
    }
}
