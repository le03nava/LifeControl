package com.lifecontrol.api.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves {@code V8__store_optimistic_locking.sql} + the JPA {@code @Version} mapping actually
 * protect the store tree against a lost update, against real PostgreSQL.
 *
 * <p>Two independent transactions load the same row without sharing a persistence context: the
 * first holds its transaction open while the second mutates and commits. The first commit must then
 * fail with {@link ObjectOptimisticLockingFailureException} instead of silently overwriting the
 * winner. Latches are bounded so a broken test fails loudly instead of hanging.</p>
 */
@SpringBootTest
@DisplayName("Store optimistic locking Integration Tests")
class StoreOptimisticLockingIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private CompanyStoreRepository companyStoreRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private CompanyCountryRepository companyCountryRepository;

    @Autowired
    private CompanyRegionRepository companyRegionRepository;

    @Autowired
    private CompanyZoneRepository companyZoneRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID zoneId;

    @BeforeEach
    void setUp() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = companyRepository
                .findByCompanyKey("STORE-LOCK-KEY")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey("STORE-LOCK-KEY")
                        .companyName("Store Locking Test Company")
                        .rfc("SLOK010101ABC")
                        .enabled(true)
                        .build()));

        var companyCountry = companyCountryRepository
                .findByCompanyIdAndCountryId(company.getId(), country.getId())
                .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                        .company(company)
                        .country(country)
                        .build()));

        var region = companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                        .companyCountry(companyCountry)
                        .regionCode("SL")
                        .regionName("Store Locking Region")
                        .enabled(true)
                        .build()));

        var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode("SL")
                        .zoneName("Store Locking Zone")
                        .enabled(true)
                        .build()));

        zoneId = zone.getId();
    }

    @Test
    @DisplayName("should fail a stale version commit instead of silently winning the write")
    void staleUpdate_FailsInsteadOfSilentlyWinning() throws Exception {
        var zone = companyZoneRepository.findById(zoneId).orElseThrow();
        var store = companyStoreRepository.save(CompanyStore.builder()
                .companyZone(zone)
                .storeName("Concurrency Store")
                .enabled(true)
                .build());
        var storeId = store.getId();
        assertThat(store.getVersion()).isZero();

        var template = new TransactionTemplate(transactionManager);
        var threadAHasLoaded = new CountDownLatch(1);
        var threadBMayCommit = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            // Thread A loads the store, mutates it, and holds its transaction open until B commits.
            var firstTransaction = executor.submit(() -> template.execute(status -> {
                var loaded = companyStoreRepository.findById(storeId).orElseThrow();
                loaded.setStoreName("Name from thread A");
                threadAHasLoaded.countDown();
                awaitLatch(threadBMayCommit, "thread B to commit");
                return null;
            }));
            assertThat(threadAHasLoaded.await(10, TimeUnit.SECONDS))
                    .as("thread A loads the store within the timeout")
                    .isTrue();

            // Thread B loads the same row in its own persistence context, mutates and commits first.
            var secondTransaction = executor.submit(() -> template.execute(status -> {
                var loaded = companyStoreRepository.findById(storeId).orElseThrow();
                loaded.setStoreName("Name from thread B");
                companyStoreRepository.saveAndFlush(loaded);
                return null;
            }));
            secondTransaction.get(15, TimeUnit.SECONDS);
            threadBMayCommit.countDown();

            // Thread A now commits with a stale version: it must fail rather than overwrite B.
            var failure = catchThrowable(() -> firstTransaction.get(15, TimeUnit.SECONDS));
            assertThat(failure).as("thread A's commit fails loudly").isInstanceOf(ExecutionException.class);
            assertThat(causeChainOf(failure))
                    .as("the stale commit surfaces as an optimistic locking failure")
                    .anySatisfy(throwable ->
                            assertThat(throwable).isInstanceOf(ObjectOptimisticLockingFailureException.class));
        } finally {
            threadBMayCommit.countDown();
            executor.shutdownNow();
        }

        // No lost update: the row keeps the value committed by the winning transaction.
        assertThat(companyStoreRepository.findById(storeId).orElseThrow().getStoreName())
                .isEqualTo("Name from thread B");
    }

    private static void awaitLatch(CountDownLatch latch, String what) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for " + what);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + what, e);
        }
    }

    private static List<Throwable> causeChainOf(Throwable throwable) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
