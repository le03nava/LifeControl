package com.lifecontrol.api.purchaseorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.common.document.DocumentNumberRenderer;
import com.lifecontrol.api.common.document.DocumentSequenceAllocator;
import com.lifecontrol.api.common.document.DocumentType;
import com.lifecontrol.api.common.state.StatusSubject;
import com.lifecontrol.api.common.state.StatusTransitionRecorder;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrder;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderState;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderDetailRepository;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderRepository;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.supplier.model.Supplier;
import com.lifecontrol.api.supplier.repository.SupplierRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The period a purchase order's number is allocated in and the date its own row records are <b>the same
 * instant</b>, verified against real PostgreSQL with Flyway enabled (issue #772, ADR-0003 decision 4).
 *
 * <p><b>Why this needs a database.</b> The two halves live in two places that only a real flush exercises:
 * {@code PurchaseOrderService} freezes the date the number is allocated in, and the JPA lifecycle callback on
 * the entity stamps {@code created_at}/{@code updated_at} when the row is written. A Mockito test can see the
 * first half (the entity it captured) and cannot see the second (there is no persist), so the property "the
 * row's date and the number's period agree" is only observable by writing a row and reading it back.</p>
 *
 * <p><b>Why the orders are written through the repository and not through {@code PurchaseOrderService}.</b>
 * The subject here is the entity's own audit columns; the service's clock read is covered by
 * {@code PurchaseOrderServiceTest}. A fixture that froze the instant itself would make the service's behavior
 * invisible, so it is deliberately not this class's question.</p>
 *
 * <p><b>The company's tenant is stated for every write</b>, exactly as {@code PurchaseOrderListFilter}
 * does: {@code purchase_orders}, {@code document_sequence} and {@code suppliers} are policy-armed tables, so a
 * fixture that writes without a scope is refused by the policy instead of passing silently.</p>
 */
@SpringBootTest
@DisplayName("Purchase Order Numbering Integration Tests")
class PurchaseOrderNumberingIntegrationTest extends AbstractPostgresIntegrationTest {

    /**
     * The instant a document numbered in October carries in its own row: the last millisecond before the
     * period flips, which is the boundary the defect is about.
     */
    private static final LocalDateTime OCTOBER_LAST_INSTANT = LocalDateTime.of(2026, 10, 31, 23, 59, 59, 999_000_000);

    /** The period {@link #OCTOBER_LAST_INSTANT} belongs to, and the {@code seq_period} it must leave behind. */
    private static final String OCTOBER_PERIOD = "202610";

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderDetailRepository purchaseOrderDetailRepository;

    @Autowired
    private StatusTransitionRecorder statusTransitionRecorder;

    @Autowired
    private DocumentSequenceAllocator documentSequenceAllocator;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Autowired
    private StatusRepository statusRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private CompanyCountryRepository companyCountryRepository;

    @Autowired
    private CompanyRegionRepository companyRegionRepository;

    @Autowired
    private CompanyZoneRepository companyZoneRepository;

    @Autowired
    private CompanyStoreRepository companyStoreRepository;

    @Autowired
    private CountryRepository countryRepository;

    private Company company;
    private UUID companyId;
    private CompanyStore store;
    private Supplier supplier;
    private PaymentMethod paymentMethod;

    @BeforeEach
    void setUp() {
        // Details reference purchase orders, so they go first.
        purchaseOrderDetailRepository.deleteAll();
        company =
                TenantFixtures.findOrCreate(companyRepository, "PO-NUMBERING", "PO Numbering Company", "PON010101ABC");
        companyId = company.getId();
        TenantFixtures.inTenant(companyId, () -> purchaseOrderRepository.deleteAll());

        seedStoreChain();
        seedSupplierAndPaymentMethod();
    }

    @AfterEach
    void tearDown() {
        // Do not leave purchase order rows behind: they reference suppliers and stores, and other integration
        // classes that run later in the same JVM delete those wholesale.
        purchaseOrderDetailRepository.deleteAll();
        TenantFixtures.inTenant(companyId, () -> purchaseOrderRepository.deleteAll());
    }

    // ─── fixture ────────────────────────────────────────────────────────

    /**
     * One country &rarr; region &rarr; zone &rarr; store chain, in one {@link TenantFixtures#inTenant} for the
     * whole tree: every child is a row on a policy-armed table and references the detached {@code Company}, so
     * both its {@code INSERT} and the flush's unsaved-value probe need that company's tenant installed.
     */
    private void seedStoreChain() {
        TenantFixtures.inTenant(companyId, () -> {
            var companyCountry = findOrCreateCompanyCountry();
            var region = findOrCreateRegion(companyCountry);
            var zone = findOrCreateZone(region);
            store = findOrCreateStore(zone);
        });
    }

    private CompanyCountry findOrCreateCompanyCountry() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();
        return companyCountryRepository
                .findByCompanyIdAndCountryId(companyId, country.getId())
                .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                        .company(company)
                        .country(country)
                        .build()));
    }

    private CompanyRegion findOrCreateRegion(CompanyCountry companyCountry) {
        return companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                .filter(region -> "PO Numbering Region".equals(region.getRegionName()))
                .findFirst()
                .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                        .companyCountry(companyCountry)
                        .regionCode("PON")
                        .regionName("PO Numbering Region")
                        .enabled(true)
                        .build()));
    }

    private CompanyZone findOrCreateZone(CompanyRegion region) {
        return companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .filter(zone -> "PO Numbering Zone".equals(zone.getZoneName()))
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode("PON1")
                        .zoneName("PO Numbering Zone")
                        .enabled(true)
                        .build()));
    }

    private CompanyStore findOrCreateStore(CompanyZone zone) {
        return companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                .filter(candidate -> "PO Numbering Store".equals(candidate.getStoreName()))
                .findFirst()
                .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                        .companyZone(zone)
                        .storeName("PO Numbering Store")
                        .enabled(true)
                        .build()));
    }

    /**
     * A supplier of {@code company}, stated under its tenant: {@code suppliers} is policy-armed, and its RFC
     * key is per company, so the fixture draws one instead of stating a literal two runs would collide on.
     */
    private void seedSupplierAndPaymentMethod() {
        supplier = TenantFixtures.inTenant(
                companyId,
                () -> supplierRepository.save(Supplier.builder()
                        .supplierName("PO Numbering Supplier")
                        .rfc("PON" + UUID.randomUUID().toString().substring(0, 10))
                        .company(company)
                        .enabled(true)
                        .build()));
        paymentMethod = paymentMethodRepository
                .findByPaymentMethodNameIgnoreCase("PO Numbering Transfer")
                .orElseGet(() -> paymentMethodRepository.save(PaymentMethod.builder()
                        .paymentMethodName("PO Numbering Transfer")
                        .paymentMethodShortName("PONT")
                        .enabled(true)
                        .build()));
    }

    // ─── the two halves ─────────────────────────────────────────────────

    /**
     * The criterion issue #772 states: an order numbered at {@code 2026-10-31T23:59:59.999} carries
     * {@code seq_period} {@code 202610} <b>and</b> its {@code created_at} is in October.
     *
     * <p>{@code purchase_orders} carries no {@code order_date} column ({@code V1__baseline_schema.sql:317-328}
     * declares {@code created_at}/{@code updated_at} and nothing else), so {@code created_at} <b>is</b> the
     * document's own date and ADR-0003 decision 4 says the period comes from it. Two readings of the clock —
     * one for the period and one for the row — are how a number for October ends up on a row dated November.
     * </p>
     */
    @Test
    @DisplayName("should keep the instant its number was allocated in when the row is written")
    void anOrderKeepsTheInstantItsNumberWasAllocatedIn() {
        var orderId = persistOrder(OCTOBER_PERIOD, OCTOBER_LAST_INSTANT);

        var reloaded = reload(orderId);

        assertThat(reloaded.getCreatedAt())
                .as("the row keeps the instant the number was allocated in, not a later reading at flush")
                .isEqualTo(OCTOBER_LAST_INSTANT);
        assertThat(reloaded.getUpdatedAt())
                .as("a row born just now has not been updated since")
                .isEqualTo(OCTOBER_LAST_INSTANT);
        assertThat(reloaded.getSeqPeriod())
                .as("the period the number was allocated in does not move when the row is written")
                .isEqualTo(OCTOBER_PERIOD);
        assertThat(DocumentNumberRenderer.periodOf(reloaded.getCreatedAt().toLocalDate()))
                .as("the period of the date the row records is the period its number was allocated in")
                .isEqualTo(reloaded.getSeqPeriod());
    }

    /**
     * The regression pin for {@code Auditable}: an entity that freezes nothing is still stamped by the
     * inherited lifecycle callback at persist.
     *
     * <p>This is the half a naive fix breaks. An override that skips {@code super.onCreate()} leaves both
     * columns null and the {@code NOT NULL} constraint refuses the insert; the same failure mode is recorded
     * by {@code GoodsReceiptIntegrationTest} for a differently named callback shadowing the inherited one.</p>
     */
    @Test
    @DisplayName("should still stamp an order that froze no instant")
    void anOrderWithoutAFrozenInstantIsStillStampedAtPersist() {
        var orderId = persistOrder(OCTOBER_PERIOD, null);

        var reloaded = reload(orderId);

        assertThat(reloaded.getCreatedAt())
                .as("the inherited callback still stamps created_at when nothing was frozen")
                .isNotNull();
        assertThat(reloaded.getUpdatedAt())
                .as("the inherited callback still stamps updated_at when nothing was frozen")
                .isNotNull();
    }

    // ─── helpers ────────────────────────────────────────────────────────

    /**
     * Writes one order under {@code companyId}'s tenant and answers its id, with the creation row
     * {@code V69} demands: the deferred trigger refuses a document born without its {@code status_transition}
     * row, so a fixture owes exactly what a writer owes.
     *
     * @param period the {@code seq_period} the allocation happens in
     * @param frozen the instant to carry in {@code created_at}/{@code updated_at}, or {@code null} to leave it
     *     to the entity's lifecycle callback
     */
    private UUID persistOrder(String period, LocalDateTime frozen) {
        // The catalog row the state is projected from (ADR-0004 decision 7): read here so a drift between
        // the label this fixture names and the code the enum carries fails loudly instead of silently.
        var draftStatus = statusRepository
                .findByTypeNameAndStatusName("PURCHASE_ORDER", "Draft")
                .orElseThrow();
        assertThat(draftStatus.getCode()).isEqualTo(PurchaseOrderState.DRAFT.name());

        return TenantFixtures.inTenant(companyId, () -> {
            // ADR-0003 identity parts: the allocator hands out the value, because a literal would collide on
            // uq_purchase_orders_doc_identity when both cases number an order in the same period.
            var seqValue = documentSequenceAllocator.allocate(companyId, DocumentType.PURCHASE_ORDER, period);
            var builder = PurchaseOrder.builder()
                    .orderNumber("PO-NUMBERING-" + UUID.randomUUID().toString().substring(0, 8))
                    .docType(DocumentType.PURCHASE_ORDER.code())
                    .seqPeriod(period)
                    .seqValue(seqValue)
                    .docNumber(DocumentNumberRenderer.renderPeriodNumber(DocumentType.PURCHASE_ORDER, period, seqValue))
                    .supplier(supplier)
                    .companyStore(store)
                    .company(company)
                    .paymentMethod(paymentMethod)
                    .status(PurchaseOrderState.DRAFT)
                    .comments("PO numbering fixture")
                    .enabled(true);
            if (frozen != null) {
                builder.createdAt(frozen).updatedAt(frozen);
            }
            var saved = purchaseOrderRepository.save(builder.build());
            recordCreation(saved);
            return saved.getId();
        });
    }

    /**
     * Reads the order back in its own transaction, so the value asserted is the database's and not the
     * persistence context's copy of what the fixture wrote.
     */
    private PurchaseOrder reload(UUID orderId) {
        return TenantFixtures.inTenant(
                companyId, () -> purchaseOrderRepository.findById(orderId).orElseThrow());
    }

    /**
     * Appends {@code order}'s creation row through {@link StatusTransitionRecorder}, under a fixture identity.
     *
     * <p>The identity is not decoration: the recorder takes the actor from the request's claims and refuses a
     * row with none, so a fixture that wants to write history has to say who it is. It is installed for this
     * one call and cleared afterwards, so no case inherits an authenticated caller it did not ask for.</p>
     */
    private void recordCreation(PurchaseOrder order) {
        var identity = Jwt.withTokenValue("fixture-token")
                .header("alg", "none")
                .subject("test:fixture")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(identity, List.of()));
        try {
            purchaseOrderRepository.flush();
            statusTransitionRecorder.recordCreation(
                    StatusSubject.PURCHASE_ORDER, order.getCompany().getId(), order.getId(), order.getStatus());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
