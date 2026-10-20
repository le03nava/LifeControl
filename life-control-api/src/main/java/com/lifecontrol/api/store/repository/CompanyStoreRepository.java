package com.lifecontrol.api.store.repository;

import com.lifecontrol.api.store.model.CompanyStore;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for company stores.
 *
 * <p>The list finders used by the store list path carry an {@link EntityGraph} that fetches the
 * full {@code companyZone -> companyRegion -> companyCountry -> company} chain plus the
 * {@code address} in a single query. {@code spring.jpa.open-in-view=false} is set in production and
 * {@code CompanyStore.address} is a lazy owning-side {@code @OneToOne} that Hibernate cannot proxy,
 * so without the graph the response mapper issued one extra select per store element.</p>
 *
 * <p>Measured against PostgreSQL by {@code CompanyStoreListQueryCountIntegrationTest}: 8 statements
 * for 1 store and 8 for 4 stores with the graph, against 9 and 12 without it. The chain itself is
 * normally already in the persistence context because the list path resolves the zone hierarchy
 * first; fetching it here keeps the list path a single round trip independently of that.</p>
 */
@Repository
public interface CompanyStoreRepository extends JpaRepository<CompanyStore, UUID> {

    @EntityGraph(
            attributePaths = {
                "companyZone",
                "companyZone.companyRegion",
                "companyZone.companyRegion.companyCountry",
                "companyZone.companyRegion.companyCountry.company",
                "address"
            })
    List<CompanyStore> findByCompanyZoneIdAndEnabledTrue(UUID companyZoneId);

    @EntityGraph(
            attributePaths = {
                "companyZone",
                "companyZone.companyRegion",
                "companyZone.companyRegion.companyCountry",
                "companyZone.companyRegion.companyCountry.company",
                "address"
            })
    List<CompanyStore> findByCompanyZoneId(UUID companyZoneId);

    Optional<CompanyStore> findByIdAndCompanyZoneId(UUID id, UUID companyZoneId);

    boolean existsByStoreNameAndCompanyZoneIdAndIdNot(String storeName, UUID companyZoneId, UUID excludeId);

    boolean existsByStoreNameAndCompanyZoneId(String storeName, UUID companyZoneId);

    @EntityGraph(
            attributePaths = {
                "companyZone",
                "companyZone.companyRegion",
                "companyZone.companyRegion.companyCountry",
                "companyZone.companyRegion.companyCountry.company",
                "address"
            })
    List<CompanyStore> findByIdInAndCompanyZoneId(Set<UUID> storeIds, UUID companyZoneId);

    @EntityGraph(
            attributePaths = {
                "companyZone",
                "companyZone.companyRegion",
                "companyZone.companyRegion.companyCountry",
                "companyZone.companyRegion.companyCountry.company",
                "address"
            })
    List<CompanyStore> findByIdInAndCompanyZoneIdAndEnabledTrue(Set<UUID> storeIds, UUID companyZoneId);

    /**
     * Store ids within one level of the hierarchy, constrained by every ancestor claim.
     *
     * <p>{@link com.lifecontrol.api.store.model.CompanyStore} carries no company/country/region
     * column: it hangs off {@code companyZone -> companyRegion -> companyCountry -> company}, so
     * each finder below is the same walk reached from a different level.</p>
     *
     * <p>Every finder takes the <strong>whole</strong> claim path down from {@code company}, not
     * just its own level, because that is what the id-addressed read verifies:
     * {@code CurrentUserContext#verifyLevelsUpTo} walks from {@code company} into the caller's
     * broadest granted scope. A finder that filtered by its own claim alone would hand the list a
     * row the detail denies whenever an ancestor claim disagrees — a token minted out of band, a
     * group attribute, or migration residue. Each level is also optional in the argument list in
     * the sense that callers with a narrower scope never reach it; the resolver denies before
     * calling when a required claim is missing, so no empty collection arrives here.</p>
     *
     * <p>The projections return ids only — a list filter needs the set, never the entity — and none
     * of them filters by {@code enabled}: a disabled store's receipts stay readable to whoever can
     * open them by id.</p>
     */
    @Query("select s.id from CompanyStore s where s.companyZone.companyRegion.companyCountry.company.id in :companyIds")
    List<UUID> findIdsByCompanyIdIn(@Param("companyIds") Collection<UUID> companyIds);

    @Query("select s.id from CompanyStore s "
            + "where s.companyZone.companyRegion.companyCountry.id in :companyCountryIds "
            + "and s.companyZone.companyRegion.companyCountry.company.id in :companyIds")
    List<UUID> findIdsByCompanyCountryIdIn(
            @Param("companyCountryIds") Collection<UUID> companyCountryIds,
            @Param("companyIds") Collection<UUID> companyIds);

    @Query("select s.id from CompanyStore s "
            + "where s.companyZone.companyRegion.id in :companyRegionIds "
            + "and s.companyZone.companyRegion.companyCountry.id in :companyCountryIds "
            + "and s.companyZone.companyRegion.companyCountry.company.id in :companyIds")
    List<UUID> findIdsByCompanyRegionIdIn(
            @Param("companyRegionIds") Collection<UUID> companyRegionIds,
            @Param("companyCountryIds") Collection<UUID> companyCountryIds,
            @Param("companyIds") Collection<UUID> companyIds);

    @Query("select s.id from CompanyStore s "
            + "where s.companyZone.id in :companyZoneIds "
            + "and s.companyZone.companyRegion.id in :companyRegionIds "
            + "and s.companyZone.companyRegion.companyCountry.id in :companyCountryIds "
            + "and s.companyZone.companyRegion.companyCountry.company.id in :companyIds")
    List<UUID> findIdsByCompanyZoneIdIn(
            @Param("companyZoneIds") Collection<UUID> companyZoneIds,
            @Param("companyRegionIds") Collection<UUID> companyRegionIds,
            @Param("companyCountryIds") Collection<UUID> companyCountryIds,
            @Param("companyIds") Collection<UUID> companyIds);

    @Query("select s.id from CompanyStore s "
            + "where s.id in :storeIds "
            + "and s.companyZone.id in :companyZoneIds "
            + "and s.companyZone.companyRegion.id in :companyRegionIds "
            + "and s.companyZone.companyRegion.companyCountry.id in :companyCountryIds "
            + "and s.companyZone.companyRegion.companyCountry.company.id in :companyIds")
    List<UUID> findIdsByStoreIdIn(
            @Param("storeIds") Collection<UUID> storeIds,
            @Param("companyZoneIds") Collection<UUID> companyZoneIds,
            @Param("companyRegionIds") Collection<UUID> companyRegionIds,
            @Param("companyCountryIds") Collection<UUID> companyCountryIds,
            @Param("companyIds") Collection<UUID> companyIds);
}
