package com.lifecontrol.api.config.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.health.DataRedisHealthContributorAutoConfiguration;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Pins the failure policy the application ships for an unreachable Redis.
 *
 * <p>Why this needs its own test: activating the shared Redis cache (the antecedent change) turned a
 * Redis outage from invisible into a measured one. With nothing bounding the wait, a cached read
 * answered {@code 500 after 60.15s} — the Lettuce default — on <b>every</b> request, and
 * {@code GET /actuator/health} stopped answering at all, which in an orchestrated environment is a
 * timed-out probe. Only eight cache regions exist, so the outage was taking down endpoints that
 * never touch the cache. Two settings fix that, and neither is visible in normal operation: a
 * bounded command timeout, and removing Redis from the health endpoint the container probe reads.
 *
 * <p>The tests read {@code application.properties} — the artifact Gradle puts on the test classpath —
 * rather than passing values in, because the thing under test is <b>what this application ships</b>.
 * A property that never reaches the connection factory or the health contributor is inert, exactly
 * as {@code spring.cache.type} was before the antecedent change, so each assertion checks the effect
 * and not only the file. The health cases carry controls that prove the assertions can fail.
 *
 * <p>An unreachable Redis is simulated with a server that <b>accepts the TCP connection and never
 * answers</b> ({@link HungRedisServer}), not with a closed port. A closed port refuses the connection
 * immediately, so it measures the connect failure and passes whatever the command timeout is; only a
 * server that stays silent makes the command wait for the bound under test.
 *
 * <p>These are context-runner tests rather than {@code @SpringBootTest}: they need no Redis server
 * and no database, and the factory is created without ever being connected.
 */
@DisplayName("Redis outage policy")
class RedisOutagePolicyTest {

    /**
     * The policy's bound, in milliseconds. It is written out rather than derived from the file so
     * that changing the shipped value has to be a deliberate edit of this expectation too.
     */
    private static final long EXPECTED_COMMAND_TIMEOUT_MILLIS = 2000L;

    /**
     * How long a failed command may take before the bound is not doing its job. It is far above the
     * configured 2s (so a loaded machine does not make this flaky) and far below the 60s Lettuce
     * default the bound replaces (so the default cannot sneak back in unnoticed).
     */
    private static final Duration FAILURE_BOUND = Duration.ofSeconds(10);

