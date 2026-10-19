package com.lifecontrol.api.config.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Pins what the worker's switch says at start-up (records S3-D and S5.3).
 *
 * <p>The component under test lives <b>outside</b> the scheduler's {@code @ConditionalOnProperty} on
 * purpose — with the worker off, {@link ProvisioningWorkerSchedulerConfig} is not loaded at all, so a
 * warning written inside it could never run. These cases therefore drive the component directly and
 * cover both states, and the disabled message is asserted on its <b>content</b>: naming the
 * consequence is the whole point, because "worker disabled" alone is the silent default that let the
 * incident of record S5.1 sit unnoticed.</p>
 */
@DisplayName("ProvisioningWorkerStateLogger start-up emission")
class ProvisioningWorkerStateLoggerTest {

    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(ProvisioningWorkerStateLogger.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("a disabled worker logs one WARN naming the consequence, the switch and the fix")
    void disabledLogsOneWarnWithTheConsequence() {
        new ProvisioningWorkerStateLogger(false).logState();

        assertThat(appender.list).hasSize(1);
        var event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .contains("app.provisioning.worker.enabled")
                .contains("never applied")
                .contains("no account is created")
                .contains("no activation email is sent")
                .contains("WORKER_ENABLED=true");
    }

    @Test
    @DisplayName("an enabled worker logs one INFO and no WARN at all")
    void enabledLogsNoWarn() {
        new ProvisioningWorkerStateLogger(true).logState();

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(appender.list.stream().map(ILoggingEvent::getLevel).toList()).doesNotContain(Level.WARN);
    }

    @Test
    @DisplayName("registers with the switch off: no scheduler condition to pass, and no properties bean needed")
    void registeredWithTheSwitchOffAndWithoutThePropertiesBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProvisioningWorkerStateLogger.class)
                .run(context -> {
                    // No `app.provisioning.worker.enabled` is declared, so the switch is off by its own
                    // default. The gated scheduler configuration is absent from this context, which is
                    // the whole point: the logger is still a bean, and nothing here registers the
                    // properties record it must not depend on.
                    assertThat(context).hasSingleBean(ProvisioningWorkerStateLogger.class);
                    assertThat(context.getBeansOfType(ProvisioningWorkerProperties.class))
                            .isEmpty();
                });
    }

    @Test
    @DisplayName("registers with the switch explicitly false as well")
    void registeredWithTheSwitchExplicitlyFalse() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProvisioningWorkerStateLogger.class)
                .withPropertyValues("app.provisioning.worker.enabled=false")
                .run(context -> assertThat(context).hasSingleBean(ProvisioningWorkerStateLogger.class));
    }
}
