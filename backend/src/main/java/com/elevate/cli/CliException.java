package com.elevate.cli;

/** An error that ends the CLI with exit code 2 (bad config, backend unreachable, run did not complete). */
public class CliException extends RuntimeException {

    /** @param message what went wrong, for the user */
    public CliException(String message) {
        super(message);
    }
}
