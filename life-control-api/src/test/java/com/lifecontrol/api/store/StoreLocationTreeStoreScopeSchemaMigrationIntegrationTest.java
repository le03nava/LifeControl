package com.lifecontrol.api.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
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
 * Persistence-level verification of ADR-0001's D1.a against real PostgreSQL, with Flyway enabled and
 * {@code ddl-auto=validate}, so a green run proves the migration landed exactly as it declares.
 *
 * <p>The rule under test is the one {@code V44}–{@code V50} stopped one level short of: every level of the
 * store location tree stores the store it belongs to, derives it from its parent when the row arrives
 * without one, and is refused by name when the value it names disagrees with that parent's. The two levels
 * this suite covers are the last two — {@code store_zones} ({@code V6}) and {@code store_locations}
 * ({@code V7}) — which carried no store at all, so {@code stock_movement}'s
 * {@code (company_store_id, store_location_id)} key had nothing to reference.</p>
 *
 * <p>Written the way its sibling {@code StoreHierarchyCompanyScopeSchemaMigrationIntegrationTest} is: the
 * same row with a consistent store is accepted, the same row with a disagreeing one is refused <b>by the
 * constraint's name</b> — which is what makes the assertion about the rule and not about "some error" —
 * and every refusal also asserts that nothing was stored.</p>
 */
