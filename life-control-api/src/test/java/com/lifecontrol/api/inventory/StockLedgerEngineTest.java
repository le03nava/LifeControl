package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.inventory.ledger.StockLedgerEngine;
import com.lifecontrol.api.inventory.ledger.StockLedgerWriter;
import com.lifecontrol.api.inventory.repository.StockPositionRepository;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit coverage of {@link StockLedgerEngine#applyReceipt}: the quantity guard, the store guard and the one
 * movement a receipt books. Persistence behaviour (the ledger row, the projection it derives, the unique key)
 * is covered by {@code InventoryLedgerBookingIntegrationTest} on real PostgreSQL.
 *
 * <p><b>What this suite no longer pins, and why the absence is the assertion.</b> Before the mirror was retired
 * ({@code odd/tasks/ledger-mirror-retirement.md}, T4) a receipt also locked the per-store stock row and the
 * location balance row, and wrote both legacy columns beside the ledger. Those rows no longer exist: the
 * projections {@code V61}'s trigger maintains are the balances, so a receipt's whole write is one movement.
 * The mocks that pinned the lock order are gone with them, and the engine's constructor no longer accepts the
 * legacy repositories at all — a receipt that went back to locking one could not compile, which is a stronger
 * guarantee than a verification here.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StockLedgerEngine receipt")
class StockLedgerEngineTest {

    private static final UUID VARIANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /** The company that owns this fixture's store, derived through the chain the resolver walks. */
    private static final UUID STORE_COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c0");

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

    @Mock
    private StoreInventorySettingsRepository storeInventorySettingsRepository;

    @Mock
    private CompanyStoreRepository companyStoreRepository;

    /** The ledger's writer is a collaborator here: this suite is about what the receipt decides. */
    @Mock
    private StockLedgerWriter stockLedgerWriter;

    /**
     * The ledger's position. A collaborator like the writer above: a receipt never reads it — the position gate
     * is a sale's — and the projection is moved by the trigger {@code V61} installs, not by this engine.
     */
    @Mock
    private StockPositionRepository stockPositionRepository;

    private StockLedgerEngine stockLedgerEngine;

    /**
     * The store of the fixture, with the whole company &rarr; country &rarr; region &rarr; zone &rarr; store
     * chain: the engine derives the tenant from the store argument, so a fixture without the chain would be
     * asking a question it cannot answer.
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
        stockLedgerEngine = new StockLedgerEngine(
                productVariantRepository,
                productVariantStoreStockRepository,
                storeInventorySettingsRepository,
                companyStoreRepository,
                new CompanyStoreOwnerResolver(),
                stockLedgerWriter,
                stockPositionRepository);

        // The engine derives the tenant from the store argument before it reads the definition, so every
        // non-quantity path needs the fixture's store to exist. Lenient because the quantity guard rejects
        // before the store is read.
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

    private ProductVariantStoreStock storeRowWithCost(String cost) {
        return ProductVariantStoreStock.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000003"))
                .productVariantId(VARIANT_ID)
                .companyStoreId(STORE_ID)
                .costPrice(cost != null ? new BigDecimal(cost) : null)
                .build();
    }

    private void applyReceipt(String quantity) {
        stockLedgerEngine.applyReceipt(
                VARIANT_ID,
                STORE_ID,
                LOCATION_ID,
                new BigDecimal(quantity),
                StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE,
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
            assertThatThrownBy(() -> stockLedgerEngine.applyReceipt(
                            VARIANT_ID, STORE_ID, LOCATION_ID, null, "GOODS_RECEIPT", null, "tester"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be greater than zero");

            assertNoRepositoryInteraction();
        }

        private void assertNoRepositoryInteraction() {
            verifyNoInteractions(productVariantRepository);
            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(companyStoreRepository);
            verifyNoInteractions(stockLedgerWriter);
        }
    }

    @Nested
    @DisplayName("store guard")
    class StoreGuardTests {

        @Test
        @DisplayName("should reject a variant with no stock row in the supplied store and book nothing")
        void rejectsVariantWithNoStoreRow() {
            var otherStoreId = UUID.fromString("00000000-0000-0000-0000-0000000000ee");

            // The other store exists and belongs to the same company; what it lacks is this variant's stock
            // row, which is the placement error this case is about — and the row that records what the goods
            // cost, so a receipt has nothing to denominate.
            when(companyStoreRepository.findById(otherStoreId))
                    .thenReturn(Optional.of(storeOwnedBy(STORE_COMPANY_ID, otherStoreId)));
            when(productVariantRepository.findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID))
                    .thenReturn(Optional.of(enabledVariant()));
            when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreId(VARIANT_ID, otherStoreId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> stockLedgerEngine.applyReceipt(
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

            verifyNoInteractions(stockLedgerWriter);
        }

        @Test
        @DisplayName("should refuse a variant the store's company does not own and write nothing")
        void refusesVariantOfAnotherCompany() {
            // The row exists, is enabled and has a stock row in this very store: the pair the schema cannot
            // forbid (company_stores carries no company_id), so the company the store derives is the only thing
            // that can refuse it — the lookup scoped to that company answers nothing, exactly as it would for a
            // variant that never existed.
            when(productVariantRepository.findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> applyReceipt("3.00")).isInstanceOf(ProductVariantNotFoundException.class);

            verify(productVariantRepository).findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID);
            verifyNoInteractions(stockLedgerWriter);
            verifyNoInteractions(productVariantStoreStockRepository);
        }
    }

    @Nested
    @DisplayName("receipt write path")
    class ReceiptWriteTests {

        @Test
        @DisplayName("should book exactly one RECEIPT row, sourced by the receipt line")
        void receiptBooksOneLedgerRow() {
            var referenceId = UUID.randomUUID();

            when(productVariantRepository.findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID))
                    .thenReturn(Optional.of(enabledVariant()));
            when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreId(VARIANT_ID, STORE_ID))
                    .thenReturn(Optional.of(storeRowWithCost("7.25")));
            when(stockLedgerWriter.operatingCurrency(STORE_COMPANY_ID)).thenReturn("MXN");

            stockLedgerEngine.applyReceipt(
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
            assertThat(booking.companyId()).isEqualTo(STORE_COMPANY_ID);
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

            // The movement is the WHOLE write: nothing else is persisted, and the store row is only read.
            verify(productVariantStoreStockRepository, never()).save(any(ProductVariantStoreStock.class));
            verifyNoInteractions(stockPositionRepository);
        }

        @Test
        @DisplayName("should book at zero cost with the reason stated when the store records no cost")
        void unrecordedCostIsBookedAtZeroWithTheReason() {
            when(productVariantRepository.findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID))
                    .thenReturn(Optional.of(enabledVariant()));
            when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreId(VARIANT_ID, STORE_ID))
                    .thenReturn(Optional.of(storeRowWithCost(null)));
            when(stockLedgerWriter.operatingCurrency(STORE_COMPANY_ID)).thenReturn("MXN");

            applyReceipt("4.00");

            // A receipt of stock that has no recorded cost is still a receipt: it is booked at zero and the row
            // says why, instead of the receipt failing on a check the operator cannot satisfy.
            verify(stockLedgerWriter)
                    .bookReceipt(
                            any(StockLedgerWriter.Booking.class),
                            any(BigDecimal.class),
                            any(BigDecimal.class),
                            any(String.class),
                            any(String.class));
            var reasonCaptor = ArgumentCaptor.forClass(String.class);
            var costCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            verify(stockLedgerWriter)
                    .bookReceipt(
                            any(StockLedgerWriter.Booking.class),
                            any(BigDecimal.class),
                            costCaptor.capture(),
                            any(String.class),
                            reasonCaptor.capture());
            assertThat(costCaptor.getValue()).isEqualByComparingTo("0");
            assertThat(reasonCaptor.getValue()).contains("no cost is recorded");
        }
    }
}
