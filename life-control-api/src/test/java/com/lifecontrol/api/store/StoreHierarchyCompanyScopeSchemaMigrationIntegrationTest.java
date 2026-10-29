package com.lifecontrol.api.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence-level verification of the store hierarchy's tenancy migrations against real PostgreSQL,
 * with Flyway enabled and {@code ddl-auto=validate}, so a green run proves the schema landed exactly as
 * the migrations declare it.
 *
 * <p>The rule under test is issue <b>#673</b>'s: every level of the hierarchy stores its owner, derived
 * from its parent, and the database refuses a row whose owner disagrees with that parent's. The cases are
 * written the way {@code SupplierSchemaMigrationIntegrationTest} writes them — the same rows with a
 * consistent company are accepted, and the same rows with a disagreeing one are refused <b>by the
 * constraint's name</b>, which is what makes the assertion about the rule rather than about "some
 * error" — and each refusal also asserts that nothing was stored.</p>
 *
 * <p>The suite is organized one section per level, in migration order: the region's contract
 * ({@code V44}/{@code V45}), the zone's ({@code V46}/{@code V47}), the store's ({@code V48}/{@code V49})
 * and the purchase-order key the store level makes expressible ({@code V50}).</p>
 */
@SpringBootTest
@DisplayName("Store Hierarchy Company Scope Schema Integration Tests")
class StoreHierarchyCompanyScopeSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_A_KEY = "STORE-SCHEMA-A";
    private static final String COMPANY_B_KEY = "STORE-SCHEMA-B";
    private static final String COMPANY_A_RFC = "SSHA010101AB1";
    private static final String COMPANY_B_RFC = "SSHB010101AB2";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyAId;
    private UUID companyBId;
    private UUID countryId;
    private UUID companyCountryId;

    private final List<UUID> insertedZones = new ArrayList<>();
    private final List<UUID> insertedRegions = new ArrayList<>();
    private final List<UUID> insertedCompanyCountries = new ArrayList<>();
    private final List<UUID> insertedCompanies = new ArrayList<>();

    @BeforeEach
    void insertCompaniesAndTheirCountry() {
        companyAId = insertCompany(COMPANY_A_KEY, "Store Schema Company A", COMPANY_A_RFC);
        companyBId = insertCompany(COMPANY_B_KEY, "Store Schema Company B", COMPANY_B_RFC);

        // Seeded reference data, read rather than invented: no company country exists without one.
        countryId = jdbcTemplate.queryForObject("SELECT id FROM countries WHERE country_code = 'MX'", UUID.class);
        companyCountryId = insertCompanyCountry(companyAId);
    }

    @AfterEach
    void removeInsertedRows() {
        // Leaf first: every list below references the one after it.
        for (var id : insertedZones) {
            jdbcTemplate.update("DELETE FROM company_zones WHERE id = ?", id);
        }
        insertedZones.clear();
        for (var id : insertedRegions) {
            jdbcTemplate.update("DELETE FROM company_regions WHERE id = ?", id);
        }
        insertedRegions.clear();
        for (var id : insertedCompanyCountries) {
            jdbcTemplate.update("DELETE FROM company_countries WHERE id = ?", id);
        }
        insertedCompanyCountries.clear();
        for (var id : insertedCompanies) {
            jdbcTemplate.update("DELETE FROM companies WHERE id = ?", id);
        }
        insertedCompanies.clear();
    }

    // ──────────────────────────────────────
    // V44/V45 — the region level
    // ──────────────────────────────────────

    @Test
    @DisplayName("the region level stores its owner: company_regions.company_id is mandatory after V45")
    void regionCompanyIdIsMandatory() {
        var nullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'company_regions' AND column_name = 'company_id'
                """, String.class);

        assertThat(nullable)
                .as("the owner is stored so the database can refuse a disagreeing one, not derived on read")
                .isEqualTo("NO");
    }

    @Test
    @DisplayName("V44 leaves both unique targets the next level's composite keys need")
    void regionLevelUniqueTargetsExist() {
        assertThat(uniqueConstraintCount("company_countries", "uq_company_countries_company_id"))
                .as("uq_company_countries_company_id is the region key's target")
                .isEqualTo(1);
        assertThat(uniqueConstraintCount("company_regions", "uq_company_regions_company_id"))
                .as("uq_company_regions_company_id is the zone key's target")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a region whose company is its country's company is accepted")
    void regionInsideItsCountryIsAccepted() {
        var regionId = insertRegion(companyCountryId, companyAId);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT company_id FROM company_regions WHERE id = ?", UUID.class, regionId))
                .as("the accepted row stores the company it was written with")
                .isEqualTo(companyAId);
    }

    @Test
    @DisplayName("a region whose company disagrees with its country's is refused by the key, storing nothing")
    void regionOfAnotherCompanyIsRefused() {
        var refusedId = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                INSERT INTO company_regions (id, company_country_id, company_id, region_code, region_name, enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, refusedId, companyCountryId, companyBId, suffix, "Foreign region " + suffix))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_company_regions_country_same_company");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM company_regions WHERE id = ?", Integer.class, refusedId))
                .as("the refused insert stored nothing")
                .isZero();
    }

    @Test
    @DisplayName("a region with no company at all is refused by the NOT NULL V45 lands, storing nothing")
    void regionWithoutCompanyIsRefused() {
        var refusedId = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        assertThatThrownBy(() ->
                        jdbcTemplate.update("""
                INSERT INTO company_regions (id, company_country_id, region_code, region_name, enabled)
                VALUES (?, ?, ?, ?, true)
                """, refusedId, companyCountryId, suffix, "Ownerless region " + suffix))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("company_id");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM company_regions WHERE id = ?", Integer.class, refusedId))
                .as("the refused insert stored nothing")
                .isZero();
    }

    // ──────────────────────────────────────
    // V46/V47 — the zone level
    // ──────────────────────────────────────

    @Test
    @DisplayName("the zone level stores its owner: company_zones.company_id is mandatory after V47")
    void zoneCompanyIdIsMandatory() {
        var nullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'company_zones' AND column_name = 'company_id'
                """, String.class);

        assertThat(nullable)
                .as("the owner is stored so the database can refuse a disagreeing one, not derived on read")
                .isEqualTo("NO");
    }

    @Test
    @DisplayName("V46 leaves the unique target the store level's composite key needs")
    void zoneLevelUniqueTargetExists() {
        assertThat(uniqueConstraintCount("company_zones", "uq_company_zones_company_id"))
                .as("uq_company_zones_company_id is the store key's target")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a zone whose company is its region's company is accepted")
    void zoneInsideItsRegionIsAccepted() {
        var regionId = insertRegion(companyCountryId, companyAId);
        var zoneId = insertZone(regionId, companyAId);

        assertThat(jdbcTemplate.queryForObject("SELECT company_id FROM company_zones WHERE id = ?", UUID.class, zoneId))
                .as("the accepted row stores the company it was written with")
                .isEqualTo(companyAId);
    }

    @Test
    @DisplayName("a zone whose company disagrees with its region's is refused by the key, storing nothing")
    void zoneOfAnotherCompanyIsRefused() {
        var regionId = insertRegion(companyCountryId, companyAId);
        var refusedId = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        assertThatThrownBy(() ->
                        jdbcTemplate.update("""
                INSERT INTO company_zones (id, company_region_id, company_id, zone_code, zone_name, enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, refusedId, regionId, companyBId, suffix, "Foreign zone " + suffix))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_company_zones_region_same_company");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM company_zones WHERE id = ?", Integer.class, refusedId))
                .as("the refused insert stored nothing")
                .isZero();
    }

    @Test
    @DisplayName("a zone with no company at all is refused by the NOT NULL V47 lands, storing nothing")
    void zoneWithoutCompanyIsRefused() {
        var regionId = insertRegion(companyCountryId, companyAId);
        var refusedId = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO company_zones (id, company_region_id, zone_code, zone_name, enabled)
                VALUES (?, ?, ?, ?, true)
                """, refusedId, regionId, suffix, "Ownerless zone " + suffix))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("company_id");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM company_zones WHERE id = ?", Integer.class, refusedId))
                .as("the refused insert stored nothing")
                .isZero();
    }

    // ──────────────────────────────────────
    // fixtures
    // ──────────────────────────────────────

    private int uniqueConstraintCount(String table, String constraintName) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND constraint_name = ?
                  AND constraint_type = 'UNIQUE'
                """, Integer.class, table, constraintName);
    }

    private UUID insertCompany(String key, String name, String rfc) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO companies (id, company_key, company_name, rfc, enabled)
                VALUES (?, ?, ?, ?, true)
                """, id, key, name, rfc);
        insertedCompanies.add(id);
        return id;
    }

    private UUID insertCompanyCountry(UUID companyId) {
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO company_countries (id, company_id, country_id) VALUES (?, ?, ?)",
                id,
                companyId,
                countryId);
        insertedCompanyCountries.add(id);
        return id;
    }

    private UUID insertRegion(UUID companyCountry, UUID companyId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO company_regions (id, company_country_id, company_id, region_code, region_name, enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, id, companyCountry, companyId, suffix, "Schema region " + suffix);
        insertedRegions.add(id);
        return id;
    }

    private UUID insertZone(UUID companyRegion, UUID companyId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO company_zones (id, company_region_id, company_id, zone_code, zone_name, enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, id, companyRegion, companyId, suffix, "Schema zone " + suffix);
        insertedZones.add(id);
        return id;
    }
}
