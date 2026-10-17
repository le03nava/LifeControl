package com.lifecontrol.api.config.logbook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the deployment half of the fail-closed profile guard: the deployment layer must not
 * manufacture the {@code dev} that the guard refuses to start on.
 * <p>
 * The API module has no offline harness for the compose files, so this is a structural
 * check: it reads {@code docker/docker-compose.yml} and the k8s application manifests and
 * asserts the variable is required (compose) and never defaulted by a shared manifest (k8s).
 * It reads the repository tree, never a running daemon, so it is deterministic and offline.
 * <p>
 * The k8s half pins an absence on purpose. A profile declared in the **shared** ConfigMap is a
 * shared default: it would hand every environment the {@code dev} profile — the exposure this
 * unit closes — while making the fail-closed guard pass, because the guard fires on a missing
 * declaration and not on a wrong one. The profile belongs to an environment-specific overlay,
 * which this tree does not have yet, so the honest pin is that no shared manifest supplies it.
 */
@DisplayName("deployment layer declares SPRING_PROFILES_ACTIVE")
class DeploymentProfileConfigurationTest {

    private static Path repositoryRoot;

    @BeforeAll
    static void locateRepositoryRoot() {
        repositoryRoot = findRepositoryRoot();
    }

    @Test
    @DisplayName("docker compose requires the variable instead of defaulting to dev")
    void composeRequiresTheVariable() throws IOException {
        var compose = Files.readString(repositoryRoot.resolve("docker/docker-compose.yml"));

        assertThat(compose)
                .as("the runtime profile must be required, so a forgotten variable fails the deployment "
                        + "instead of silently selecting dev")
                .contains("SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:?");
        assertThat(compose)
                .as("no fallback to dev may survive in the compose file")
                .doesNotContain("SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-");
    }

    @Test
    @DisplayName("no shared k8s manifest hands the cluster a dev profile")
    void sharedK8sConfigDeclaresNoProfile() throws IOException {
        var applications = repositoryRoot.resolve("k8s/manifests/applications");
        var sharedConfig = Files.readString(applications.resolve("common-config.yml"));

        assertThat(sharedConfig)
                .as("a profile declared in the shared ConfigMap is a shared default: it would hand every "
                        + "environment the dev profile, which is the exposure this unit closes, and it would "
                        + "make the fail-closed guard pass by supplying the very declaration the guard asks "
                        + "for. The profile belongs to an environment-specific overlay, which this tree does "
                        + "not have yet; until it exists the guard refusing to start is the correct behaviour.")
                .doesNotContain("SPRING_PROFILES_ACTIVE");
    }

    private static Path findRepositoryRoot() {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("docker/docker-compose.yml"))
                    && Files.isDirectory(current.resolve("k8s/manifests"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found from " + System.getProperty("user.dir"));
    }
}
