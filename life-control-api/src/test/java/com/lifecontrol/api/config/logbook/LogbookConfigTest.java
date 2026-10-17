package com.lifecontrol.api.config.logbook;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.zalando.logbook.Correlation;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.Precorrelation;
import org.zalando.logbook.Sink;
import org.zalando.logbook.core.DefaultStrategy;
import org.zalando.logbook.core.StatusAtLeastStrategy;

/**
 * Pins the two halves of this application's Logbook rule: which strategy the
 * configuration hands to Logbook, and what that strategy emits.
 * <p>
 * The rule is what {@code logback-spring.xml} depends on. Logbook's own writer emits
 * only at TRACE and the suite's {@code logback-test.xml} keeps that logger quiet, so
 * the level itself is verified against a running application, not here.
 */
@DisplayName("LogbookConfig strategy")
class LogbookConfigTest {

    private final LogbookConfig config = new LogbookConfig();

    private static MockEnvironment env(String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Nested
    @DisplayName("profile mapping")
    class ProfileMapping {

        @Test
        @DisplayName("hands Logbook the default strategy on dev, where every exchange is logged")
        void devLogsEveryExchange() {
            assertThat(config.logbookStrategy(env("dev"))).isInstanceOf(DefaultStrategy.class);
        }

        @Test
        @DisplayName("hands Logbook the status-at-least strategy on every other profile")
        void otherProfilesLogFailuresOnly() {
            assertThat(config.logbookStrategy(env("prod"))).isInstanceOf(StatusAtLeastStrategy.class);
            assertThat(config.logbookStrategy(env("staging"))).isInstanceOf(StatusAtLeastStrategy.class);
            assertThat(config.logbookStrategy(env())).isInstanceOf(StatusAtLeastStrategy.class);
        }
    }

    @Nested
    @DisplayName("what the chosen strategy emits")
    class Emissions {

        @Test
        @DisplayName("off dev, a 200 emits nothing and a >=400 emits the request and the response")
        void failuresOnly() throws Exception {
            var strategy = config.logbookStrategy(env("prod"));
            var sink = new RecordingSink();
            var request = request();

            strategy.write(precorrelation(), request, sink.sink());
            assertThat(sink.calls).isEmpty();

            strategy.write(correlation(), request, response(200), sink.sink());
            assertThat(sink.calls).isEmpty();

            strategy.write(correlation(), request, response(400), sink.sink());
            assertThat(sink.calls).containsExactly("writeBoth");

            strategy.write(correlation(), request, response(500), sink.sink());
            assertThat(sink.calls).containsExactly("writeBoth", "writeBoth");
        }

        @Test
        @DisplayName("on dev, the request half is emitted as soon as the request arrives")
        void devEmitsTheRequestImmediately() throws Exception {
            var strategy = config.logbookStrategy(env("dev"));
            var sink = new RecordingSink();

            strategy.write(precorrelation(), request(), sink.sink());

            assertThat(sink.calls).containsExactly("write");
        }
    }

    /** Records which halves of the exchange Logbook was asked to write. */
    private static final class RecordingSink {

        private final List<String> calls = new ArrayList<>();

        Sink sink() {
            return proxy(Sink.class, (method, args) -> {
                calls.add(method);
                return null;
            });
        }
    }

    private static HttpRequest request() {
        return proxy(HttpRequest.class, (method, args) -> "getMethod".equals(method) ? "GET" : null);
    }

    private static HttpResponse response(int status) {
        return proxy(HttpResponse.class, (method, args) -> "getStatus".equals(method) ? status : null);
    }

    private static Precorrelation precorrelation() {
        return proxy(Precorrelation.class, (method, args) -> null);
    }

    private static Correlation correlation() {
        return proxy(Correlation.class, (method, args) -> null);
    }

    private static <T> T proxy(Class<T> type, StubHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (instance, method, args) -> {
                    var value = handler.invoke(method.getName(), args);
                    return value != null ? value : primitiveDefault(method.getReturnType());
                }));
    }

    /** Logbook's interfaces return primitives too, so a stub has to answer for them. */
    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    @FunctionalInterface
    private interface StubHandler {
        Object invoke(String method, Object[] args);
    }
}
