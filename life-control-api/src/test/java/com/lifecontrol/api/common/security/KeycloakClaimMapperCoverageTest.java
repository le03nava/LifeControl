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
 * Pins the Keycloak protocol mappers in {@code docker/scripts/keycloak-setup.sh}, and the realm's
 * unmanaged-attribute policy they depend on, to the claims {@link ScopeLevel} reads.
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
 * reverse direction catches drift and unprovisioned extras. A third assertion bounds the script's
 * mapper loop and verifies the loop body actually binds every declared claim — the mapper name, the
 * emitted token claim and the user attribute all come from the loop variable — as an {@code
 * oidc-usermodel-attribute-mapper} with the multivalued flag enabled, which is what makes a
 * multi-store attribute arrive as a JSON array instead of a single collapsed value. A fourth
 * assertion pins the realm policy that makes those attributes storable at all. It starts no Spring
 * context, touches no network, and depends on no timing.</p>
 *
 * <p>Known limitation: this asserts a convention. The script must declare its claims as literal
 * lines strictly between {@code TENANCY_CLAIMS=(} and its closing {@code )}, and must keep the mapper
 * loop headed by {@code for claim in "${TENANCY_CLAIMS[@]}"} and terminated by its {@code done}. If
 * that shape changes, the anchor assertions below fail loudly instead of passing vacuously.</p>
 *
 * @see ScopeLevel#claim()
 */
class KeycloakClaimMapperCoverageTest {

    /** The script that provisions the realm, clients, roles and (now) protocol mappers. */
    private static final String SETUP_SCRIPT = "docker/scripts/keycloak-setup.sh";

    /** The explicit bash array the setup script declares; this test's stable anchor. */
    private static final String TENANCY_CLAIMS_DECLARATION = "TENANCY_CLAIMS=(";

    /** The loop header the mapper block is bounded to, and the claim its body binds. */
    private static final String MAPPER_LOOP_HEADER = "for claim in \"${TENANCY_CLAIMS[@]}\"; do";

    /** The line that closes the mapper loop: the block ends here, not at the claims array. */
    private static final String MAPPER_LOOP_TERMINATOR = "\ndone";

    /** The only protocol mapper type that turns a user attribute into a top-level token claim. */
    private static final String USER_ATTRIBUTE_MAPPER = "oidc-usermodel-attribute-mapper";

    /** Binds the mapper's name to the loop variable; a literal here provisions only one claim. */
    private static final String NAME_BINDING = "-s name=\"$claim\"";

    /** Binds the emitted token claim to the loop variable, written the way the script writes it. */
    private static final String CLAIM_NAME_BINDING = "-s \"config.\"claim.name\"=$claim\"";

    /** Binds the user attribute the mapper reads to the loop variable. */
    private static final String USER_ATTRIBUTE_BINDING = "-s \"config.\"user.attribute\"=$claim\"";

    /**
     * The load-bearing config flag, written the way the script writes it after shell unescaping.
     *
     * <p>Without it Keycloak collapses a multivalued user attribute to its first value instead of
     * emitting a JSON array, and multi-store callers silently lose scopes.</p>
     */
    private static final String MULTIVALUED_BINDING = "-s \"config.\"multivalued\"=true\"";

    /** The resource kcadm reads and writes the declarative user-profile policy through. */
    private static final String USER_PROFILE_RESOURCE = "users/profile";

