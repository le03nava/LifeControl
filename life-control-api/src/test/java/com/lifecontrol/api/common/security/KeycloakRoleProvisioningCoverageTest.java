package com.lifecontrol.api.common.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code lc-*} client roles {@link Roles} declares to the loop in
 * {@code docker/scripts/keycloak-setup.sh} that provisions them in Keycloak.
 *
 * <p>{@link Roles} is the single source of truth for the role names an {@code @PreAuthorize} references, and
 * {@code JwtDecoderConfig} turns each Keycloak client role {@code lc-x} into the authority
 * {@code ROLE_lc-x} the code checks. Nothing in the Java code grants them: a role exists for a caller only if
 * the realm's app client carries it, and the provisioning lives in a shell script this build never executes.
 * So a constant the code checks without a line in that loop is invisible to every Java test and only shows up
 * as a 403 for the operator who was supposed to hold it — the exact failure a role that was never created
 * produces, indistinguishable at runtime from "this caller was not granted it".</p>
 *
 * <p>This test closes that gap deterministically and offline: it reads the script's client-role loop and
 * asserts <b>set equality in both directions</b> with the {@code lc-*} constants of {@code Roles.java}. The
 * forward direction catches "the code checks a role nobody provisions"; the reverse direction catches a role
 * provisioned that no constant can ever check. Neither list is written down here: both are recomputed from
 * the two files, which is the whole point — a hand-maintained expected list drifts exactly like the two files
 * drift. It starts no Spring context, touches no network, and depends on no timing.</p>
 *
 * <p>Known limitation: this asserts a convention. The script must keep its client-role loop headed by
 * {@code for role in} and terminated by {@code ; do}, with every entry a bare {@code lc-*} word (line
 * continuations are fine). The bound is anchored on the loop's <b>content</b> — the one {@code for role in}
 * block whose entries are all client roles — because the script has a second {@code for role in} loop for
 * {@code realm-management} roles that this test must not read. If that shape changes, the anchor assertion
 * below fails loudly instead of passing vacuously.</p>
 *
 * @see Roles
 */
class KeycloakRoleProvisioningCoverageTest {

    /** The script that provisions the realm, clients, roles and protocol mappers. */
    private static final String SETUP_SCRIPT = "docker/scripts/keycloak-setup.sh";

    /** The source of truth for the role names the code checks. */
    private static final String ROLES_SOURCE =
            "life-control-api/src/main/java/com/lifecontrol/api/common/security/Roles.java";

    /** The loop header every role-provisioning loop in the script uses; the block is bounded on it. */
    private static final String ROLE_LOOP_HEADER = "for role in ";

    /** The line that closes the loop's role list, so the block ends at its {@code do}. */
    private static final String ROLE_LOOP_TERMINATOR = "; do";

    /** A client role of {@code life-control-client}, as {@code Roles} and the script both spell it. */
    private static final Pattern CLIENT_ROLE = Pattern.compile("lc-[a-z0-9-]+");

    /**
     * A constant declaration of a client role in {@code Roles.java}.
     *
     * <p>Matched as the declaration, not as any quoted {@code lc-*} string: the file's javadoc names roles in
     * prose ({@code {@code lc-admin}}, {@code {@code life-control-admin}}), and reading those as declarations
     * would make the expected set depend on comments.</p>
     */
    private static final Pattern ROLES_CONSTANT =
            Pattern.compile("static final String [A-Z0-9_]+ = \"(lc-[a-z0-9-]+)\";");

    @Test
    void everyRoleConstantIsProvisionedByTheSetupScript() throws IOException {
        Set<String> missing = new TreeSet<>(rolesConstantsDeclare());
        missing.removeAll(rolesTheSetupScriptProvisions());

        assertTrue(
                missing.isEmpty(),
                "Roles.java declares these client roles but the client-role loop in "
                        + SETUP_SCRIPT
                        + " never provisions them, so no caller can ever hold them and every"
                        + " @PreAuthorize that checks one denies everybody: "
                        + missing
                        + ". Add each missing role to the client-role loop in "
                        + SETUP_SCRIPT
                        + ".");
    }

