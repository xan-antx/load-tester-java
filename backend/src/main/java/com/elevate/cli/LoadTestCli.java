package com.elevate.cli;

import java.util.Arrays;

import picocli.CommandLine;

/**
 * Command-line mode of the jar: {@code java -jar load-tester.jar run --config loadtest.yml}.
 * Runs a test through the REST API and exits 0 (SLO pass), 1 (SLO fail) or 2 (error).
 */
public final class LoadTestCli {

    /** The first argument that selects CLI mode. */
    public static final String COMMAND = "run";

    private LoadTestCli() {
    }

    /**
     * @param args the full command line, starting with {@code run}
     * @return the exit code
     */
    public static int execute(String[] args) {
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        CommandLine command = new CommandLine(new RunCommand());
        command.setExecutionExceptionHandler((e, cmd, parsed) -> {
            cmd.getErr().println("ERROR: " + e.getMessage());
            return CliRunner.EXIT_ERROR;
        });
        return command.execute(rest);
    }
}