    /**
     * The kcadm invocation that must carry the policy assignment itself, not a nearby line.
     *
     * <p>Asserted as a line prefix, not as a substring: a diagnostic or comment that merely quotes
     * the command would otherwise satisfy the assertion without the value ever reaching the command.
     * The script keeps this invocation on one line, which is the same kind of declared convention the
     * {@code TENANCY_CLAIMS} anchor is.</p>
     */
    private static final String POLICY_UPDATE_COMMAND = "kcadm update " + USER_PROFILE_RESOURCE;

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
                mapperBlock.contains(MAPPER_LOOP_HEADER),
                "The bounded mapper block in "
                        + SETUP_SCRIPT
                        + " must begin at the loop header "
                        + MAPPER_LOOP_HEADER
                        + " and bind the claim through the loop variable, but it does not; the bound in this"
                        + " test no longer matches the script's mapper loop.");
        assertTrue(
                mapperBlock.contains(USER_ATTRIBUTE_MAPPER),
                "The mapper-creation loop in "
                        + SETUP_SCRIPT
                        + " must create "
                        + USER_ATTRIBUTE_MAPPER
                        + " mappers reading user attributes, but the loop body contains no such mapper type.");
        assertTrue(
                mapperBlock.contains(NAME_BINDING),
                "The mapper-creation loop in "
                        + SETUP_SCRIPT
                        + " must bind the mapper name to the loop variable ("
                        + NAME_BINDING
                        + "), but it does not. A literal name provisions only one claim, so the remaining "
                        + TENANCY_CLAIMS_DECLARATION
                        + " claims never reach the token and their callers are denied.");
        assertTrue(
                mapperBlock.contains(CLAIM_NAME_BINDING),
                "The mapper-creation loop in "
                        + SETUP_SCRIPT
                        + " must bind config.\"claim.name\" to the loop variable ("
                        + CLAIM_NAME_BINDING
                        + "), but it does not. Without it the emitted token claim is not the one "
                        + TENANCY_CLAIMS_DECLARATION
                        + " declares.");
        assertTrue(
                mapperBlock.contains(USER_ATTRIBUTE_BINDING),
                "The mapper-creation loop in "
                        + SETUP_SCRIPT
                        + " must bind config.\"user.attribute\" to the loop variable ("
                        + USER_ATTRIBUTE_BINDING
                        + "), but it does not. Without it the mapper reads no user attribute, so no tenancy"
                        + " value reaches the token.");
        assertTrue(
                mapperBlock.contains(MULTIVALUED_BINDING),
                "The mapper-creation loop in "
                        + SETUP_SCRIPT
                        + " must set "
                        + MULTIVALUED_BINDING
                        + ", but it does not. Without it Keycloak collapses a multivalued user attribute to a"
                        + " single value instead of emitting a JSON array, and multi-store callers silently"
                        + " lose scopes.");
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
        boolean updatesPolicy = Stream.of(script.split("\n"))
                .map(String::trim)
                .filter(line -> !line.startsWith("#"))
                .anyMatch(line -> line.startsWith(POLICY_UPDATE_COMMAND) && line.contains(setting));

        assertTrue(
                updatesPolicy,
                "The setup script "
                        + SETUP_SCRIPT
                        + " never runs a line that *starts with* `"
                        + POLICY_UPDATE_COMMAND
                        + "` and carries "
                        + setting
                        + ". The realm's declarative user profile has unmanaged attributes disabled, so the"
                        + " tenancy attributes the mappers read are silently DISCARDED: the admin REST API still"
                        + " answers 204, the attribute reads back as null, no claim reaches the token, and every"
                        + " scoped caller is denied. No Java test can see it, because it happens inside Keycloak."
                        + " The assignment is required on the update command itself: a read-back or diagnostic"
                        + " line that merely mentions "
                        + setting
                        + " does not prove the value reaches the command. Set it to "
                        + UNMANAGED_ATTRIBUTE_POLICY
                        + " (never ENABLED: the administrator endpoints are the write path, and a subject must"
                        + " not be able to assign itself a tenancy).");
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

    /**
     * The tenancy mapper loop: from the {@code for claim in "${TENANCY_CLAIMS[@]}"} header to its
     * terminating {@code done}.
     *
     * <p>Bounded to the loop so text elsewhere in the script (for example a comment describing the
     * mappers, or a helper used by them) cannot satisfy the assertions above. "Everything after the
     * claims array" was too broad: it accepted a binding that had drifted out of the loop.</p>
     */
    private static String mapperCreationBlock(String script) {
        int start = script.indexOf(MAPPER_LOOP_HEADER);
        assertTrue(
                start >= 0,
                "The setup script "
                        + SETUP_SCRIPT
                        + " no longer contains the mapper loop header "
                        + MAPPER_LOOP_HEADER
                        + " this test bounds its assertions to. Restore the loop shape, or update this test's"
                        + " bound, so the claim-to-mapper binding stays pinned.");
        int end = script.indexOf(MAPPER_LOOP_TERMINATOR, start);
        assertTrue(
                end > start,
                "The mapper loop starting at offset "
                        + start
                        + " in "
                        + SETUP_SCRIPT
                        + " has no terminating line starting with 'done', so this test cannot bound the block it"
                        + " asserts on.");
        return script.substring(start, end);
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
