package com.elevate;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.elevate.cli.LoadTestCli;
import com.elevate.queue.WorkerMode;

/**
 * Entry point of the jar:
 * <ul>
 *   <li>{@code java -jar load-tester.jar} starts the REST API on port 8080;</li>
 *   <li>{@code java -jar load-tester.jar run|gate|check-sitemap ...} runs the command-line client
 *       (no Spring context, no web server) and exits with its code;</li>
 *   <li>{@code java -jar load-tester.jar worker} runs the queue-mode worker (no web server).</li>
 * </ul>
 */
@SpringBootApplication
public class LoadTesterApplication {

    /**
     * @param args a command name for the CLI or the worker, anything else for the server
     */
    public static void main(String[] args) {
        if (args.length > 0 && LoadTestCli.handles(args[0])) {
            System.exit(LoadTestCli.execute(args));
        }
        if (args.length > 0 && WorkerMode.COMMAND.equals(args[0])) {
            WorkerMode.start(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SpringApplication.run(LoadTesterApplication.class, args);
    }
}
