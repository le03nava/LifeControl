package com.lifecontrol.api.config.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.lifecontrol.api.fiscal.service.FiscalCancellationPass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * {@code S6} of issue {@code #994}, which is a statement about the <b>context</b> and not about the pass: with the
 * property absent there must be no scheduling infrastructure at all, so a disabled pass cannot be a thread that ticks
 * forever while reporting nothing.
 *
 * <p><b>Why this suite exists.</b> An integration suite that calls {@code pass.runPass()} directly proves the pass
 * works; it proves nothing about whether the pass is ever <i>registered</i>. A prefix typo in the condition, or a SpEL
 * interval that cannot resolve, would leave the pass unregistered in every deployment — the exact silent state this
 * unit removes — with every other suite still green. So both halves are asserted on a context that has nothing but
 * this configuration in it.</p>
 */
class FiscalCancellationPassSchedulerConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(FiscalCancellationPassSchedulerConfig.class)
            .withBean(FiscalCancellationPass.class, () -> mock(FiscalCancellationPass.class));

    @Test
    @DisplayName("S6: an enabled pass is registered as exactly one scheduled task")
    void anEnabledPassRegistersOneScheduledTask() {
        runner.withPropertyValues(
                        "app.fiscal.cancellation.pass.enabled=true",
                        "app.fiscal.cancellation.pass.interval-seconds=600")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FiscalCancellationPassSchedulerConfig.ScheduledPass.class);
                    assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks().stream()
                                    .filter(task -> task.getTask() != null)
                                    .count())
                            .as("the interval expression resolved and the method was registered")
                            .isEqualTo(1);
                });
    }

    @Test
    @DisplayName("S6: with the property absent there is no scheduling infrastructure in the context at all")
    void anAbsentPropertyLeavesNoSchedulingBehind() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FiscalCancellationPassSchedulerConfig.ScheduledPass.class);
            assertThat(context).doesNotHaveBean(ScheduledTaskHolder.class);
        });
    }

    @Test
    @DisplayName("S6: an explicit false is the same as absent — a stated default cannot enable it")
    void aFalsePropertyIsOff() {
        runner.withPropertyValues("app.fiscal.cancellation.pass.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(FiscalCancellationPassSchedulerConfig.ScheduledPass.class);
            assertThat(context).doesNotHaveBean(ScheduledTaskHolder.class);
        });
    }
}