@SpringBootTest
@DisplayName("Store Location Tree Store Scope Schema Integration Tests")
class StoreLocationTreeStoreScopeSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_A_KEY = "LOC-TREE-A";
    private static final String COMPANY_B_KEY = "LOC-TREE-B";
    private static final String COMPANY_A_RFC = "LTA010101AB1";
    private static final String COMPANY_B_RFC = "LTB010101AB2";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyAId;
    private UUID companyBId;
    private UUID countryId;
    private UUID companyCountryId;

    private final List<InsertedRow> insertedLocations = new ArrayList<>();
    private final List<InsertedRow> insertedStoreZones = new ArrayList<>();
    private final List<InsertedRow> insertedAreas = new ArrayList<>();
    private final List<InsertedRow> insertedStores = new ArrayList<>();
    private final List<InsertedRow> insertedCompanyZones = new ArrayList<>();
    private final List<InsertedRow> insertedRegions = new ArrayList<>();
    private final List<InsertedRow> insertedCompanyCountries = new ArrayList<>();
    private final List<InsertedRow> insertedCompanies = new ArrayList<>();

    @BeforeEach
    void insertCompaniesAndTheirCountry() {
        companyAId = insertCompany(COMPANY_A_KEY, "Location Tree Company A", COMPANY_A_RFC);
        companyBId = insertCompany(COMPANY_B_KEY, "Location Tree Company B", COMPANY_B_RFC);

        // Seeded reference data, read rather than invented: no company country exists without one.
        countryId = jdbcTemplate.queryForObject("SELECT id FROM countries WHERE country_code = 'MX'", UUID.class);
        companyCountryId = insertCompanyCountry(companyAId);
    }

    @AfterEach
    void removeInsertedRows() {
        // Leaf first: every list below references the one after it.
        for (var row : insertedLocations) {
            deleteRow(row, "DELETE FROM store_locations WHERE id = ?");
        }
        insertedLocations.clear();
        for (var row : insertedStoreZones) {
            deleteRow(row, "DELETE FROM store_zones WHERE id = ?");
        }
        insertedStoreZones.clear();
        for (var row : insertedAreas) {
            deleteRow(row, "DELETE FROM store_areas WHERE id = ?");
        }
        insertedAreas.clear();
        for (var row : insertedStores) {
            deleteRow(row, "DELETE FROM company_stores WHERE id = ?");
        }
        insertedStores.clear();
        for (var row : insertedCompanyZones) {
            deleteRow(row, "DELETE FROM company_zones WHERE id = ?");
        }
        insertedCompanyZones.clear();
        for (var row : insertedRegions) {
            deleteRow(row, "DELETE FROM company_regions WHERE id = ?");
        }
        insertedRegions.clear();
        for (var row : insertedCompanyCountries) {
            deleteRow(row, "DELETE FROM company_countries WHERE id = ?");
        }
        insertedCompanyCountries.clear();
        for (var row : insertedCompanies) {
            deleteRow(row, "DELETE FROM companies WHERE id = ?");
        }
        insertedCompanies.clear();
    }

    @Test
    @DisplayName("a zone that arrives with no store gets its area's, derived by V59's trigger")
    void zoneWithoutStoreIsDerivedFromItsArea() {
        var storeId = insertStoreOfCompanyA();
        var areaId = insertArea(storeId, companyAId);
        var zoneId = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        // No company_store_id in the column list: the trigger is the only author of this column.
        TenantFixtures.inTenant(
                companyAId, () -> jdbcTemplate.update("""
                INSERT INTO store_zones (id, store_area_id, zone_code, zone_name, enabled)
                VALUES (?, ?, ?, ?, true)
                """, zoneId, areaId, suffix, "Derived zone " + suffix));
        insertedStoreZones.add(new InsertedRow(companyAId, zoneId));

        assertThat(zoneStore(zoneId))
                .as("the zone's store is its area's, derived before the NOT NULL")
                .isEqualTo(storeId);
    }

    @Test
    @DisplayName("a location that arrives with no store gets its zone's, derived by V59's trigger")
    void locationWithoutStoreIsDerivedFromItsZone() {
        var storeId = insertStoreOfCompanyA();
        var areaId = insertArea(storeId, companyAId);
        var zoneId = insertZone(areaId, storeId, companyAId);
        var locationId = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        TenantFixtures.inTenant(
                companyAId, () -> jdbcTemplate.update("""
                INSERT INTO store_locations (id, store_zone_id, location_code, location_name, enabled)
                VALUES (?, ?, ?, ?, true)
                """, locationId, zoneId, suffix, "Derived location " + suffix));
        insertedLocations.add(new InsertedRow(companyAId, locationId));

        assertThat(locationStore(locationId))
                .as("the location's store is its zone's, derived before the NOT NULL")
                .isEqualTo(storeId);
    }

    @Test
    @DisplayName("a zone naming another store's id is refused by fk_store_zones_area_same_store")
    void zoneNamingAnotherStoreIsRefused() {
        var storeAId = insertStoreOfCompanyA();
        var areaAId = insertArea(storeAId, companyAId);
        // A second store of the same company: the refusal has to be about the store, not the tenant, so
        // both stores must belong to company A — a cross-tenant pair would be refused for the wrong reason.
        var storeBId = insertStoreOfCompanyA();
        var refusedId = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        assertThatThrownBy(() -> TenantFixtures.inTenant(
                        companyAId,
                        () -> jdbcTemplate.update("""
                INSERT INTO store_zones (id, store_area_id, company_store_id, zone_code, zone_name, enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, refusedId, areaAId, storeBId, suffix, "Foreign zone " + suffix)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_store_zones_area_same_store");

        assertThat(countZone(refusedId)).as("the refused insert stored nothing").isZero();
    }

    @Test
    @DisplayName("a location naming another store's id is refused by fk_store_locations_zone_same_store")
    void locationNamingAnotherStoreIsRefused() {
        var storeAId = insertStoreOfCompanyA();
        var areaAId = insertArea(storeAId, companyAId);
        var zoneAId = insertZone(areaAId, storeAId, companyAId);
        var storeBId = insertStoreOfCompanyA();
        var refusedId = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        assertThatThrownBy(() -> TenantFixtures.inTenant(
                        companyAId,
                        () -> jdbcTemplate.update(
                                """
                INSERT INTO store_locations (id, store_zone_id, company_store_id, location_code,
                                             location_name, enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, refusedId, zoneAId, storeBId, suffix, "Foreign location " + suffix)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_store_locations_zone_same_store");

        assertThat(countLocation(refusedId))
                .as("the refused insert stored nothing")
                .isZero();
    }

    @Test
    @DisplayName("a zone and a location naming their own store are accepted and read back")
    void zoneAndLocationNamingTheirOwnStoreAreAccepted() {
        var storeId = insertStoreOfCompanyA();
        var areaId = insertArea(storeId, companyAId);
        var zoneId = insertZone(areaId, storeId, companyAId);
        var locationId = insertLocation(zoneId, storeId, companyAId);

        assertThat(zoneStore(zoneId)).isEqualTo(storeId);
        assertThat(locationStore(locationId)).isEqualTo(storeId);
    }

    private UUID insertCompany(String key, String name, String rfc) {
        var id = TenantFixtures.insertCompany(jdbcTemplate, key, name, rfc, TenantFixtures.SEED_CURRENCY);
        insertedCompanies.add(new InsertedRow(id, id));
        return id;
    }

    private UUID insertCompanyCountry(UUID companyId) {
        var id = UUID.randomUUID();
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "INSERT INTO company_countries (id, company_id, country_id) VALUES (?, ?, ?)",
                        id,
                        companyId,
                        countryId));
        insertedCompanyCountries.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertRegion(UUID companyCountry, UUID companyId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update("""
                INSERT INTO company_regions (id, company_country_id, company_id, region_code, region_name, enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, id, companyCountry, companyId, suffix, "Location region " + suffix));
        insertedRegions.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertCompanyZone(UUID companyRegion, UUID companyId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update("""
                INSERT INTO company_zones (id, company_region_id, company_id, zone_code, zone_name, enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, id, companyRegion, companyId, suffix, "Location zone " + suffix));
        insertedCompanyZones.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertStore(UUID companyZone, UUID companyId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.update("""
                INSERT INTO company_stores (id, company_zone_id, company_id, store_name, enabled)
                VALUES (?, ?, ?, ?, true)
                """, id, companyZone, companyId, "Location store " + suffix));
        insertedStores.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertArea(UUID storeId, UUID companyId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.update("""
                INSERT INTO store_areas (id, company_store_id, area_code, area_name, enabled)
                VALUES (?, ?, ?, ?, true)
                """, id, storeId, suffix, "Location area " + suffix));
        insertedAreas.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertZone(UUID areaId, UUID storeId, UUID companyId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.update("""
                INSERT INTO store_zones (id, store_area_id, company_store_id, zone_code, zone_name, enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, id, areaId, storeId, suffix, "Location zone " + suffix));
        insertedStoreZones.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertLocation(UUID zoneId, UUID storeId, UUID companyId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.update("""
                INSERT INTO store_locations (id, store_zone_id, company_store_id, location_code,
                                             location_name, enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, id, zoneId, storeId, suffix, "Location leaf " + suffix));
        insertedLocations.add(new InsertedRow(companyId, id));
        return id;
    }

    /** A store of company A, through the whole company-zone chain, reusing the level helpers above. */
    private UUID insertStoreOfCompanyA() {
        var regionId = insertRegion(companyCountryId, companyAId);
        var companyZoneId = insertCompanyZone(regionId, companyAId);
        return insertStore(companyZoneId, companyAId);
    }

    /**
     * A row this class inserted, with the tenant whose policy guards it.
     *
     * <p>The tenant travels with the id because the cleanup below has to state it: an unscoped
     * {@code DELETE} on an armed table matches no row, so a fixture that forgot it would leave every row
     * behind for the next class of the shared container to count.</p>
     */
    private record InsertedRow(UUID companyId, UUID id) {}

    /** Deletes one row inside the tenant that guards it. */
    private void deleteRow(InsertedRow row, String sql) {
        TenantFixtures.inTenant(row.companyId(), () -> jdbcTemplate.update(sql, row.id()));
    }

    /** The stored zone's store, read through the tenant that owns the row. */
    private UUID zoneStore(UUID zoneId) {
        return TenantFixtures.inTenant(
                companyAId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT company_store_id FROM store_zones WHERE id = ?", UUID.class, zoneId));
    }

    /** The stored location's store, read through the tenant that owns the row. */
    private UUID locationStore(UUID locationId) {
        return TenantFixtures.inTenant(
                companyAId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT company_store_id FROM store_locations WHERE id = ?", UUID.class, locationId));
    }

    /** The stored zone, counted so a refusal can prove it stored nothing. */
    private int countZone(UUID zoneId) {
        return TenantFixtures.inTenant(
                companyAId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM store_zones WHERE id = ?", Integer.class, zoneId));
    }

    /** The stored location, counted so a refusal can prove it stored nothing. */
    private int countLocation(UUID locationId) {
        return TenantFixtures.inTenant(
                companyAId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM store_locations WHERE id = ?", Integer.class, locationId));
    }
}
