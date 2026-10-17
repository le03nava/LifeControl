package com.lifecontrol.api.config.logbook;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import org.zalando.logbook.HttpHeaders;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.Origin;
import org.zalando.logbook.core.StatusAtLeastStrategy;

/**
 * Drives the {@link Logbook} this application actually builds — with its real sanitizer and
 * the non-dev strategy — and asserts what lands on the log: nothing for a 2xx, both halves
 * for a refusal, and no sensitive value in either.
 * <p>
 * The suite's {@code logback-test.xml} keeps Logbook muted, so the logger is enabled here for
 * the duration of these tests only.
 */
@DisplayName("built Logbook emission and redaction")
class LogbookEmissionTest {

    private static final String REQUEST_SECRET = "REQSECRET";
    private static final String RESPONSE_SECRET = "RESPSECRET";
    private static final String QUERY_SECRET = "QSECRET";

    private Logger logbookLogger;
    private Level previousLevel;
    private boolean previousAdditive;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        logbookLogger = (Logger) LoggerFactory.getLogger("org.zalando.logbook.Logbook");
        previousLevel = logbookLogger.getLevel();
        previousAdditive = logbookLogger.isAdditive();
        logbookLogger.setLevel(Level.TRACE);
        logbookLogger.setAdditive(false);
        appender.start();
        logbookLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logbookLogger.detachAppender(appender);
        logbookLogger.setLevel(previousLevel);
        logbookLogger.setAdditive(previousAdditive);
    }

    @Test
    @DisplayName("off dev, a 2xx emits nothing and a refusal emits both halves without leaking a secret")
    void emitsFailuresOnlyAndRedacts() throws IOException {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        Logbook logbook =
                new LogbookConfig().logbook(new SensitiveDataSanitizer(environment), new StatusAtLeastStrategy(400));

        exchange(logbook, request(), response(200, "{}"));
        assertThat(appender.list).isEmpty();

        exchange(logbook, request(), response(500, "{\"password\":\"" + RESPONSE_SECRET + "\"}"));
        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list)
                .allSatisfy(event -> assertThat(event.getLoggerName()).isEqualTo("org.zalando.logbook.Logbook"));

        var logged = String.join(
                "\n",
                appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(logged).doesNotContain(REQUEST_SECRET, RESPONSE_SECRET, QUERY_SECRET);
        // Off dev the body is discarded whole, so not even the field name survives: the
        // partial JSON field filter would have kept `password` and redacted only its value.
        assertThat(logged).doesNotContain("password");
    }

    private static void exchange(Logbook logbook, HttpRequest request, HttpResponse response) throws IOException {
        logbook.process(request).write().process(response).write();
    }

    @Test
    @DisplayName("off dev, a non-JSON body is discarded whole rather than passed through")
    void redactsNonJsonBodyWhole() throws IOException {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        Logbook logbook =
                new LogbookConfig().logbook(new SensitiveDataSanitizer(environment), new StatusAtLeastStrategy(400));

        exchange(
                logbook,
                request("text/plain", "password=" + REQUEST_SECRET),
                response(500, "text/plain", "password=" + RESPONSE_SECRET));

        var logged = String.join(
                "\n",
                appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(logged).doesNotContain(REQUEST_SECRET, RESPONSE_SECRET);
        assertThat(logged).contains("[REDACTED]");
    }

    private static HttpRequest request() {
        return request("application/json", "{\"password\":\"" + REQUEST_SECRET + "\"}");
    }

    private static HttpRequest request(String contentType, String body) {
        return proxy(
                HttpRequest.class,
                values(
                        "getOrigin",
                        Origin.REMOTE,
                        "getRemote",
                        "127.0.0.1",
                        "getMethod",
                        "POST",
                        "getScheme",
                        "http",
                        "getHost",
                        "localhost",
                        "getPort",
                        Optional.of(8080),
                        "getPath",
                        "/probe",
                        "getQuery",
                        "access_token=" + QUERY_SECRET,
                        "getProtocolVersion",
                        "HTTP/1.1",
                        "getContentType",
                        contentType,
                        "getCharset",
                        StandardCharsets.UTF_8,
                        "getHeaders",
                        HttpHeaders.of("Authorization", "Bearer " + REQUEST_SECRET),
                        "getBodyAsString",
                        body,
                        "getBody",
                        body.getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpResponse response(int status, String body) {
        return response(status, "application/json", body);
    }

    private static HttpResponse response(int status, String contentType, String body) {
        return proxy(
                HttpResponse.class,
                values(
                        "getOrigin",
                        Origin.LOCAL,
                        "getStatus",
                        status,
                        "getReasonPhrase",
                        "probe",
                        "getProtocolVersion",
                        "HTTP/1.1",
                        "getContentType",
                        contentType,
                        "getCharset",
                        StandardCharsets.UTF_8,
                        "getHeaders",
                        HttpHeaders.empty(),
                        "getBodyAsString",
                        body,
                        "getBody",
                        body.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> values(Object... keyValuePairs) {
        var values = new HashMap<String, Object>();
        for (var i = 0; i < keyValuePairs.length; i += 2) {
            values.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return values;
    }

    private static <T> T proxy(Class<T> type, Map<String, Object> values) {
        return type.cast(
                Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (instance, method, args) -> {
                    var name = method.getName();
                    if ("withBody".equals(name) || "withoutBody".equals(name)) {
                        return instance;
                    }
                    if (values.containsKey(name)) {
                        return values.get(name);
                    }
                    var returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == int.class) {
                        return 0;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    return null;
                }));
    }
}
