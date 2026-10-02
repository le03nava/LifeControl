package com.lifecontrol.api.common.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the Keycloak protocol mappers in {@code docker/scripts/keycloak-setup.sh} to the claims
 * {@link ScopeLevel} reads.
 *
 * <p>{@link ScopeLevel} is the single source of truth for the five scoped-authorization claim names,
 * and {@code CurrentUserContext} reads them straight out of the top level of the JWT. Nothing in the
 * Java code provisions them: a claim only reaches the token if the realm's app client carries a
 * protocol mapper for it. The provisioning lives in a shell script this build never executes, so a
 * claim the code reads without a mapper in the script is invisible to every Java test and only shows
 * up as a 403 for a scoped caller holding no {@code lc-admin}.</p>
 *
 * <p>This test closes that gap deterministically and offline: it reads the script's {@code
 * TENANCY_CLAIMS} array and asserts <b>set equality in both directions</b> with {@code
 * ScopeLevel.values()}. The forward direction catches "the code reads a claim nobody provisions"; the
 * reverse direction catches drift and unprovisioned extras. A third assertion verifies the script
 * actually creates the mappers (not merely lists the claims) and that they are
 * {@code oidc-usermodel-attribute-mapper}s with the multivalued flag enabled, which is what makes a
 * multi-store attribute arrive as a JSON array instead of a single collapsed value. It starts no
 * Spring context, touches no network, and depends on no timing.</p>
 *
 * <p>Known limitation: this asserts a convention. The script must declare its claims as literal
 * lines strictly between {@code TENANCY_CLAIMS=(} and its closing {@code )}. If that shape changes,
 * the anchor assertions below fail loudly instead of passing vacuously.</p>
 *
 * @see ScopeLevel#claim()
 */
class KeycloakClaimMapperCoverageTest {

    /** The script that provisions the realm, clients, roles and (now) protocol mappers. */
    private static final String SETUP_SCRIPT = "docker/scripts/keycloak-setup.sh";

    /** The explicit bash array the setup script declares; this test's stable anchor. */
    private static final String TENANCY_CLAIMS_DECLARATION = "TENANCY_CLAIMS=(";

    /** The only protocol mapper type that turns a user attribute into a top-level token claim. */
    private static final String USER_ATTRIBUTE_MAPPER = "oidc-usermodel-attribute-mapper";

    /**
     * The load-bearing config flag, written the way the script writes it after shell unescaping.
     *
     * <p>Without it Keycloak collapses a multivalued user attribute to its first value instead of
     * emitting a JSON array, and multi-store callers silently lose scopes.</p>
     */
    private static final String MULTIVALUED_CONFIG = "config.\"multivalued\"=true";

    /** The resource kcadm reads and writes the declarative user-profile policy through. */
    private static final String USER_PROFILE_RESOURCE = "users/profile";

    /**
     * The policy value that keeps unmanaged attributes storable once an administrator writes them.
     *
     * <p>Not {@code ENABLED}: the administrator endpoints are the write path an access projection
     * uses, and a subject must never be able to assign itself a tenancy.</p>
     */
    private static final String UNMANAGED_ATTRIBUTE_POLICY = "ADMIN_EDIT";

    @Test
    void everyScopeLevelClaimHasAProtocolMapperInTheSetupScript() throws IOException {
        Set<String> declared = claimsDeclaredBySetupScript(setupScript());
        Set<String> missing = new TreeSet<>(claimsScopeLevelReads());
        missing.removeAll(declared);

        assertTrue(
                missing.isEmpty(),
                "ScopeLevel reads these claims but the setup script declares no protocol mapper for them, so"
                        + " the claim never reaches the token and every scoped caller is denied: "
                        + missing
                        + ". Add one entry per missing claim to the "
                        + TENANCY_CLAIMS_DECLARATION
                        + " array in "
                        + SETUP_SCRIPT
                        + " and one mapper for it below.");
    }

    @Test
    void theSetupScriptDeclaresNoClaimScopeLevelDoesNotRead() throws IOException {
        Set<String> declared = claimsDeclaredBySetupScript(setupScript());
        Set<String> unread = new TreeSet<>(declared);
        unread.removeAll(claimsScopeLevelReads());

        assertTrue(
                unread.isEmpty(),
                "The setup script declares claims that ScopeLevel never reads, so their mappers emit token"
                        + " claims nothing consumes: "
                        + unread
                        + ". Remove them from the "
                        + TENANCY_CLAIMS_DECLARATION
                        + " array in "
                        + SETUP_SCRIPT
                        + ", or add the level to ScopeLevel if the claim is actually meant to be read.");
    }