    private ApplicationContextRunner redisRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataRedisAutoConfiguration.class, DataRedisHealthContributorAutoConfiguration.class))
                .withPropertyValues("spring.data.redis.host=localhost", "spring.data.redis.port=6379");
    }

    /**
     * A runner that reaches the real {@link HealthEndpoint}, not just the contributor bean: it wires
     * the registry and the endpoint the container probe actually consumes.
     */
    private ApplicationContextRunner healthEndpointRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataRedisAutoConfiguration.class,
                        DataRedisHealthContributorAutoConfiguration.class,
                        HealthContributorRegistryAutoConfiguration.class,
                        HealthEndpointAutoConfiguration.class))
                .withPropertyValues("spring.data.redis.host=localhost", "spring.data.redis.port=6379");
    }

    /** Reads the application's own {@code application.properties} from the test classpath. */
    private static Properties applicationProperties() throws IOException {
        var properties = new Properties();
        try (InputStream in = RedisOutagePolicyTest.class.getResourceAsStream("/application.properties")) {
            if (in == null) {
                throw new IllegalStateException(
                        "application.properties is not on the test classpath; this test must read the shipped file");
            }
            properties.load(in);
        }
        return properties;
    }

    @Test
    @DisplayName("the shipped configuration bounds the Redis command timeout")
    void shippedConfiguration_BoundsTheRedisCommandTimeout() throws IOException {
        var configured = applicationProperties().getProperty("spring.data.redis.timeout");

        assertThat(configured)
                .as("application.properties must bound spring.data.redis.timeout: unset means the Lettuce "
                        + "default of 60s, and a cached read against a dead Redis blocks that long")
                .isNotNull();

        redisRunner()
                .withPropertyValues("spring.data.redis.timeout=" + configured)
                .run(context -> {
                    var factory = (LettuceConnectionFactory) context.getBean(RedisConnectionFactory.class);
                    assertThat(factory.getTimeout())
                            .as("the property must actually reach the connection factory; a value Boot "
                                    + "never applies is inert and bounds nothing")
                            .isEqualTo(EXPECTED_COMMAND_TIMEOUT_MILLIS);
                });
    }

    @Test
    @DisplayName("the shipped configuration keeps Redis out of the health endpoint the probe consumes")
    void shippedConfiguration_KeepsRedisOutOfTheHealthEndpoint() throws IOException {
        var configured = applicationProperties().getProperty("management.health.redis.enabled");

        assertThat(configured)
                .as("application.properties must disable the Redis health indicator: the container probe "
                        + "reads /actuator/health, so a Redis outage must not mark the service down")
                .isEqualTo("false");

        redisRunner()
                .withPropertyValues("management.health.redis.enabled=" + configured)
                .run(context -> assertThat(context)
                        .as("with the indicator disabled no Redis contributor is registered, so the health "
                                + "endpoint cannot block on Redis or report the service down because of it")
                        .doesNotHaveBean("redisHealthContributor"));
    }

    @Test
    @DisplayName("control: with the indicator enabled the contributor is registered, so the test can fail")
    void control_WithTheIndicatorEnabled_TheContributorIsRegistered() {
        redisRunner()
                .withPropertyValues("management.health.redis.enabled=true")
                .run(context -> assertThat(context)
                        .as("the previous assertion would pass vacuously if the contributor were never "
                                + "registered at all")
                        .hasBean("redisHealthContributor"));
    }

    @Test
    @DisplayName("the real health endpoint answers UP with no Redis contributor while Redis is hung")
    void healthEndpoint_WithRedisDisabled_AnswersUpAndCarriesNoRedisContributor() throws IOException {
        var properties = applicationProperties();
        var configured = properties.getProperty("management.health.redis.enabled");
        var timeout = properties.getProperty("spring.data.redis.timeout");

        try (var hungRedis = new HungRedisServer()) {
            healthEndpointRunner()
                    .withPropertyValues(
                            "management.health.redis.enabled=" + configured,
                            "spring.data.redis.timeout=" + timeout,
                            "spring.data.redis.port=" + hungRedis.port())
                    .run(context -> {
                        var registry = context.getBean(HealthContributorRegistry.class);
                        assertThat(registry.getContributor("redis"))
                                .as("with the indicator disabled the registry must carry no Redis contributor, "
                                        + "so /actuator/health cannot block on Redis or go down because of it")
                                .isNull();

                        var health = context.getBean(HealthEndpoint.class).health();
                        assertThat(health.getStatus().getCode())
                                .as("the endpoint the container probe reads must answer UP against a hung "
                                        + "Redis; a DOWN or a block here is the outage the policy prevents")
                                .isEqualTo(Status.UP.getCode());
                    });
        }
    }

    @Test
    @DisplayName("control: with the indicator enabled the endpoint carries Redis and goes DOWN")
    void control_WithRedisEnabled_TheEndpointCarriesRedisAndGoesDown() throws IOException {
        var timeout = applicationProperties().getProperty("spring.data.redis.timeout");

        try (var hungRedis = new HungRedisServer()) {
            healthEndpointRunner()
                    .withPropertyValues(
                            "management.health.redis.enabled=true",
                            "spring.data.redis.timeout=" + timeout,
                            "spring.data.redis.port=" + hungRedis.port())
                    .run(context -> {
                        var registry = context.getBean(HealthContributorRegistry.class);
                        assertThat(registry.getContributor("redis"))
                                .as("the disabled-direction assertion would pass vacuously if Redis never "
                                        + "reached the registry at all")
                                .isNotNull();

                        var health = context.getBean(HealthEndpoint.class).health();
                        assertThat(health.getStatus().getCode())
                                .as("with Redis enabled and hung, the health endpoint must report the outage; "
                                        + "a permanent UP would mean the endpoint never consults Redis")
                                .isEqualTo(Status.DOWN.getCode());
                    });
        }
    }

    @Test
    @DisplayName("a command against an unreachable Redis fails within the shipped bound")
    void unreachableRedis_FailsWithinTheShippedBound() throws IOException {
        var configured = applicationProperties().getProperty("spring.data.redis.timeout");

        try (var hungRedis = new HungRedisServer()) {
            redisRunner()
                    .withPropertyValues(
                            "spring.data.redis.timeout=" + configured, "spring.data.redis.port=" + hungRedis.port())
                    .run(context -> {
                        var factory = context.getBean(RedisConnectionFactory.class);

                        var startedAt = System.nanoTime();
                        var thrown =
                                catchThrowable(() -> factory.getConnection().ping());
                        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

                        assertThat(thrown)
                                .as("an unreachable Redis must fail rather than answer")
                                .isInstanceOf(DataAccessException.class);
                        assertThat(elapsed)
                                .as("the bound is the whole point of the setting: a server that accepts the "
                                        + "connection and never replies must make the command time out at the "
                                        + "configured bound, not at the 60s Lettuce default")
                                .isLessThan(FAILURE_BOUND);
                    });
        }
    }

    /**
     * A server that accepts TCP connections and never writes, so a command against it waits for the
     * configured command timeout instead of failing at connect time.
     *
     * <p>This is the controlled differential the policy needs: a closed port refuses the connection
     * immediately, so the elapsed time is the connect failure and the test passes whatever the bound
     * is. Accepting the connection and staying silent is the only shape that exposes the timeout.
     */
    private static final class HungRedisServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final List<Socket> acceptedConnections = new CopyOnWriteArrayList<>();
        private final Thread acceptThread;

        HungRedisServer() throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.acceptThread = new Thread(this::acceptForever, "hung-redis-server");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void acceptForever() {
            while (!serverSocket.isClosed()) {
                try {
                    // Hold every accepted connection open and never write: the peer's command must
                    // time out rather than be answered or refused.
                    acceptedConnections.add(serverSocket.accept());
                } catch (IOException stopped) {
                    return;
                }
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            acceptThread.interrupt();
            for (var connection : acceptedConnections) {
                connection.close();
            }
        }
    }
}