    @Test
    void theSetupScriptProvisionsNoRoleRolesDoesNotDeclare() throws IOException {
        Set<String> uncheckable = new TreeSet<>(rolesTheSetupScriptProvisions());
        uncheckable.removeAll(rolesConstantsDeclare());

        assertTrue(
                uncheckable.isEmpty(),
                "The client-role loop in "
                        + SETUP_SCRIPT
                        + " provisions these roles but Roles.java declares no constant for them, so they are"
                        + " granted in Keycloak and checked by nothing: "
                        + uncheckable
                        + ". Declare each one in "
                        + ROLES_SOURCE
                        + ", or remove it from the loop if it is not a role this API checks.");
    }

    /** The {@code lc-*} client roles declared as constants by {@link Roles}. */
    private static Set<String> rolesConstantsDeclare() throws IOException {
        var declarations = new TreeSet<String>();
        Matcher constant = ROLES_CONSTANT.matcher(read(ROLES_SOURCE));
        while (constant.find()) {
            declarations.add(constant.group(1));
        }
        assertTrue(
                !declarations.isEmpty(),
                ROLES_SOURCE
                        + " declares no lc-* constant this test can read; either the constants moved out of"
                        + " this file or their form changed, and this test would otherwise compare the setup"
                        + " script against an empty set.");
        return declarations;
    }

    /** The roles listed by the {@code life-control-client} role loop of the setup script. */
    private static Set<String> rolesTheSetupScriptProvisions() throws IOException {
        String script = read(SETUP_SCRIPT);
        List<Set<String>> clientRoleLoops = new ArrayList<>();
        for (int header = script.indexOf(ROLE_LOOP_HEADER); header >= 0; ) {
            int listStart = header + ROLE_LOOP_HEADER.length();
            int listEnd = script.indexOf(ROLE_LOOP_TERMINATOR, listStart);
            assertTrue(
                    listEnd > listStart,
                    "The role loop starting at offset "
                            + header
                            + " in "
                            + SETUP_SCRIPT
                            + " has no terminating "
                            + ROLE_LOOP_TERMINATOR
                            + ", so this test cannot bound the block it reads.");
            Set<String> roles = rolesOf(script.substring(listStart, listEnd));
            if (!roles.isEmpty()
                    && roles.stream().allMatch(role -> CLIENT_ROLE.matcher(role).matches())) {
                clientRoleLoops.add(roles);
            }
            header = script.indexOf(ROLE_LOOP_HEADER, listEnd);
        }

        assertTrue(
                clientRoleLoops.size() == 1,
                "Expected exactly one "
                        + ROLE_LOOP_HEADER
                        + " block of bare client roles in "
                        + SETUP_SCRIPT
                        + ", found "
                        + clientRoleLoops.size()
                        + ". The script also lists realm-management roles in a loop of this shape, so this test"
                        + " identifies the client-role loop by its content; restore that shape, or update this"
                        + " test's bound, so the roles the code checks stay pinned to the ones provisioned.");
        return clientRoleLoops.get(0);
    }

    /** The bare words of one loop's role list, with continuation markers and commentary dropped. */
    private static Set<String> rolesOf(String list) {
        Set<String> roles = new TreeSet<>();
        for (String entry : list.strip().split("\\s+")) {
            String role = entry.strip();
            if (!role.isEmpty() && !role.equals("\\") && !role.startsWith("#")) {
                roles.add(role);
            }
        }
        return roles;
    }

    private static String read(String repositoryRelativePath) throws IOException {
        Path file = repositoryRoot().resolve(repositoryRelativePath);
        assertTrue(Files.isRegularFile(file), "File not found at " + file);
        return Files.readString(file);
    }

    /**
     * Walks up from the working directory to the checkout root, identified by holding both the setup
     * script and the API sources.
     *
     * <p>Works whether Gradle runs this module's tests from {@code life-control-api/} or the root.</p>
     */
    private static Path repositoryRoot() {
        Path start = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve(SETUP_SCRIPT))
                    && Files.isDirectory(candidate.resolve("life-control-api/src/main/java"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate the repository root (a directory holding both "
                + SETUP_SCRIPT
                + " and life-control-api/src/main/java) by walking up from "
                + start);
    }
}
