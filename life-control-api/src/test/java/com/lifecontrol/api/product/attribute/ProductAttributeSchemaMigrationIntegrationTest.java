package com.lifecontrol.api.product.attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.product.category.catalog.StarterProductCategoryCatalog;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence-level verification of {@code V37__product_attribute_catalog.sql} and
 * {@code V38__product_attribute_starter_catalog.sql} against real PostgreSQL with Flyway enabled and
 * {@code ddl-auto=validate}.
 *
 * <p>The service layer cannot prove any of these guarantees: the closed kind and level vocabularies, the
 * code format, the "a unit belongs to a measurement" rule and both COALESCE expression indexes are
 * enforced by the database alone, and the service only mirrors them. That split is the whole point of
 * both migration headers. The indexes are asserted explicitly because a plain
 * {@code UNIQUE (company_id, profile_key)} would satisfy every service test while allowing two
 * <em>global</em> profiles to claim {@code calzado}: {@code NULL <> NULL} in SQL, so the duplicate only
 * appears once the {@code COALESCE} turns the global owner into a value.</p>
 *
 * <p>V38 is data, and data written by a migration that ran once on an empty database is assumed rather
 * than proven. Its half is asserted here in two directions: the seed's own invariants as they stand, and
 * the idempotency of the shipped script — replayed from the classpath inside a rolled-back transaction,
 * the same technique {@code StarterProductCategoryMigrationIntegrationTest} uses for V35 — because the
 * guards that make a re-run harmless are the ones a restored dump exercises.</p>
 *
 * <p>Every row this suite writes carries the {@code pasm} prefix and is removed before each method, so
 * the seed assertions read the migration's rows and nothing else.</p>
 */
