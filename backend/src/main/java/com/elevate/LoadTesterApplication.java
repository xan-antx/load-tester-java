package com.elevate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.elevate.cli.LoadTestCli;

/**
 * Entry point of the jar. {@code java -jar load-tester.jar} starts the REST API on port 8080;
 * {@code java -jar load-tester.jar run --config loadtest.yml} runs the command-line client
 * instead (no Spring context, no web server) and exits with its verdict code.
 */
@SpringBootApplication
public class LoadTesterApplication {

    /**
     * @param args {@code run ...} for the CLI, anything else for the server
     */
    public static void main(String[] args) {
        if (args.length > 0 && LoadTestCli.COMMAND.equals(args[0])) {
            System.exit(LoadTestCli.execute(args));
        }
        SpringApplication.run(LoadTesterApplication.class, args);
    }
}
