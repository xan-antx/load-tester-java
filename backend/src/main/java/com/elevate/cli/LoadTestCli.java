package com.elevate.cli;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Supplier;

import picocli.CommandLine;

/**
 * Command-line modes of the jar:
 * <ul>
 *   <li>{@code run --config loadtest.yml} — one load test with an SLO verdict (exit 0 pass, 1 fail, 2 error);</li>
 *   <li>{@code gate --api ... --url ... --sample ...} — regression gate against a baseline (0, 1, 2);</li>
 *   <li>{@code check-sitemap --url ...} — show the sitemap and paths a website run would use.</li>
 * </ul>
 */
public final class LoadTestCli {

    /** The commands, by their first argument. */
    private static final Map<String, Supplier<Object>> COMMANDS = Map.of(
            "run", RunCommand::new,
            "gate", GateCommand::new,
            "check-sitemap", SitemapCheckCommand::new);

    private LoadTestCli() {
    }

    /** Whether the first argument selects a command-line mode. */
    public static boolean handles(String firstArgument) {
        return COMMANDS.containsKey(firstArgument);
    }

    /**
     * @param args the full command line, starting with the command name
     * @return the exit code
     */
    public static int execute(String[] args) {
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        CommandLine command = new CommandLine(COMMANDS.get(args[0]).get());
        command.setExecutionExceptionHandler((e, cmd, parsed) -> {
            cmd.getErr().println("ERROR: " + e.getMessage());
            return CliRunner.EXIT_ERROR;
        });
        return command.execute(rest);
    }
}