@SpringBootTest
@DisplayName("Product Attribute Schema Integration Tests")
class ProductAttributeSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SEED_RESOURCE = "db/migration/V38__product_attribute_starter_catalog.sql";
    private static final String PROBE_PREFIX = "pasm";
    private static final String PROBE_PROFILE_KEY = "pasm_probe";
    private static final String OTHER_PROBE_PROFILE_KEY = "pasm_otro";
    private static final String PROBE_ROOT = "pasm_raiz";

    private static final String COMPANY_KEY = "PRODUCT-ATTRIBUTE-SCHEMA-KEY";
    private static final String COMPANY_RFC = "PASA010101ABC";

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CompanyRepository companyRepository;

    @BeforeEach
    void removeProbeRows() {
        // The container is shared per JVM: a probe row surviving a failed assertion would make the
        // next method's counts and unique indexes disagree with what that method inserted. Deleted
        // leaf-first because every child row references a profile, and only rows this suite owns: the
        // seeded catalog is never touched, it is the thing under test.
        var probeProfiles = "SELECT id FROM product_attribute_profiles WHERE profile_key LIKE '" + PROBE_PREFIX + "%'";
        jdbcTemplate.update("DELETE FROM product_attribute_values WHERE definition_id IN "
                + "(SELECT d.id FROM product_attribute_definitions d WHERE d.profile_id IN ("
                + probeProfiles + "))");
        jdbcTemplate.update("DELETE FROM product_attribute_definitions WHERE profile_id IN (" + probeProfiles + ")");
        jdbcTemplate.update("DELETE FROM product_attribute_profile_roots WHERE profile_id IN (" + probeProfiles
                + ") OR lower(root_name) LIKE '" + PROBE_PREFIX + "%'");
        jdbcTemplate.update("DELETE FROM product_attribute_profiles WHERE profile_key LIKE '" + PROBE_PREFIX + "%'");
    }

    // ──────────────────────────────────────
    // V37 — structure
    // ──────────────────────────────────────

    @Nested
    @DisplayName("V37 — structure")
    class StructureTests {

        @Test
        @DisplayName("applies every migration on the classpath and leaves no pending migration")
        void flywayHeadIsTheLatestMigration() {
            assertThat(flyway.info().current()).isNotNull();
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(latestMigrationVersion());
            assertThat(flyway.info().pending()).isEmpty();
        }

        @Test
        @DisplayName("creates the four catalog tables")
        void createsTheFourCatalogTables() {
            for (var table : List.of(
                    "product_attribute_profiles",
                    "product_attribute_definitions",
                    "product_attribute_values",
                    "product_attribute_profile_roots")) {
                assertThat(jdbcTemplate.queryForObject("""
                                SELECT COUNT(*) FROM information_schema.tables
                                WHERE table_schema = 'public' AND table_name = ?
                                """, Integer.class, table))
                        .as("%s exists", table)
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("adds a nullable jsonb product_variants.attributes column")
        void addsTheVariantAttributesColumn() {
            var column = jdbcTemplate.queryForMap("""
                    SELECT data_type, is_nullable FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'product_variants'
                      AND column_name = 'attributes'
                    """);

            assertThat(column.get("data_type"))
                    .as("the variant carries the same jsonb shape the product does")
                    .isEqualTo("jsonb");
            assertThat(column.get("is_nullable"))
                    .as("a variant created before this migration keeps a legal row")
                    .isEqualTo("YES");
        }
    }

    // ──────────────────────────────────────
    // V37 — the closed vocabularies and the code format
    // ──────────────────────────────────────

    @Nested
    @DisplayName("V37 — definitions")
    class DefinitionConstraintTests {

        @Test
        @DisplayName("ck_product_attribute_definitions_kind rejects a kind outside the closed set")
        void unknownKindIsRejected() {
            var profileId = insertGlobalProfile(PROBE_PROFILE_KEY);

            assertViolatesConstraint("ck_product_attribute_definitions_kind", """
                    INSERT INTO product_attribute_definitions
                        (id, profile_id, attribute_code, label, attribute_level, kind)
                    VALUES (?, ?, 'sonda_kind', 'Sonda Kind', 'PRODUCT', 'ENUMX')
                    """, UUID.randomUUID(), profileId);

            assertThat(countDefinitions(profileId))
                    .as("the rejected row is not stored")
                    .isZero();
        }

        @Test
        @DisplayName("ck_product_attribute_definitions_level rejects a level outside the closed set")
        void unknownLevelIsRejected() {
            var profileId = insertGlobalProfile(PROBE_PROFILE_KEY);

            assertViolatesConstraint("ck_product_attribute_definitions_level", """
                    INSERT INTO product_attribute_definitions
                        (id, profile_id, attribute_code, label, attribute_level, kind)
                    VALUES (?, ?, 'sonda_level', 'Sonda Level', 'PRODUCTS', 'TEXT')
                    """, UUID.randomUUID(), profileId);

            assertThat(countDefinitions(profileId)).isZero();
        }

        @Test
        @DisplayName("ck_product_attribute_definitions_code_format rejects a code with a space or a leading digit")
        void malformedCodeIsRejected() {
            var profileId = insertGlobalProfile(PROBE_PROFILE_KEY);

            // The code is the JSON key, so it has to be addressable from a report query without
            // quoting games. Both of these are the labels an operator would type, not a key.
            for (var code : List.of("Material Externo", "1bad")) {
                assertViolatesConstraint(
                        "ck_product_attribute_definitions_code_format", """
                        INSERT INTO product_attribute_definitions
                            (id, profile_id, attribute_code, label, attribute_level, kind)
                        VALUES (?, ?, ?, 'Sonda Code', 'PRODUCT', 'TEXT')
                        """, UUID.randomUUID(), profileId, code);
            }

            assertThat(countDefinitions(profileId)).isZero();
        }

        @Test
        @DisplayName("ck_product_attribute_definitions_code_format accepts a lower-case snake_case code")
        void legalCodeIsAccepted() {
            var profileId = insertGlobalProfile(PROBE_PROFILE_KEY);

            var inserted = insertDefinition(profileId, "material_externo", "PRODUCT", "TEXT", null);

            assertThat(inserted).as("the format check is not over-strict").isEqualTo(1);
            assertThat(countDefinitions(profileId)).isEqualTo(1);
        }

        @Test
        @DisplayName("ck_product_attribute_definitions_unit_is_measurement keeps a unit on measurements only")
        void unitOnANonMeasurementIsRejected() {
            var profileId = insertGlobalProfile(PROBE_PROFILE_KEY);

            // A unit on a TEXT attribute is a unit nobody validates: the column exists for the hint a
            // measurement input prints, and every other kind has nothing to print it beside.
            assertViolatesConstraint(
                    "ck_product_attribute_definitions_unit_is_measurement", """
                    INSERT INTO product_attribute_definitions
                        (id, profile_id, attribute_code, label, attribute_level, kind, unit_label)
                    VALUES (?, ?, 'sonda_texto', 'Sonda Texto', 'PRODUCT', 'TEXT', 'cm')
                    """, UUID.randomUUID(), profileId);

            assertThat(countDefinitions(profileId)).isZero();
            assertThat(insertDefinition(profileId, "sonda_medida", "PRODUCT", "MEASUREMENT", "cm"))
                    .as("a measurement keeps its hint")
                    .isEqualTo(1);
            assertThat(insertDefinition(profileId, "sonda_libre", "PRODUCT", "TEXT", null))
                    .as("a kind with no unit stores none")
                    .isEqualTo(1);
            assertThat(countDefinitions(profileId)).isEqualTo(2);
        }

        @Test
        @DisplayName("uq_product_attribute_definitions_profile_code rejects one code twice in a profile")
        void duplicateCodeInAProfileIsRejected() {
            var profileId = insertGlobalProfile(PROBE_PROFILE_KEY);
            insertDefinition(profileId, "sonda_codigo", "PRODUCT", "TEXT", null);

            assertViolatesConstraint(
                    "uq_product_attribute_definitions_profile_code", """
                    INSERT INTO product_attribute_definitions
                        (id, profile_id, attribute_code, label, attribute_level, kind)
                    VALUES (?, ?, 'sonda_codigo', 'Sonda Duplicada', 'PRODUCT', 'TEXT')
                    """, UUID.randomUUID(), profileId);

            assertThat(countDefinitions(profileId)).isEqualTo(1);
        }

        @Test
        @DisplayName("the same code in two profiles is accepted: a code is unique per profile only")
        void sameCodeInTwoProfilesIsAccepted() {
            var firstProfileId = insertGlobalProfile(PROBE_PROFILE_KEY);
            var secondProfileId = insertGlobalProfile(OTHER_PROBE_PROFILE_KEY);

            assertThat(insertDefinition(firstProfileId, "sonda_codigo", "PRODUCT", "TEXT", null))
                    .isEqualTo(1);
            assertThat(insertDefinition(secondProfileId, "sonda_codigo", "PRODUCT", "TEXT", null))
                    .as("color is the same idea in calzado and in electro, each with its own palette")
                    .isEqualTo(1);
        }
    }

    // ──────────────────────────────────────
    // V37 — the two COALESCE uniqueness guards
    // ──────────────────────────────────────

    @Nested
    @DisplayName("V37 — COALESCE uniqueness")
    class OwnerUniquenessTests {

        @Test
        @DisplayName("uq_product_attribute_profiles_owner_key rejects two GLOBAL profiles with the same key")
        void duplicateGlobalProfileKeyIsRejected() {
            insertGlobalProfile(PROBE_PROFILE_KEY);

            // The NULL trap: both rows have company_id NULL, and NULL <> NULL, so a plain
            // UNIQUE (company_id, profile_key) would admit both and two global profiles would claim
            // the same key. The index compares lower(profile_key), which is what the second insert
            // below also proves.
            assertViolatesConstraint(
                    "uq_product_attribute_profiles_owner_key", """
                    INSERT INTO product_attribute_profiles (id, company_id, profile_key, label)
                    VALUES (?, NULL, ?, 'Sonda Duplicada')
                    """, UUID.randomUUID(), PROBE_PROFILE_KEY);
            assertViolatesConstraint(
                    "uq_product_attribute_profiles_owner_key", """
                    INSERT INTO product_attribute_profiles (id, company_id, profile_key, label)
                    VALUES (?, NULL, ?, 'Sonda Mayúsculas')
                    """, UUID.randomUUID(), PROBE_PROFILE_KEY.toUpperCase());

            assertThat(countProfiles(PROBE_PROFILE_KEY, null)).isEqualTo(1);
        }

        @Test
        @DisplayName("a company may own the key a global profile already owns: that is the override seam")
        void companyProfileMayReuseAGlobalKey() {
            var companyId = companyId();
            insertGlobalProfile(PROBE_PROFILE_KEY);

            insertCompanyProfile(PROBE_PROFILE_KEY, companyId);

            assertThat(countProfiles(PROBE_PROFILE_KEY, companyId))
                    .as("the owner is part of the key, so the global row is not in the way")
                    .isEqualTo(1);
            assertThat(countProfiles(PROBE_PROFILE_KEY, null))
                    .as("and the global row is still there")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("uq_product_attribute_profile_roots_owner_root rejects two global rows naming the same root")
        void duplicateGlobalRootAttachmentIsRejected() {
            var profileId = insertGlobalProfile(PROBE_PROFILE_KEY);
            insertGlobalRootAttachment(profileId, PROBE_ROOT);

            // Same COALESCE trap as the profile key, and this one decides which profile a whole branch
            // resolves: two global rows for one root would make the answer depend on row order.
            assertViolatesConstraint(
                    "uq_product_attribute_profile_roots_owner_root", """
                    INSERT INTO product_attribute_profile_roots (id, profile_id, company_id, root_name)
                    VALUES (?, ?, NULL, ?)
                    """, UUID.randomUUID(), profileId, PROBE_ROOT);
            assertViolatesConstraint(
                    "uq_product_attribute_profile_roots_owner_root",
                    """
                    INSERT INTO product_attribute_profile_roots (id, profile_id, company_id, root_name)
                    VALUES (?, ?, NULL, ?)
                    """,
                    UUID.randomUUID(),
                    profileId,
                    PROBE_ROOT.toUpperCase());

            assertThat(countGlobalRootAttachments(PROBE_ROOT)).isEqualTo(1);
        }

        @Test
        @DisplayName("a company row and a global row may name the same root: the company row wins")
        void companyRootAttachmentMayShadowTheGlobalOne() {
            var companyId = companyId();
            var globalProfileId = insertGlobalProfile(PROBE_PROFILE_KEY);
            var companyProfileId = insertCompanyProfile(OTHER_PROBE_PROFILE_KEY, companyId);
            insertGlobalRootAttachment(globalProfileId, PROBE_ROOT);

            var inserted = jdbcTemplate.update("""
                    INSERT INTO product_attribute_profile_roots (id, profile_id, company_id, root_name)
                    VALUES (?, ?, ?, ?)
                    """, UUID.randomUUID(), companyProfileId, companyId, PROBE_ROOT);

            assertThat(inserted)
                    .as("the override the schema ships is expressible")
                    .isEqualTo(1);
            assertThat(countGlobalRootAttachments(PROBE_ROOT)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("""
                            SELECT COUNT(*) FROM product_attribute_profile_roots
                            WHERE company_id = ? AND lower(root_name) = lower(?)
                            """, Integer.class, companyId, PROBE_ROOT))
                    .isEqualTo(1);
        }
    }

    // ──────────────────────────────────────
    // V38 — the seed, as data
    // ──────────────────────────────────────

    @Nested
    @DisplayName("V38 — the starter catalog")
    class SeedTests {

        @Test
        @DisplayName("seeds exactly the four global profiles the catalog names")
        void seedsFourGlobalProfiles() {
            var keys = jdbcTemplate.queryForList("""
                    SELECT profile_key FROM product_attribute_profiles
                    WHERE company_id IS NULL
                    ORDER BY profile_key
                    """, String.class);

            assertThat(keys).containsExactly("abarrotes", "calzado", "electro", "ferreteria");
        }

        @Test
        @DisplayName("every seeded profile carries at least five enabled definitions")
        void everySeededProfileCarriesAtLeastFiveEnabledDefinitions() {
            var counts = new LinkedHashMap<String, Integer>();
            jdbcTemplate
                    .queryForList("""
                            SELECT p.profile_key AS profile_key, COUNT(d.id) AS enabled_definitions
                            FROM product_attribute_profiles p
                            JOIN product_attribute_definitions d ON d.profile_id = p.id AND d.enabled
                            WHERE p.company_id IS NULL
                            GROUP BY p.profile_key
                            """)
                    .forEach(row -> counts.put(
                            (String) row.get("profile_key"), ((Number) row.get("enabled_definitions")).intValue()));

            assertThat(counts).containsOnlyKeys("calzado", "ferreteria", "electro", "abarrotes");
            assertThat(counts.values()).allSatisfy(count -> assertThat(count).isGreaterThanOrEqualTo(5));
        }

        @Test
        @DisplayName("every global attachment names a root the starter category catalog declares enabled")
        void globalAttachmentsNameEnabledStarterRoots() {
            // The names are read from the catalog's public API instead of being re-typed here: the
            // attachment matches by NAME, so a root the category catalog does not seed enabled (or
            // renames) is an attachment that can never resolve, and re-typing the eleven literals would
            // hide exactly that drift.
            var enabledRootNames = StarterProductCategoryCatalog.roots().stream()
                    .filter(StarterProductCategoryCatalog.Entry::enabled)
                    .map(StarterProductCategoryCatalog.Entry::name)
                    .toList();
            var attachedRoots = jdbcTemplate.queryForList(
                    "SELECT root_name FROM product_attribute_profile_roots WHERE company_id IS NULL", String.class);

            assertThat(attachedRoots).isNotEmpty();
            assertThat(attachedRoots)
                    .allSatisfy(rootName -> assertThat(enabledRootNames)
                            .as("%s names a root the category catalog seeds enabled", rootName)
                            .anyMatch(declared -> declared.equalsIgnoreCase(rootName)));
        }

        @Test
        @DisplayName("each seeded profile is attached to at least one root")
        void everySeededProfileIsReachable() {
            // A profile with no root is unreachable: the resolution starts at a category and arrives at
            // the profile through the attachment, so nothing else can find it.
            var attachedProfileKeys = jdbcTemplate.queryForList("""
                    SELECT DISTINCT p.profile_key FROM product_attribute_profile_roots r
                    JOIN product_attribute_profiles p ON p.id = r.profile_id
                    WHERE r.company_id IS NULL
                    """, String.class);

            assertThat(attachedProfileKeys).containsExactlyInAnyOrder("calzado", "ferreteria", "electro", "abarrotes");
        }

        @Test
        @DisplayName("enumerable definitions carry at least two enabled values and the other kinds none")
        void seededValuesMatchTheirKind() {
            var rows = jdbcTemplate.queryForList("""
                    SELECT d.attribute_code AS attribute_code, d.kind AS kind, COUNT(v.id) AS enabled_values
                    FROM product_attribute_definitions d
                    JOIN product_attribute_profiles p ON p.id = d.profile_id AND p.company_id IS NULL
                    LEFT JOIN product_attribute_values v ON v.definition_id = d.id AND v.enabled
                    GROUP BY d.id, d.attribute_code, d.kind
                    """);

            assertThat(rows).isNotEmpty();
            assertThat(rows).allSatisfy(row -> {
                var kind = (String) row.get("kind");
                var enabledValues = ((Number) row.get("enabled_values")).intValue();
                if ("ENUM".equals(kind) || "MULTI_ENUM".equals(kind)) {
                    assertThat(enabledValues)
                            .as("%s offers a choice set", row.get("attribute_code"))
                            .isGreaterThanOrEqualTo(2);
                } else {
                    assertThat(enabledValues)
                            .as("%s has no allowed set to offer", row.get("attribute_code"))
                            .isZero();
                }
            });
        }

        @Test
        @DisplayName("the seed leaves no duplicate profile key, code, value or root name")
        void seedLeavesNoDuplicateKeys() {
            // These are the invariants V38's four NOT EXISTS guards protect. They hold after a single
            // run of the script either way; what they add is the assertion that the guards and the
            // uniqueness indexes agree, so a re-run cannot insert a row the index would refuse.
            assertThat(duplicates("""
                    SELECT lower(profile_key) FROM product_attribute_profiles
                    WHERE company_id IS NULL GROUP BY 1 HAVING COUNT(*) > 1
                    """)).as("profile keys").isEmpty();
            assertThat(duplicates("""
                    SELECT profile_id || '/' || attribute_code FROM product_attribute_definitions
                    GROUP BY profile_id, attribute_code HAVING COUNT(*) > 1
                    """)).as("codes per profile").isEmpty();
            assertThat(duplicates("""
                    SELECT definition_id || '/' || value_code FROM product_attribute_values
                    GROUP BY definition_id, value_code HAVING COUNT(*) > 1
                    """)).as("values per definition").isEmpty();
            assertThat(duplicates("""
                    SELECT lower(root_name) FROM product_attribute_profile_roots
                    WHERE company_id IS NULL GROUP BY 1 HAVING COUNT(*) > 1
                    """)).as("root names per global owner").isEmpty();
        }

        @Test
        @Transactional
        @DisplayName("replaying the shipped V38 script inserts nothing")
        void replayingTheSeedIsANoOp() {
            // The guards exist for a restored dump or a manual re-run, and neither is reachable from a
            // test. The script is replayed from the classpath inside this method's transaction, so the
            // technique pins the file that ships and the rollback leaves the shared container as it
            // was. A surviving temporary table would also abort the second CREATE TEMPORARY TABLE, so a
            // green run proves the script cleans up after itself.
            var before = seedRowCounts();

            var connection = DataSourceUtils.getConnection(dataSource);
            try {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(SEED_RESOURCE));
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }

            assertThat(seedRowCounts())
                    .as("a second pass adds nothing on any of the four levels")
                    .isEqualTo(before);
        }

        private Map<String, Integer> seedRowCounts() {
            return Map.of(
                    "profiles", countTable("product_attribute_profiles"),
                    "definitions", countTable("product_attribute_definitions"),
                    "values", countTable("product_attribute_values"),
                    "roots", countTable("product_attribute_profile_roots"));
        }
    }

    // ──────────────────────────────────────
    // fixtures
    // ──────────────────────────────────────

    private UUID insertGlobalProfile(String profileKey) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO product_attribute_profiles (id, company_id, profile_key, label)
                VALUES (?, NULL, ?, ?)
                """, id, profileKey, "Sonda " + profileKey);
        return id;
    }

    private UUID insertCompanyProfile(String profileKey, UUID companyId) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO product_attribute_profiles (id, company_id, profile_key, label)
                VALUES (?, ?, ?, ?)
                """, id, companyId, profileKey, "Sonda " + profileKey);
        return id;
    }

    private int insertDefinition(UUID profileId, String code, String level, String kind, String unitLabel) {
        return jdbcTemplate.update("""
                INSERT INTO product_attribute_definitions
                    (id, profile_id, attribute_code, label, attribute_level, kind, unit_label)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), profileId, code, "Sonda " + code, level, kind, unitLabel);
    }

    private int insertGlobalRootAttachment(UUID profileId, String rootName) {
        return jdbcTemplate.update("""
                INSERT INTO product_attribute_profile_roots (id, profile_id, company_id, root_name)
                VALUES (?, ?, NULL, ?)
                """, UUID.randomUUID(), profileId, rootName);
    }

    private int countDefinitions(UUID profileId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_attribute_definitions WHERE profile_id = ?", Integer.class, profileId);
    }

    private int countProfiles(String profileKey, UUID companyId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM product_attribute_profiles
                WHERE lower(profile_key) = lower(?) AND company_id IS NOT DISTINCT FROM ?
                """, Integer.class, profileKey, companyId);
    }

    private int countGlobalRootAttachments(String rootName) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM product_attribute_profile_roots
                WHERE company_id IS NULL AND lower(root_name) = lower(?)
                """, Integer.class, rootName);
    }

    private List<String> duplicates(String sql) {
        return jdbcTemplate.queryForList(sql, String.class);
    }

    private int countTable(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /** Find-or-create the company the override rows hang off, the same fixture style as the siblings. */
    private UUID companyId() {
        return TenantFixtures.findOrCreateAndFlush(
                        companyRepository, COMPANY_KEY, "Product Attribute Schema Test Company", COMPANY_RFC)
                .getId();
    }

    /**
     * Asserts the raw write is rejected by a real PostgreSQL constraint and that the reported error
     * names the exact constraint, so a rejection for an unrelated reason cannot pass as this one.
     */
    private void assertViolatesConstraint(String constraintName, String sql, Object... args) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql, args))
                .as("write rejected by %s", constraintName)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(thrown -> assertThat(((DataIntegrityViolationException) thrown)
                                .getMostSpecificCause()
                                .getMessage())
                        .as("PostgreSQL reports the violated constraint")
                        .contains(constraintName));
    }
}
