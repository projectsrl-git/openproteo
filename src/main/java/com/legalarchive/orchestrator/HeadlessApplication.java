package com.legalarchive.orchestrator;

import java.util.Arrays;

import com.legalarchive.orchestrator.cli.OpCli;

/**
 * Entry point of {@code openproteo-headless.war} (spec {@code .claude/HEADLESS.md} section 4.2).
 * The artifact is built without the embedded Tomcat, so it can never publish a page.
 *
 * <p>Batch H1 ships the command-line client only: {@code java -jar openproteo-headless.war cli ...}.
 * The headless instance itself - the engine and the cron with no HTTP, behind the control
 * directory - comes with batch H2; until then any other first argument is refused.
 *
 * <p>Not a Spring class: no annotation, so the component scan of the GUI artifacts ignores it, and
 * {@link OrchestratorApplication} and {@link ServletInitializer} are untouched.
 */
public final class HeadlessApplication {

    private HeadlessApplication() { }

    public static void main(String[] args) {
        if (args.length > 0 && "cli".equals(args[0])) {
            System.exit(OpCli.run(Arrays.copyOfRange(args, 1, args.length), System.out, System.err));
        }
        System.err.println("openproteo-headless: this build runs only the command-line client:");
        System.err.println("  java -jar openproteo-headless.war cli <command> ...   (cli --help for the commands)");
        System.err.println("The headless instance comes with batch H2 (.claude/HEADLESS.md).");
        System.exit(2);
    }
}
