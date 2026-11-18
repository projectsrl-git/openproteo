package com.legalarchive.orchestrator;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.ConfigurableEnvironment;

import com.legalarchive.headless.HeadlessConfig;
import com.legalarchive.orchestrator.cli.OpCli;
import com.legalarchive.orchestrator.control.ControlChannel;
import com.legalarchive.orchestrator.control.ControlService;
import com.legalarchive.orchestrator.control.EngineLock;

/**
 * Entry point of {@code openproteo-headless.war} (spec {@code .claude/HEADLESS.md} sections 4.2-4.3).
 * The artifact is built without the embedded Tomcat and without the servlet API, so it can never
 * publish a page.
 *
 * <ul>
 *   <li>{@code java -jar openproteo-headless.war cli <command> ...}: the command-line client, no Spring.</li>
 *   <li>{@code java -jar openproteo-headless.war [--spring options]}: the headless instance - the
 *       engine, the cron and the control channel, with no HTTP listener.</li>
 * </ul>
 *
 * <p>Before any bean exists, the instance takes the engine lock in the workflows directory and checks
 * the control directory. Either failure stops the start: a second engine on the same data would
 * break the audit chains, and an instance without its channel could not be reached by anyone.
 *
 * <p>Not a Spring class: no annotation, so the component scan of the GUI artifacts ignores it, and
 * {@link OrchestratorApplication} and {@link ServletInitializer} are untouched.
 */
public final class HeadlessApplication {

    private HeadlessApplication() { }

    /** Exit code of a headless start that was refused. */
    public static final int START_REFUSED = 1;

    public static void main(String[] args) {
        if (args.length > 0 && "cli".equals(args[0])) {
            System.exit(OpCli.run(Arrays.copyOfRange(args, 1, args.length), System.out, System.err));
        }
        System.setProperty(ControlService.HEADLESS_PROPERTY, "true");
        SpringApplication app = new SpringApplication(HeadlessConfig.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addListeners(new Preflight());
        ConfigurableApplicationContext ctx;
        try {
            ctx = app.run(args);
        } catch (Throwable t) {
            System.err.println("openproteo-headless: not started: " + root(t));
            System.exit(START_REFUSED);
            return;
        }
        ControlService control = ctx.getBean(ControlService.class);
        if (!control.serving()) {
            // backstop: the preflight already checked the directory; this covers a channel that failed while opening
            String why = control.reason();
            LoggerFactory.getLogger(HeadlessApplication.class).error("headless: no control channel ({}): stopping", why);
            System.err.println("openproteo-headless: not started: no control channel: " + why);
            ctx.close();
            System.exit(START_REFUSED);
        }
        final CountDownLatch closed = new CountDownLatch(1);
        ctx.addApplicationListener(new ApplicationListener<ContextClosedEvent>() {
            @Override public void onApplicationEvent(ContextClosedEvent e) { closed.countDown(); }
        });
        LoggerFactory.getLogger(HeadlessApplication.class).info("headless: running with no HTTP listener; control through {}",
                ctx.getEnvironment().getProperty("orchestrator.control-dir"));
        try {
            closed.await();   // the JVM lives until the context is closed (shutdown hook on SIGTERM / Ctrl-C / service stop)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs once the configuration is known and before any bean is created: takes the engine lock and
     * checks the control directory. Throwing here aborts {@code SpringApplication.run}.
     */
    static final class Preflight implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {
        private static final Logger log = LoggerFactory.getLogger(HeadlessApplication.class);

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            ConfigurableEnvironment env = event.getEnvironment();
            Path lock = EngineLock.acquire(env.getProperty("orchestrator.workflows-dir"), "openproteo-headless.war");
            log.info("headless: engine lock held: {}", lock);
            String dir = env.getProperty("orchestrator.control-dir", "").trim();
            if (dir.isEmpty()) {
                throw new IllegalStateException("a headless instance needs a control directory: set it on the Platform page "
                        + "of the GUI (it is saved in openproteo-instance.json in the workflows directory), or pass "
                        + "--orchestrator.control-dir=<absolute path>");
            }
            String why = ControlChannel.preflight(Paths.get(dir));
            if (why != null) throw new IllegalStateException(why);
        }
    }

    private static String root(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String m = r.getMessage();
        return m == null ? r.toString() : m;
    }
}