    @Test
    void theSetupScriptCreatesMultivaluedUserAttributeMappers() throws IOException {
        // The mapper arguments are shell-escaped (config.\"claim.name\"=...); dropping the backslashes
        // leaves the config keys in their readable form so the assertions below stay legible.
        String mapperBlock = mapperCreationBlock(setupScript()).replace("\\", "");

        assertTrue(
                mapperBlock.contains(USER_ATTRIBUTE_MAPPER),
                "The mapper-creation block after "
                        + TENANCY_CLAIMS_DECLARATION
                        + " in "
                        + SETUP_SCRIPT
                        + " must create "
                        + USER_ATTRIBUTE_MAPPER
                        + " mappers reading user attributes, but the block contains no such mapper type.");

        assertTrue(
                mapperBlock.contains(MULTIVALUED_CONFIG),
                "The mapper-creation block in "
                        + SETUP_SCRIPT
                        + " must set "
                        + MULTIVALUED_CONFIG
                        + ". Without it Keycloak collapses a multivalued user attribute to a single value"
                        + " instead of emitting a JSON array, and multi-store callers silently lose scopes.");
    }

    /**
     * Pins the realm side of the same dependency the mappers above depend on.
     *
     * <p>The realm has Keycloak's declarative user profile enabled and declares only {@code
     * username}, {@code email}, {@code firstName} and {@code lastName}. With the unmanaged-attribute
     * policy unset, writing {@code company_id} through the admin REST API still answers {@code 204}
     * and the attribute is <b>silently discarded</b>: it reads back as {@code null}, the mappers
     * above emit no claim, and every scoped caller is denied. That failure happens inside Keycloak,
     * so no Java test can observe it — this assertion is the only thing that can.</p>
     */
    @Test
    void theSetupScriptMakesUnmanagedAttributesStorable() throws IOException {
        String script = setupScript();
        String setting = "unmanagedAttributePolicy=" + UNMANAGED_ATTRIBUTE_POLICY;
        boolean setsPolicy = Stream.of(script.split("\n"))
                .map(String::trim)
                .filter(line -> !line.startsWith("#"))
                .anyMatch(line -> line.contains(USER_PROFILE_RESOURCE) && line.contains(setting));

        assertTrue(
                setsPolicy,
                "The setup script "
                        + SETUP_SCRIPT
                        + " never updates "
                        + USER_PROFILE_RESOURCE
                        + " with "
                        + setting
                        + ". The realm's declarative user profile has unmanaged attributes disabled, so"
                        + " the tenancy attributes the mappers above read are silently DISCARDED: the admin"
                        + " REST API still answers 204, the attribute reads back as null, no claim reaches the"
                        + " token, and every scoped caller is denied. No Java test can see it, because it"
                        + " happens inside Keycloak. Set it to "
                        + UNMANAGED_ATTRIBUTE_POLICY
                        + " (never ENABLED: the administrator endpoints are the write path, and a subject"
                        + " must not be able to assign itself a tenancy).");
    }

    /** The claim names this repository reads, derived from {@link ScopeLevel}. */
    private static Set<String> claimsScopeLevelReads() {
        return Stream.of(ScopeLevel.values()).map(ScopeLevel::claim).collect(Collectors.toCollection(TreeSet::new));
    }

    /** Parses the claim names strictly between {@code TENANCY_CLAIMS=(} and its closing parenthesis. */
    private static Set<String> claimsDeclaredBySetupScript(String script) {
        int open = tenancyClaimsDeclarationStart(script) + TENANCY_CLAIMS_DECLARATION.length();
        int close = tenancyClaimsDeclarationEnd(script);

        Set<String> claims = new TreeSet<>();
        for (String line : script.substring(open, close).split("\n")) {
            String entry = line.trim();
            if (!entry.isEmpty() && !entry.startsWith("#")) {
                claims.add(entry);
            }
        }
        return claims;
    }

    /** Everything the script does after listing the claims — where the mappers must be created. */
    private static String mapperCreationBlock(String script) {
        return script.substring(tenancyClaimsDeclarationEnd(script) + 1);
    }

    private static int tenancyClaimsDeclarationStart(String script) {
        int declaration = script.indexOf(TENANCY_CLAIMS_DECLARATION);
        assertTrue(
                declaration >= 0,
                "The setup script "
                        + SETUP_SCRIPT
                        + " no longer declares the "
                        + TENANCY_CLAIMS_DECLARATION
                        + " array this test anchors on. Restore the declaration, or update the anchor in"
                        + " this test, so the claims the code reads stay pinned to the mappers that"
                        + " provision them.");
        return declaration;
    }

    private static int tenancyClaimsDeclarationEnd(String script) {
        int open = tenancyClaimsDeclarationStart(script) + TENANCY_CLAIMS_DECLARATION.length();
        int close = script.indexOf(')', open);
        assertTrue(
                close > open,
                "The "
                        + TENANCY_CLAIMS_DECLARATION
                        + " array in "
                        + SETUP_SCRIPT
                        + " has no closing parenthesis after the declaration at offset "
                        + open
                        + ".");
        return close;
    }

    private static String setupScript() throws IOException {
        Path script = repositoryRoot().resolve(SETUP_SCRIPT);
        assertTrue(Files.isRegularFile(script), "Setup script not found at " + script);
        return Files.readString(script);
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
