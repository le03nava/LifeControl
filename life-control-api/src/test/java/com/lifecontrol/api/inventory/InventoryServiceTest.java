package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.inventory.ledger.StockLedgerWriter;
import com.lifecontrol.api.inventory.model.InventoryMovement;
import com.lifecontrol.api.inventory.model.ProductVariantLocation;
import com.lifecontrol.api.inventory.repository.InventoryMovementRepository;
import com.lifecontrol.api.inventory.repository.ProductVariantLocationRepository;
import com.lifecontrol.api.inventory.repository.StockPositionRepository;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.inventory.service.InventoryService;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.model.ProductVariantStoreStock;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.service.CompanyStoreOwnerResolver;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit coverage of {@link InventoryService#applyReceipt}: the quantity guard, the store guard, the
 * additive mutations and the lock order. Persistence behaviour (unique constraint, ledger rows, the
 * resurrection regression) is covered by {@code InventoryIntegrationTest} on real PostgreSQL.
 *
 * <p>After the variant-identity split the serialization point is the per-store stock row, so the
 * lock order verified here is {@code storeStock -> locationBalance}, with the definition read
 * (unlocked) first only to reject a missing or disabled variant.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InventoryService Tests")
class InventoryServiceTest {

    private static final UUID VARIANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

    @Mock
    private ProductVariantLocationRepository productVariantLocationRepository;

    @Mock
    private InventoryMovementRepository inventoryMovementRepository;

    @Mock
    private StoreInventorySettingsRepository storeInventorySettingsRepository;

    @Mock
    private CompanyStoreRepository companyStoreRepository;

    /** The ledger's writer is a collaborator here: this suite is about the legacy arithmetic. */
    @Mock
    private StockLedgerWriter stockLedgerWriter;

    /**
     * The ledger's position. A collaborator like the writer above: this suite exercises receipts, whose path
     * never reads it — the position gate is a sale's, since T5b of {@code odd/tasks/inventory-ledger.md}.
     */
    @Mock
    private StockPositionRepository stockPositionRepository;

    private InventoryService inventoryService;

    /** The company that owns this fixture's store, derived through the chain the resolver walks. */
    private static final UUID STORE_COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c0");

    /**
     * The store of the fixture, with the whole company &rarr; country &rarr; region &rarr; zone
     * &rarr; store chain: the engine derives the tenant from the store argument, so a fixture without
     * the chain would be asking a question it cannot answer.
     */
    private static CompanyStore storeOwnedBy(UUID ownerCompanyId, UUID storeId) {
        var company = Company.builder()
                .id(ownerCompanyId)
                .companyKey("INV-KEY")
                .companyName("Inventory Test Company")
                .rfc("INVT123456ABC")
                .enabled(true)
                .build();
        var country =
                CompanyCountry.builder().id(UUID.randomUUID()).company(company).build();
        var region = CompanyRegion.builder()
                .id(UUID.randomUUID())
                .companyCountry(country)
                .regionCode("01")
                .regionName("Inventory Region")
                .enabled(true)
                .build();
        var zone = CompanyZone.builder()
                .id(UUID.randomUUID())
                .companyRegion(region)
                .zoneCode("01")
                .zoneName("Inventory Zone")
                .enabled(true)
                .build();
        return CompanyStore.builder()
                .id(storeId)
                .companyZone(zone)
                .storeName("Inventory Test Store")
                .enabled(true)
                .build();
    }

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(
                productVariantRepository,
                productVariantStoreStockRepository,
                productVariantLocationRepository,
                inventoryMovementRepository,
                storeInventorySettingsRepository,
                companyStoreRepository,
                new CompanyStoreOwnerResolver(),
                stockLedgerWriter,
                stockPositionRepository);

        // The ledger is a collaborator of this suite, not its subject: the legacy arithmetic is. A
        // healthy ledger answers the two questions the reversal path asks it (which ledger row mirrors a
        // legacy sale row, and whether a reversal already names it), so the legacy behaviour the cases
        // below assert is reached instead of the writer's own refusal.
        lenient()
                .when(stockLedgerWriter.mirroredMovementId(any(), anyString()))
                .thenReturn(Optional.of(UUID.randomUUID()));

        // The engine derives the tenant from the store argument before it reads the definition, so
        // every non-quantity path needs the fixture's store to exist. Lenient because the quantity
        // guard rejects before the store is read.
        lenient()
                .when(companyStoreRepository.findById(STORE_ID))
                .thenReturn(Optional.of(storeOwnedBy(STORE_COMPANY_ID, STORE_ID)));
    }

    private ProductVariant enabledVariant() {
        return ProductVariant.builder()
                .id(VARIANT_ID)
                .barCode("7501234567890")
                .variantName("Talla M")
                .enabled(true)
                .build();
    }

    private ProductVariantStoreStock storeRowWithStock(String stock) {
        return ProductVariantStoreStock.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000003"))
                .productVariantId(VARIANT_ID)
                .companyStoreId(STORE_ID)
                .stock(stock != null ? new BigDecimal(stock) : null)
                .build();
    }

    private ProductVariantLocation balanceWith(UUID balanceId, String stock) {
        return ProductVariantLocation.builder()
                .id(balanceId)
                .productVariantId(VARIANT_ID)
                .storeLocationId(LOCATION_ID)
                .stock(new BigDecimal(stock))
                .build();
    }

    private void applyReceipt(String quantity) {
        inventoryService.applyReceipt(
                VARIANT_ID,
                STORE_ID,
                LOCATION_ID,
                new BigDecimal(quantity),
                "GOODS_RECEIPT",
                UUID.randomUUID(),
                "tester");
    }

    @Nested
    @DisplayName("quantity guard")
    class QuantityGuardTests {

        @Test
        @DisplayName("should reject zero without touching any repository")
        void rejectsZero() {
            assertThatThrownBy(() -> applyReceipt("0.00"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be greater than zero");

            assertNoRepositoryInteraction();
        }

        @Test
        @DisplayName("should reject a negative quantity without touching any repository")
        void rejectsNegative() {
            assertThatThrownBy(() -> applyReceipt("-1.00"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be greater than zero");

            assertNoRepositoryInteraction();
        }

        @Test
        @DisplayName("should reject a null quantity with the plain IllegalArgumentException")
        void rejectsNull() {
            assertThatThrownBy(() -> inventoryService.applyReceipt(
                            VARIANT_ID, STORE_ID, LOCATION_ID, null, "GOODS_RECEIPT", null, "tester"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be greater than zero");

            assertNoRepositoryInteraction();
        }

        private void assertNoRepositoryInteraction() {
            verifyNoInteractions(productVariantRepository);
            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(productVariantLocationRepository);
            verifyNoInteractions(inventoryMovementRepository);
        }
    }

    @Nested
    @DisplayName("store guard")
    class StoreGuardTests {

        @Test
        @DisplayName("should reject a variant with no stock row in the supplied store and write nothing")
        void rejectsVariantWithNoStoreRow() {
            var otherStoreId = UUID.fromString("00000000-0000-0000-0000-0000000000ee");

            // The other store exists and belongs to the same company; what it lacks is this variant's
            // stock row, which is the placement error this case is about.
            when(companyStoreRepository.findById(otherStoreId))
                    .thenReturn(Optional.of(storeOwnedBy(STORE_COMPANY_ID, otherStoreId)));
            when(productVariantRepository.findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID))
                    .thenReturn(Optional.of(enabledVariant()));
            when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreIdForUpdate(
                            VARIANT_ID, otherStoreId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> inventoryService.applyReceipt(
                            VARIANT_ID,
                            otherStoreId,
                            LOCATION_ID,
                            new BigDecimal("1.00"),
                            "GOODS_RECEIPT",
                            UUID.randomUUID(),
                            "receiver"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(VARIANT_ID.toString())
                    .hasMessageContaining(otherStoreId.toString());

            // No ledger row, no balance row and no stock mutation.
            verifyNoInteractions(inventoryMovementRepository);
            verifyNoInteractions(productVariantLocationRepository);
            verify(productVariantStoreStockRepository, never()).save(any(ProductVariantStoreStock.class));
        }

        @Test
        @DisplayName("should refuse a variant the store's company does not own and write nothing")
        void refusesVariantOfAnotherCompany() {
            // The row exists, is enabled and has a stock row in this very store: the pair the schema
            // cannot forbid (company_stores carries no company_id), so the company the store derives
            // is the only thing that can refuse it — the lookup scoped to that company answers
            // nothing, exactly as it would for a variant that never existed.
            when(productVariantRepository.findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> applyReceipt("3.00")).isInstanceOf(ProductVariantNotFoundException.class);

            // The only company asked about is the one that owns the store.
            verify(productVariantRepository).findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID);
            // Nothing persisted and nothing locked: no ledger row, no balance mutation, no aggregate
            // mutation, and the store row was never even looked for.
            verifyNoInteractions(inventoryMovementRepository);
            verifyNoInteractions(productVariantLocationRepository);
            verifyNoInteractions(productVariantStoreStockRepository);
        }
    }

    @Nested
    @DisplayName("receipt write path")
    class AdditiveReceiptTests {

        @Test
        @DisplayName("should book exactly one RECEIPT row and write no legacy column")
        void receiptBooksOneLedgerRowAndNothingElse() {
            var balanceId = UUID.fromString("00000000-0000-0000-0000-000000000002");
            var referenceId = UUID.randomUUID();

            when(productVariantRepository.findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID))
                    .thenReturn(Optional.of(enabledVariant()));
            when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreIdForUpdate(
                            VARIANT_ID, STORE_ID))
                    .thenReturn(Optional.of(ProductVariantStoreStock.builder()
                            .productVariantId(VARIANT_ID)
                            .companyStoreId(STORE_ID)
                            .stock(new BigDecimal("10.00"))
                            .costPrice(new BigDecimal("7.25"))
                            .build()));
            when(productVariantLocationRepository.findByProductVariantIdAndStoreLocationIdForUpdate(
                            VARIANT_ID, LOCATION_ID))
                    .thenReturn(Optional.of(balanceWith(balanceId, "0.00")));
            when(stockLedgerWriter.operatingCurrency(STORE_COMPANY_ID)).thenReturn("MXN");

            inventoryService.applyReceipt(
                    VARIANT_ID,
                    STORE_ID,
                    LOCATION_ID,
                    new BigDecimal("12.50"),
                    StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE,
                    referenceId,
                    "receiver");

            var bookingCaptor = ArgumentCaptor.forClass(StockLedgerWriter.Booking.class);
            var quantityCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            var unitCostCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            var currencyCaptor = ArgumentCaptor.forClass(String.class);
            var reasonCaptor = ArgumentCaptor.forClass(String.class);
            verify(stockLedgerWriter)
                    .bookReceipt(
                            bookingCaptor.capture(),
                            quantityCaptor.capture(),
                            unitCostCaptor.capture(),
                            currencyCaptor.capture(),
                            reasonCaptor.capture());

            var booking = bookingCaptor.getValue();
            assertThat(booking.sourceType()).isEqualTo(StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE);
            assertThat(booking.sourceId()).isEqualTo(referenceId);
            assertThat(booking.writeSeq()).isEqualTo(StockLedgerWriter.SINGLE_WRITE);
            assertThat(booking.companyStoreId()).isEqualTo(STORE_ID);
            assertThat(booking.storeLocationId()).isEqualTo(LOCATION_ID);
            assertThat(booking.productVariantId()).isEqualTo(VARIANT_ID);
            assertThat(booking.actor()).isEqualTo("receiver");
            assertThat(quantityCaptor.getValue()).isEqualByComparingTo("12.50");
            // The cost is the one the store records (ck_movement_inbound_cost requires one on every row that
            // adds quantity) and the currency is the company's operating currency, so no reason is stated.
            assertThat(unitCostCaptor.getValue()).isEqualByComparingTo("7.25");
            assertThat(currencyCaptor.getValue()).isEqualTo("MXN");
            assertThat(reasonCaptor.getValue()).isNull();

            // S4: the ledger row is the WHOLE write. A receipt that also touched the legacy columns would be a
            // second truth, which is what this slice removes; asserting it is what keeps the removal honest.
            verify(productVariantLocationRepository, never()).save(any(ProductVariantLocation.class));
            verify(productVariantStoreStockRepository, never()).save(any(ProductVariantStoreStock.class));
            verify(inventoryMovementRepository, never()).save(any(InventoryMovement.class));
        }

        @Test
        @DisplayName("should fail with ProductVariantNotFoundException and write no ledger row")
        void unknownVariantFails() {
            when(productVariantRepository.findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> applyReceipt("1.00")).isInstanceOf(ProductVariantNotFoundException.class);

            verifyNoInteractions(stockLedgerWriter);
            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(productVariantLocationRepository);
        }

        @Test
        @DisplayName("should fail with ProductVariantNotFoundException when the variant is disabled")
        void disabledVariantFails() {
            // The enabled filter is part of the finder now, not a filter applied after it, so a
            // soft-deleted definition is simply not an answer (see ProductVariantRepository).
            when(productVariantRepository.findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> applyReceipt("1.00")).isInstanceOf(ProductVariantNotFoundException.class);

            verifyNoInteractions(stockLedgerWriter);
            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(productVariantLocationRepository);
        }
    }

    @Nested
    @DisplayName("lock order")
    class LockOrderTests {

        @Test
        @DisplayName("should lock the per-store stock row before any interaction with the balance repository")
        void locksStoreStockBeforeAnyBalanceInteraction() {
            var balanceId = UUID.fromString("00000000-0000-0000-0000-000000000000");

            when(productVariantRepository.findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID))
                    .thenReturn(Optional.of(enabledVariant()));
            when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreIdForUpdate(
                            VARIANT_ID, STORE_ID))
                    .thenReturn(Optional.of(storeRowWithStock("1.00")));
            when(productVariantLocationRepository.findByProductVariantIdAndStoreLocationIdForUpdate(
                            VARIANT_ID, LOCATION_ID))
                    .thenReturn(Optional.of(balanceWith(balanceId, "1.00")));

            applyReceipt("1.00");

            // Not vacuous: the definition read, the store-stock lock and both balance-repository
            // interactions are verified as really invoked, and the store-stock lock must come first.
            InOrder lockOrder = inOrder(
                    productVariantRepository, productVariantStoreStockRepository, productVariantLocationRepository);
            lockOrder.verify(productVariantRepository).findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID);
            lockOrder
                    .verify(productVariantStoreStockRepository)
                    .findByProductVariantIdAndCompanyStoreIdForUpdate(VARIANT_ID, STORE_ID);
            lockOrder.verify(productVariantLocationRepository).insertBalanceIfAbsent(VARIANT_ID, LOCATION_ID);
            lockOrder
                    .verify(productVariantLocationRepository)
                    .findByProductVariantIdAndStoreLocationIdForUpdate(VARIANT_ID, LOCATION_ID);

            // The definition must NOT be locked any more: it no longer carries stock.
        }

        @Test
        @DisplayName("should create the balance row through the conflict-tolerant insert when it is missing")
        void createsMissingBalanceWithConflictTolerantInsert() {
            when(productVariantRepository.findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID))
                    .thenReturn(Optional.of(enabledVariant()));
            when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreIdForUpdate(
                            VARIANT_ID, STORE_ID))
                    .thenReturn(Optional.of(storeRowWithStock("0.00")));
            when(productVariantLocationRepository.findByProductVariantIdAndStoreLocationIdForUpdate(
                            VARIANT_ID, LOCATION_ID))
                    .thenReturn(
                            Optional.of(balanceWith(UUID.fromString("00000000-0000-0000-0000-000000000002"), "0.00")));

            applyReceipt("4.00");

            InOrder lockOrder = inOrder(productVariantStoreStockRepository, productVariantLocationRepository);
            lockOrder
                    .verify(productVariantStoreStockRepository)
                    .findByProductVariantIdAndCompanyStoreIdForUpdate(VARIANT_ID, STORE_ID);
            lockOrder.verify(productVariantLocationRepository).insertBalanceIfAbsent(VARIANT_ID, LOCATION_ID);
        }
    }
}
