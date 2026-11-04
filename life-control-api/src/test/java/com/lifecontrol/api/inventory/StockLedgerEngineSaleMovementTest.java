package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.inventory.exception.InsufficientStockException;
import com.lifecontrol.api.inventory.exception.StoreInventorySettingsNotFoundException;
import com.lifecontrol.api.inventory.ledger.StockLedgerEngine;
import com.lifecontrol.api.inventory.ledger.StockLedgerEngine.SaleChange;
import com.lifecontrol.api.inventory.ledger.StockLedgerWriter;
import com.lifecontrol.api.inventory.model.StockPosition;
import com.lifecontrol.api.inventory.model.StoreInventorySettings;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Unit coverage of the sale movement engine: {@link StockLedgerEngine#applySaleDeduction} (priority
 * allocation with FIFO spillover, the two fail-closed checks and the {@code SALE} ledger rows),
 * {@link StockLedgerEngine#applySaleReversal} (ledger-driven reversal, idempotent by construction),
 * {@link StockLedgerEngine#applySaleBatch} (the ordering rules the engine owns for a whole batch) and
 * {@link StockLedgerEngine#applyStockAdjustment} (the manual per-store editor of W3-D14).
 *
 * <p>Persistence behaviour on real PostgreSQL stays covered by {@code InventoryIntegrationTest}.</p>
 *
 * <p><b>What this suite asserts, and where.</b> The engine writes the ledger and nothing else: the legacy
 * {@code inventory_movements} rows and both legacy {@code stock} columns are gone, and {@code stock_balance} /
 * {@code stock_position} are projections the {@code V61} trigger derives from every {@code stock_movement} row.
 * A case therefore reads the <b>bookings</b> ({@code bookSale}, {@code bookSaleReversal},
 * {@code bookAdjustmentIn}, {@code bookAdjustmentOut}) and the positions the engine decides on, and never the
 * two columns: asserting those would be asserting this fixture's own arithmetic, since nothing in the
 * application writes them any more.</p>
 *
 * <p><b>The serialization point moved, and the suite moved with it.</b> Since the mirror was retired
 * ({@code odd/tasks/ledger-mirror-retirement.md}, T4) no legacy row is locked: the sale and the manual edit read
 * {@code stock_position} {@code FOR UPDATE}
 * ({@link StockPositionRepository#findByCompanyStoreIdAndProductVariantIdForUpdate}) before they decide
 * anything, and the batch takes those reads in ascending {@code (store, variant)} order. The legacy lock-order
 * cases are gone rather than rewritten in place, because the repositories they ordered are no longer constructor
 * arguments of the engine; what replaced them is in {@code SerializationPointTests}, which asserts the order of
 * the position read and the booking it authorizes.</p>
 *
 * <p><b>The store row is still read, and it is read without a lock.</b> Receipt, reversal and manual edit keep
 * their store-membership guard through
 * {@link ProductVariantStoreStockRepository#findByProductVariantIdAndCompanyStoreId} — the non-locking finder — so
 * a fixture that stubs the pessimistic read would be stating a call the engine no longer makes. A sale does not
 * read the row at all: a store that does not stock the variant is refused by the position gate as zero sellable
 * stock (409), which is the same answer a drained store gets.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StockLedgerEngine sale movement engine")
class StockLedgerEngineSaleMovementTest {

    private static final UUID VARIANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID LOCATION_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID LOCATION_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID LOCATION_C = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final UUID UNKNOWN_LOCATION = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private static final UUID REFERENCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final String REFERENCE_TYPE = "SALES_ORDER_ITEM";
    private static final String ACTOR = "seller";

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

    @Mock
    private StoreInventorySettingsRepository storeInventorySettingsRepository;

    @Mock
    private CompanyStoreRepository companyStoreRepository;

    /**
     * The ledger's writer: the only place this engine's writes can be observed. The legacy
     * {@code inventory_movements} rows and the two legacy {@code stock} columns are written by nothing, so a
     * case that asserted them would be asserting its own fixture.
     */
    @Mock
    private StockLedgerWriter stockLedgerWriter;

    /**
     * The ledger's position, which since the mirror's retirement is both the number a sale is refused by and the
     * row a sale and a manual edit serialize on. It is the only aggregate this suite states: the legacy store
     * column stopped being written, so seeding it would describe a world the code no longer lives in.
     */
    @Mock
    private StockPositionRepository stockPositionRepository;

    private StockLedgerEngine stockLedgerEngine;

    /** What the ledger's projection holds at each location: the balance every mover reads and moves. */
    private final Map<UUID, BigDecimal> ledgerBalances = new LinkedHashMap<>();

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
                .companyKey("SA-KEY")
                .companyName("Sales Movement Test Company")
                .rfc("SALE123456ABC")
                .enabled(true)
                .build();
        var country =
                CompanyCountry.builder().id(UUID.randomUUID()).company(company).build();
        var region = CompanyRegion.builder()
                .id(UUID.randomUUID())
                .companyCountry(country)
                .regionCode("01")
                .regionName("Sales Region")
                .enabled(true)
                .build();
        var zone = CompanyZone.builder()
                .id(UUID.randomUUID())
                .companyRegion(region)
                .zoneCode("01")
                .zoneName("Sales Zone")
                .enabled(true)
                .build();
        return CompanyStore.builder()
                .id(storeId)
                .companyZone(zone)
                .storeName("Sales Movement Test Store")
                .enabled(true)
                .build();
    }

    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;
    private Level previousLogLevel;

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

        // The engine derives the tenant from the store argument before it reads the definition, so
        // every non-quantity path needs the fixture's store to exist. Lenient because the quantity
        // guard rejects before the store is read.
        lenient()
                .when(companyStoreRepository.findById(STORE_ID))
                .thenReturn(Optional.of(storeOwnedBy(STORE_COMPANY_ID, STORE_ID)));

        serviceLogger = (Logger) LoggerFactory.getLogger(StockLedgerEngine.class);
        previousLogLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.WARN);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogCapture() {
        serviceLogger.detachAppender(logAppender);
        serviceLogger.setLevel(previousLogLevel);
    }

    // ---------------------------------------------------------------- fixtures

    private void givenEnabledVariant() {
        when(productVariantRepository.findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID))
                .thenReturn(Optional.of(ProductVariant.builder()
                        .id(VARIANT_ID)
                        .barCode("7501234567890")
                        .variantName("Talla M")
                        .enabled(true)
                        .build()));
    }

    /**
     * The store stocks the variant — the row the receipt, the reversal and the manual edit read for membership —
     * and the ledger holds {@code quantity} for it.
     *
     * <p><b>The number is the position's, and only the position's.</b> Nothing writes
     * {@code product_variant_store_stock.stock} any more: a fixture that seeded it would state a second balance
     * that neither the availability gate nor the split ever reads. What the row still gives the engine is tenant
     * attribution and the store-membership guard, and it is handed over by a read that takes <b>no lock</b> —
     * stubbing the pessimistic finder would be pinning a call the engine no longer makes, and a sale does not
     * read the row at all (it is refused by the position gate).</p>
     */
    private void givenStoreStock(String quantity) {
        lenient()
                .when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreId(VARIANT_ID, STORE_ID))
                .thenReturn(Optional.of(ProductVariantStoreStock.builder()
                        .id(UUID.fromString("00000000-0000-0000-0000-000000000003"))
                        .productVariantId(VARIANT_ID)
                        .companyStoreId(STORE_ID)
                        .build()));
        givenThePosition(VARIANT_ID, quantity);
    }

    /**
     * States the ledger's position of one key — the number a sale is refused by, the number a manual edit's delta
     * is taken from, and the row both of them lock before deciding; {@code null} means the key has no position row
     * at all.
     *
     * <p>It stubs the <b>locking</b> read, which is the one the engine takes: the gate reads the projection row
     * {@code FOR UPDATE} so it serializes on the row it decides about, and a fixture that stubbed the plain read
     * would leave the gate answering zero. The row is mocked rather than built because {@link StockPosition} is a
     * projection with no constructor for one, which is itself the design: nothing in this application authors
     * it.</p>
     *
     * <p>Lenient because some cases are refused before this read — a stubbing nobody exercised must not fail a
     * case whose subject is a different gate.</p>
     */
    private void givenThePosition(UUID variantId, String quantity) {
        var position = mock(StockPosition.class);
        lenient().when(position.getQuantity()).thenReturn(quantity == null ? null : new BigDecimal(quantity));
        lenient()
                .when(stockPositionRepository.findByCompanyStoreIdAndProductVariantIdForUpdate(STORE_ID, variantId))
                .thenReturn(quantity == null ? Optional.empty() : Optional.of(position));
    }

    private void givenSalesLocation(UUID locationId) {
        when(storeInventorySettingsRepository.findByCompanyStoreId(STORE_ID))
                .thenReturn(Optional.of(StoreInventorySettings.builder()
                        .companyStoreId(STORE_ID)
                        .receivingLocationId(LOCATION_C)
                        .salesLocationId(locationId)
                        .build()));
    }

    private void givenNoStoreInventorySettings() {
        when(storeInventorySettingsRepository.findByCompanyStoreId(STORE_ID)).thenReturn(Optional.empty());
    }

    /**
     * Declares what the ledger holds at one location: the balance a sale's split, a manual decrease and a
     * reversal all move.
     *
     * <p>There is no second row to declare beside it. {@code product_variant_locations.stock} is written by
     * nobody and nothing locks the row any more, so a fixture that registered one would be describing a lock the
     * engine no longer takes.</p>
     */
    private void balance(UUID locationId, String quantity) {
        ledgerBalances.put(locationId, quantity == null ? BigDecimal.ZERO : new BigDecimal(quantity));
    }

    /**
     * Registers the store's balance rows in the exact order the ledger's read returns them (oldest row
     * first, {@code store_location_id} as the tie-break).
     */
    private void givenFifoOrder(UUID... locationIdsInFifoOrder) {
        lenient()
                .when(stockLedgerWriter.openBalances(STORE_ID, VARIANT_ID))
                .thenReturn(Arrays.stream(locationIdsInFifoOrder)
                        .map(locationId ->
                                new StockLedgerWriter.LocationBalance(locationId, ledgerBalances.get(locationId)))
                        .toList());
    }

    /**
     * One {@code SALE} row the ledger still holds open: the identity a reversal has to name, the location it
     * balances and the quantity it moved.
     */
    private static StockLedgerWriter.OpenSale openSale(UUID variantId, UUID locationId, String quantity) {
        return new StockLedgerWriter.OpenSale(
                UUID.randomUUID(), STORE_ID, locationId, variantId, new BigDecimal(quantity));
    }

    /**
     * What the ledger holds open for a reference: the set a reversal credits.
     *
     * <p>The fixture states it directly instead of deriving it from legacy rows, because that derivation is
     * exactly what a reversal stopped doing — the ledger answers by itself which {@code SALE} rows no reversal
     * names yet ({@code StockLedgerWriter#openSales}), and an empty set is the whole answer for "nothing was
     * sold, or everything is already reversed".</p>
     */
    private void givenOpenSales(StockLedgerWriter.OpenSale... sales) {
        lenient()
                .when(stockLedgerWriter.openSales(REFERENCE_TYPE, REFERENCE_ID))
                .thenReturn(List.of(sales));
    }

    private void deduct(String quantity) {
        stockLedgerEngine.applySaleDeduction(
                VARIANT_ID, STORE_ID, new BigDecimal(quantity), REFERENCE_TYPE, REFERENCE_ID, ACTOR);
    }

    private void reverse() {
        stockLedgerEngine.applySaleReversal(REFERENCE_TYPE, REFERENCE_ID, ACTOR);
    }

    /** One booking of the ledger: what the writer was told to record, and for how much. */
    private record Booked(StockLedgerWriter.Booking booking, BigDecimal quantity) {}

    /** One reversal the ledger was told to book: the row it frees, and how much it gives back. */
    private record BookedReversal(StockLedgerWriter.Booking booking, BigDecimal quantity, UUID reversesMovementId) {}

    /**
     * Every {@code SALE} row the engine booked, in the order it booked them.
     *
     * <p>This is the one side a balance change has: the movement is the fact and the projections follow from it,
     * so a case that wants to say "this location gave 3" says it here.</p>
     */
    private List<Booked> bookedSales() {
        var bookings = ArgumentCaptor.forClass(StockLedgerWriter.Booking.class);
        var quantities = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stockLedgerWriter, atLeastOnce()).bookSale(bookings.capture(), quantities.capture());
        return zip(bookings.getAllValues(), quantities.getAllValues());
    }

    /** Every {@code ADJUSTMENT_OUT} row a manual decrease booked, in the order it booked them. */
    private List<Booked> bookedDecreases() {
        var bookings = ArgumentCaptor.forClass(StockLedgerWriter.Booking.class);
        var quantities = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stockLedgerWriter, atLeastOnce()).bookAdjustmentOut(bookings.capture(), quantities.capture());
        return zip(bookings.getAllValues(), quantities.getAllValues());
    }

    /** Every {@code SALE_REVERSAL} row the engine booked, with the movement each one names. */
    private List<BookedReversal> bookedReversals() {
        var bookings = ArgumentCaptor.forClass(StockLedgerWriter.Booking.class);
        var quantities = ArgumentCaptor.forClass(BigDecimal.class);
        var reverses = ArgumentCaptor.forClass(UUID.class);
        verify(stockLedgerWriter, atLeastOnce())
                .bookSaleReversal(bookings.capture(), quantities.capture(), reverses.capture());
        var reversals = new ArrayList<BookedReversal>(bookings.getAllValues().size());
        for (var index = 0; index < bookings.getAllValues().size(); index++) {
            reversals.add(new BookedReversal(
                    bookings.getAllValues().get(index),
                    quantities.getAllValues().get(index),
                    reverses.getAllValues().get(index)));
        }
        return reversals;
    }

    private static List<Booked> zip(List<StockLedgerWriter.Booking> bookings, List<BigDecimal> quantities) {
        var booked = new ArrayList<Booked>(bookings.size());
        for (var index = 0; index < bookings.size(); index++) {
            booked.add(new Booked(bookings.get(index), quantities.get(index)));
        }
        return booked;
    }

    // ---------------------------------------------------------------- quantity guard

    @Nested
    @DisplayName("deduction quantity guard")
    class DeductionQuantityGuardTests {

        @Test
        @DisplayName("should reject zero without touching any repository")
        void rejectsZero() {
            assertThatThrownBy(() -> deduct("0.00"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be greater than zero");

            assertNoRepositoryInteraction();
        }

        @Test
        @DisplayName("should reject a negative quantity without touching any repository")
        void rejectsNegative() {
            assertThatThrownBy(() -> deduct("-1.00"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be greater than zero");

            assertNoRepositoryInteraction();
        }

        @Test
        @DisplayName("should reject a null quantity without touching any repository")
        void rejectsNull() {
            assertThatThrownBy(() -> stockLedgerEngine.applySaleDeduction(
                            VARIANT_ID, STORE_ID, null, REFERENCE_TYPE, REFERENCE_ID, ACTOR))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be greater than zero");

            assertNoRepositoryInteraction();
        }

        private void assertNoRepositoryInteraction() {
            verifyNoInteractions(productVariantRepository);
            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(stockPositionRepository);
            verifyNoInteractions(storeInventorySettingsRepository);
            verifyNoInteractions(stockLedgerWriter);
        }
    }

    // ---------------------------------------------------------------- allocation order

    @Nested
    @DisplayName("priority allocation with FIFO spillover")
    class AllocationOrderTests {

        @Test
        @DisplayName("should drain the sales location first and then continue in FIFO order")
        void drainsSalesLocationFirstThenFifo() {
            givenEnabledVariant();
            givenStoreStock("20.00");
            givenSalesLocation(LOCATION_B);
            balance(LOCATION_A, "2.00");
            balance(LOCATION_B, "5.00");
            balance(LOCATION_C, "7.00");
            givenFifoOrder(LOCATION_A, LOCATION_B, LOCATION_C);

            deduct("6.00");

            // 5 from the sales location B, then 1 from A: the FIFO prefix after B. The ledger's bookings are the
            // only side a balance has — the legacy rows and the store column are written by nobody, so a case
            // that asserted them would be asserting its own fixture.
            var booked = bookedSales();
            assertThat(booked)
                    .extracting(entry -> entry.booking().storeLocationId(), Booked::quantity)
                    .containsExactly(
                            tuple(LOCATION_B, new BigDecimal("5.00")), tuple(LOCATION_A, new BigDecimal("1.00")));
            // C held 7 and was never reached, so it contributed no row.
            assertThat(booked)
                    .extracting(entry -> entry.booking().storeLocationId())
                    .doesNotContain(LOCATION_C);

            assertThat(booked).allSatisfy(entry -> {
                assertThat(entry.booking().sourceType())
                        .as("the movement names the line the sale was written under (W3-D6)")
                        .isEqualTo(REFERENCE_TYPE);
                assertThat(entry.booking().sourceId()).isEqualTo(REFERENCE_ID);
                assertThat(entry.booking().writeSeq())
                        .as("the six-argument deduction books the reference's only write")
                        .isEqualTo(StockLedgerWriter.SINGLE_WRITE);
                assertThat(entry.booking().actor()).isEqualTo(ACTOR);
            });
        }

        @Test
        @DisplayName("should fall back to plain FIFO when the sales location holds no balance of the variant")
        void fallsBackToFifoWhenSalesLocationHasNoBalanceOfTheVariant() {
            givenEnabledVariant();
            givenStoreStock("10.00");
            givenSalesLocation(UNKNOWN_LOCATION);
            balance(LOCATION_A, "1.00");
            balance(LOCATION_B, "1.00");
            givenFifoOrder(LOCATION_A, LOCATION_B);

            deduct("2.00");

            // The priority location holds no balance of this variant, so the split is plain FIFO: one booking
            // per location, each for the whole balance it held.
            assertThat(bookedSales())
                    .extracting(entry -> entry.booking().storeLocationId(), Booked::quantity)
                    .containsExactly(
                            tuple(LOCATION_A, new BigDecimal("1.00")), tuple(LOCATION_B, new BigDecimal("1.00")));
        }

        @Test
        @DisplayName("should skip a location whose balance is already zero")
        void skipsEmptyLocations() {
            givenEnabledVariant();
            givenStoreStock("10.00");
            givenSalesLocation(LOCATION_A);
            balance(LOCATION_A, "0.00");
            balance(LOCATION_B, "4.00");
            givenFifoOrder(LOCATION_A, LOCATION_B);

            deduct("2.00");

            // A is the priority location but holds nothing, so it contributes no movement and only B is drawn.
            assertThat(bookedSales())
                    .extracting(entry -> entry.booking().storeLocationId(), Booked::quantity)
                    .containsExactly(tuple(LOCATION_B, new BigDecimal("2.00")));
        }

        @Test
        @DisplayName("should drain the sales location to zero and spill the remainder to the next location")
        void spilloverBoundaryDrainsThePriorityLocation() {
            givenEnabledVariant();
            givenStoreStock("8.00");
            givenSalesLocation(LOCATION_B);
            balance(LOCATION_A, "5.00");
            balance(LOCATION_B, "3.00");
            givenFifoOrder(LOCATION_A, LOCATION_B);

            deduct("5.00");

            // The priority location gives its whole 3 and the remainder spills to A: two bookings, one fact each.
            assertThat(bookedSales())
                    .extracting(entry -> entry.booking().storeLocationId(), Booked::quantity)
                    .containsExactly(
                            tuple(LOCATION_B, new BigDecimal("3.00")), tuple(LOCATION_A, new BigDecimal("2.00")));
        }
    }

    // ---------------------------------------------------------------- fail closed

    @Nested
    @DisplayName("fail-closed checks")
    class FailClosedTests {

        @Test
        @DisplayName("should fail closed on the position before reading the locations")
        void failsClosedOnThePosition() {
            givenEnabledVariant();
            givenStoreStock("4.00");

            assertThatThrownBy(() -> deduct("6.00"))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("requested 6.00, available 4.00");

            // The gate is the position, and nothing after it ran: no settings row was read and no location was
            // consulted, so the store is untouched instead of partially deducted.
            verifyNoInteractions(storeInventorySettingsRepository);
            verifyNoInteractions(stockLedgerWriter);
            // The sale never asks whether the store stocks the variant: the projection answers that question, and
            // the store row is not the quantity authority it used to be.
            verifyNoInteractions(productVariantStoreStockRepository);
        }

        @Test
        @DisplayName("should fail closed when the location rows cannot cover the position")
        void failsClosedWhenTheLocationsCannotCoverThePosition() {
            givenEnabledVariant();
            givenStoreStock("10.00");
            givenSalesLocation(LOCATION_A);
            balance(LOCATION_A, "2.00");
            balance(LOCATION_B, "2.00");
            givenFifoOrder(LOCATION_A, LOCATION_B);

            assertThatThrownBy(() -> deduct("6.00"))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("requested 6.00, available 4.00");

            // The second check runs before the first booking: the writer read the balances and was told to write
            // nothing.
            verify(stockLedgerWriter, never()).bookSale(any(), any());
            verifyNoInteractions(productVariantStoreStockRepository);
        }

        @Test
        @DisplayName("should fail closed with InsufficientStockException when the key has no position row")
        void rejectsVariantWithNoPositionRow() {
            givenEnabledVariant();
            givenThePosition(VARIANT_ID, null);

            // A store that does not stock the variant has no position row, and an absent row is used as zero
            // (fail-closed). That is a sale of unavailable stock — InsufficientStockException, the 409 the API
            // documents — not a programmer error: the store row is not consulted any more, so its absence can no
            // longer be the thing that refuses the sale.
            assertThatThrownBy(() -> deduct("1.00"))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining(VARIANT_ID.toString())
                    .hasMessageContaining("requested 1.00, available 0");

            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(stockLedgerWriter);
        }

        @Test
        @DisplayName("should reject an unknown variant with ProductVariantNotFoundException")
        void rejectsUnknownVariant() {
            when(productVariantRepository.findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> deduct("1.00")).isInstanceOf(ProductVariantNotFoundException.class);

            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(stockPositionRepository);
            verifyNoInteractions(stockLedgerWriter);
        }

        @Test
        @DisplayName("should refuse a sale of another company's variant and write nothing")
        void refusesSaleOfAnotherCompanysVariant() {
            // Stocked, enabled, sellable in the abstract — and owned by another company, so the
            // lookup asked with the store's company answers nothing. A deduction that resolves the
            // variant by bare id moves another tenant's balance; the store it is sold from already
            // says which tenant may be touched.
            when(productVariantRepository.findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> deduct("2.00")).isInstanceOf(ProductVariantNotFoundException.class);

            // The only company asked about is the one that owns the store.
            verify(productVariantRepository).findByIdAndCompanyIdAndEnabledTrue(VARIANT_ID, STORE_COMPANY_ID);
            // Nothing was decided and nothing was locked: no position read, no SALE row, and the store row was
            // never even looked for.
            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(stockPositionRepository);
            verifyNoInteractions(stockLedgerWriter);
        }
    }

    // ---------------------------------------------------------------- ledger shape

    @Nested
    @DisplayName("SALE ledger rows")
    class SaleLedgerShapeTests {

        @Test
        @DisplayName("should append one positive SALE row per consumed location with the reference set")
        void writesOnePositiveSaleRowPerConsumedLocation() {
            givenEnabledVariant();
            givenStoreStock("10.00");
            givenSalesLocation(LOCATION_B);
            balance(LOCATION_A, "5.00");
            balance(LOCATION_B, "3.00");
            givenFifoOrder(LOCATION_A, LOCATION_B);

            deduct("5.00");

            var booked = bookedSales();
            assertThat(booked).hasSize(2);
            for (var entry : booked) {
                assertThat(entry.booking().productVariantId()).isEqualTo(VARIANT_ID);
                assertThat(entry.booking().companyStoreId()).isEqualTo(STORE_ID);
                assertThat(entry.booking().companyId()).isEqualTo(STORE_COMPANY_ID);
                assertThat(entry.booking().sourceType()).isEqualTo(REFERENCE_TYPE);
                assertThat(entry.booking().sourceId()).isEqualTo(REFERENCE_ID);
                assertThat(entry.booking().actor()).isEqualTo(ACTOR);
                // The direction is the writer's, not the caller's: bookSale is the SALE row, and the quantity
                // it moves is positive.
                assertThat(entry.quantity()).isPositive();
            }
            assertThat(booked)
                    .extracting(entry -> entry.booking().storeLocationId(), Booked::quantity)
                    .containsExactly(
                            tuple(LOCATION_B, new BigDecimal("3.00")), tuple(LOCATION_A, new BigDecimal("2.00")));
        }
    }

    // ---------------------------------------------------------------- reversal

    @Nested
    @DisplayName("ledger-driven reversal")
    class ReversalTests {

        @Test
        @DisplayName("should credit each location exactly what it gave, without re-running the priority allocation")
        void creditsEachLocationExactlyWhatItGave() {
            givenStoreStock("10.00");
            balance(LOCATION_A, "3.00");
            balance(LOCATION_B, "0.00");
            var soldFromB = openSale(VARIANT_ID, LOCATION_B, "3.00");
            var soldFromA = openSale(VARIANT_ID, LOCATION_A, "2.00");
            givenOpenSales(soldFromB, soldFromA);

            reverse();

            // Each location gives back exactly what it gave, and each reversal names the row it frees: the
            // amount is the open rows themselves, so no second arithmetic could disagree with them.
            var reversals = bookedReversals();
            assertThat(reversals)
                    .extracting(
                            entry -> entry.booking().storeLocationId(),
                            BookedReversal::reversesMovementId,
                            BookedReversal::quantity)
                    .containsExactly(
                            tuple(LOCATION_A, soldFromA.movementId(), new BigDecimal("2.00")),
                            tuple(LOCATION_B, soldFromB.movementId(), new BigDecimal("3.00")));

            // The reversal never consults the store's priority location (W3-D6/W3-D8).
            verifyNoInteractions(storeInventorySettingsRepository);
        }

        @Test
        @DisplayName("should not restore a location twice: the second reversal is a no-op")
        void secondReversalIsANoOp() {
            // Nothing is open: the ledger's own predicate excludes every row a reversal already names, so a
            // second reversal finds an empty set and the earlier case's arithmetic is not repeated here.
            givenOpenSales();

            reverse();

            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(stockPositionRepository);
            verify(stockLedgerWriter, never()).bookSaleReversal(any(), any(), any());
        }

        @Test
        @DisplayName("should reverse only the uncovered remainder of a partially reversed reference")
        void reversesOnlyTheUncoveredRemainder() {
            givenStoreStock("10.00");
            balance(LOCATION_A, "3.00");
            balance(LOCATION_B, "0.00");
            // B's sale is already named by a reversal, so the ledger does not report it: only A's row is open.
            var soldFromA = openSale(VARIANT_ID, LOCATION_A, "2.00");
            givenOpenSales(soldFromA);

            reverse();

            var reversals = bookedReversals();
            assertThat(reversals).hasSize(1);
            assertThat(reversals.getFirst().reversesMovementId()).isEqualTo(soldFromA.movementId());
            assertThat(reversals.getFirst().booking().storeLocationId()).isEqualTo(LOCATION_A);
            assertThat(reversals.getFirst().quantity()).isEqualByComparingTo("2.00");
        }

        @Test
        @DisplayName("should do nothing when the reference has no open SALE movement at all")
        void unknownReferenceIsANoOp() {
            givenOpenSales();

            reverse();

            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(stockPositionRepository);
            verify(stockLedgerWriter, never()).bookSaleReversal(any(), any(), any());
        }

        @Test
        @DisplayName("should reject a reversal without a usable reference")
        void rejectsMissingReference() {
            assertThatThrownBy(() -> stockLedgerEngine.applySaleReversal(REFERENCE_TYPE, null, ACTOR))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> stockLedgerEngine.applySaleReversal("  ", REFERENCE_ID, ACTOR))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(stockPositionRepository);
            verifyNoInteractions(storeInventorySettingsRepository);
            verifyNoInteractions(stockLedgerWriter);
        }
    }

    // ---------------------------------------------------------------- missing settings row (W3-D8)

    @Nested
    @DisplayName("store without a settings row")
    class MissingSettingsRowTests {

        @Test
        @DisplayName("should allocate FIFO and log exactly one warning instead of failing closed")
        void allocatesFifoAndWarnsOnce() {
            givenEnabledVariant();
            givenStoreStock("10.00");
            givenNoStoreInventorySettings();
            balance(LOCATION_A, "1.00");
            balance(LOCATION_B, "1.00");
            givenFifoOrder(LOCATION_A, LOCATION_B);

            deduct("2.00");

            // A missing configuration row falls back to plain FIFO: one booking per location it draws from.
            assertThat(bookedSales())
                    .extracting(entry -> entry.booking().storeLocationId(), Booked::quantity)
                    .containsExactly(
                            tuple(LOCATION_A, new BigDecimal("1.00")), tuple(LOCATION_B, new BigDecimal("1.00")));

            assertThat(logAppender.list).hasSize(1);
            assertThat(logAppender.list.get(0).getLevel()).isEqualTo(Level.WARN);
            assertThat(logAppender.list.get(0).getFormattedMessage()).contains(STORE_ID.toString());
        }

        @Test
        @DisplayName("should log no warning when the store has a settings row")
        void warnsNothingWhenSettingsExist() {
            givenEnabledVariant();
            givenStoreStock("10.00");
            givenSalesLocation(LOCATION_A);
            balance(LOCATION_A, "2.00");
            givenFifoOrder(LOCATION_A);

            deduct("1.00");

            assertThat(logAppender.list).isEmpty();
        }

        @Test
        @DisplayName("should reverse without consulting the settings row and without warning")
        void reversalNeedsNoSettingsRow() {
            givenStoreStock("10.00");
            balance(LOCATION_A, "0.00");
            givenOpenSales(openSale(VARIANT_ID, LOCATION_A, "2.00"));

            reverse();

            var reversals = bookedReversals();
            assertThat(reversals).hasSize(1);
            assertThat(reversals.getFirst().booking().storeLocationId()).isEqualTo(LOCATION_A);
            assertThat(reversals.getFirst().quantity()).isEqualByComparingTo("2.00");
            assertThat(logAppender.list).isEmpty();
            verifyNoInteractions(storeInventorySettingsRepository);
        }
    }

    // ---------------------------------------------------------------- serialization point

    /**
     * Where the serialization of two movers of one {@code (store, variant)} happens now that no legacy row is
     * locked: on {@code stock_position}, the row the decision is about.
     *
     * <p>This group replaces the legacy lock-order cases. Those ordered
     * {@code product_variant_store_stock FOR UPDATE} before {@code product_variant_locations FOR UPDATE} and
     * existed only to keep the dual-write window's two locks in a fixed order; both locks are gone, and a case
     * that kept ordering them would be asserting calls the engine cannot even reach — the repositories are no
     * longer constructor arguments. What survives is the rule underneath: the gate read comes first, and the
     * booking it authorizes comes after it.</p>
     */
    @Nested
    @DisplayName("serialization point")
    class SerializationPointTests {

        @Test
        @DisplayName("should read the position FOR UPDATE before booking the sale")
        void saleReadsThePositionBeforeBooking() {
            givenEnabledVariant();
            givenStoreStock("10.00");
            givenSalesLocation(LOCATION_B);
            balance(LOCATION_A, "5.00");
            balance(LOCATION_B, "3.00");
            givenFifoOrder(LOCATION_A, LOCATION_B);

            deduct("5.00");

            // The row the decision is about is locked before the decision is booked, so a concurrent deduction of
            // the same key queues on it instead of passing the gate on a quantity another transaction is changing.
            InOrder gate = inOrder(stockPositionRepository, stockLedgerWriter);
            gate.verify(stockPositionRepository).findByCompanyStoreIdAndProductVariantIdForUpdate(STORE_ID, VARIANT_ID);
            // The split books one row per location it drew from — two here — so the order is asserted on the
            // first booking, which has to be the first write the gate authorizes.
            gate.verify(stockLedgerWriter, atLeastOnce()).bookSale(any(), any());

            // And the legacy store row is not read at all, locked or otherwise.
            verifyNoInteractions(productVariantStoreStockRepository);
        }

        @Test
        @DisplayName("should read the position FOR UPDATE before booking a manual decrease")
        void manualDecreaseReadsThePositionBeforeBooking() {
            givenStoreStock("11.00");
            givenSalesLocation(LOCATION_A);
            balance(LOCATION_A, "11.00");
            givenFifoOrder(LOCATION_A);

            stockLedgerEngine.applyStockAdjustment(VARIANT_ID, STORE_ID, new BigDecimal("4.00"), ACTOR);

            // The delta is taken from the position, so the read that states it has to come before the movement it
            // produces — otherwise the number the operator is overriding could move under the arithmetic.
            InOrder gate = inOrder(stockPositionRepository, stockLedgerWriter);
            gate.verify(stockPositionRepository).findByCompanyStoreIdAndProductVariantIdForUpdate(STORE_ID, VARIANT_ID);
            gate.verify(stockLedgerWriter).bookAdjustmentOut(any(), any());
        }

        @Test
        @DisplayName("should reverse without taking the position gate, reading the store row without a lock")
        void reversalTakesNoPositionGate() {
            givenStoreStock("0.00");
            givenOpenSales(openSale(VARIANT_ID, LOCATION_A, "2.00"));

            reverse();

            assertThat(bookedReversals()).hasSize(1);
            // A reversal decides nothing about quantity — the ledger says what each open row moved — so it never
            // reads the projection, and it never locks the legacy store row either.
            verifyNoInteractions(stockPositionRepository);
            verify(productVariantStoreStockRepository).findByProductVariantIdAndCompanyStoreId(VARIANT_ID, STORE_ID);
            verify(productVariantStoreStockRepository, never())
                    .findByProductVariantIdAndCompanyStoreIdForUpdate(any(), any());
        }
    }

    // ---------------------------------------------------------------- sale batch (F-1)

    @Nested
    @DisplayName("sale batch")
    class SaleBatchTests {

        private static final UUID SECOND_VARIANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
        private static final UUID SECOND_REFERENCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");

        /** The second key of a batch, sellable: an enabled variant with stock at one location. */
        private void givenSecondVariantSellable() {
            lenient()
                    .when(stockLedgerWriter.openBalances(STORE_ID, SECOND_VARIANT_ID))
                    .thenReturn(List.of(new StockLedgerWriter.LocationBalance(LOCATION_A, new BigDecimal("10.00"))));
            when(productVariantRepository.findByIdAndCompanyIdAndEnabledTrue(SECOND_VARIANT_ID, STORE_COMPANY_ID))
                    .thenReturn(Optional.of(ProductVariant.builder()
                            .id(SECOND_VARIANT_ID)
                            .barCode("7501234567891")
                            .variantName("Talla L")
                            .enabled(true)
                            .build()));
            lenient()
                    .when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreId(
                            SECOND_VARIANT_ID, STORE_ID))
                    .thenReturn(Optional.of(ProductVariantStoreStock.builder()
                            .id(UUID.fromString("00000000-0000-0000-0000-000000000004"))
                            .productVariantId(SECOND_VARIANT_ID)
                            .companyStoreId(STORE_ID)
                            .build()));
            givenThePosition(SECOND_VARIANT_ID, "10.00");
        }

        /** Only what a reversal of the second key reads: the store row it checks for membership. */
        private void givenSecondVariantLedgerScope() {
            when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreId(
                            SECOND_VARIANT_ID, STORE_ID))
                    .thenReturn(Optional.of(ProductVariantStoreStock.builder()
                            .id(UUID.fromString("00000000-0000-0000-0000-000000000004"))
                            .productVariantId(SECOND_VARIANT_ID)
                            .companyStoreId(STORE_ID)
                            .build()));
        }

        @Test
        @DisplayName("should touch the position rows of the batch in ascending key order, not in the input order")
        void touchesThePositionRowsInKeyOrderWhateverTheInputOrder() {
            givenEnabledVariant();
            givenStoreStock("10.00");
            givenSalesLocation(LOCATION_A);
            balance(LOCATION_A, "10.00");
            givenFifoOrder(LOCATION_A);
            givenSecondVariantSellable();

            // The higher key is listed first. The position row of a key is the only thing this engine locks, so
            // the order in which the batch takes them IS the lock order: an engine that worked the batch as it
            // read it would take them the other way round and deadlock a concurrent batch that listed them in
            // the opposite order.
            stockLedgerEngine.applySaleBatch(
                    REFERENCE_TYPE,
                    ACTOR,
                    List.of(
                            SaleChange.deduction(
                                    STORE_ID, SECOND_VARIANT_ID, new BigDecimal("1.00"), SECOND_REFERENCE_ID),
                            SaleChange.deduction(STORE_ID, VARIANT_ID, new BigDecimal("1.00"), REFERENCE_ID)));

            // Each deduction takes the gate of its own key exactly once, so the captured sequence is the lock
            // order of the batch: sorted by (store, variant), never the caller's order.
            var lockedStores = ArgumentCaptor.forClass(UUID.class);
            var lockedVariants = ArgumentCaptor.forClass(UUID.class);
            verify(stockPositionRepository, atLeast(2))
                    .findByCompanyStoreIdAndProductVariantIdForUpdate(lockedStores.capture(), lockedVariants.capture());
            assertThat(lockedStores.getAllValues()).startsWith(STORE_ID, STORE_ID);
            assertThat(lockedVariants.getAllValues()).startsWith(VARIANT_ID, SECOND_VARIANT_ID);
        }

        @Test
        @DisplayName("should reverse a reference before the deduction that shares it, whatever the input order")
        void reversesBeforeTheDeductionOfTheSameReference() {
            givenEnabledVariant();
            givenStoreStock("10.00");
            givenSalesLocation(LOCATION_A);
            balance(LOCATION_A, "10.00");
            givenFifoOrder(LOCATION_A);
            givenSecondVariantLedgerScope();
            // One line reference with a SALE row open under the SECOND variant: the same reference the batch
            // also deducts from, under the lower key. A variant change is exactly this shape (W3-D6).
            givenOpenSales(openSale(SECOND_VARIANT_ID, LOCATION_A, "4.00"));

            // The naive order: the deduction first, even though its key sorts lower. A batch that trusted its
            // input order would write the SALE row before the reversal read the ledger, and the reversal would
            // then credit the fresh sale back instead of the sale it balances.
            stockLedgerEngine.applySaleBatch(
                    REFERENCE_TYPE,
                    ACTOR,
                    List.of(
                            SaleChange.deduction(STORE_ID, VARIANT_ID, new BigDecimal("2.00"), REFERENCE_ID),
                            SaleChange.reversal(STORE_ID, SECOND_VARIANT_ID, REFERENCE_ID)));

            // The order is asserted on the ledger's own writes: the reversal of the shared reference lands
            // before the deduction that would otherwise be credited back to it.
            InOrder phases = inOrder(stockLedgerWriter);
            phases.verify(stockLedgerWriter)
                    .bookSaleReversal(
                            argThat(booking -> booking.productVariantId().equals(SECOND_VARIANT_ID)), any(), any());
            phases.verify(stockLedgerWriter)
                    .bookSale(argThat(booking -> booking.productVariantId().equals(VARIANT_ID)), any());
        }

        @Test
        @DisplayName("should lock nothing and write nothing for an empty batch")
        void emptyBatchTouchesNothing() {
            stockLedgerEngine.applySaleBatch(REFERENCE_TYPE, ACTOR, List.of());

            verifyNoInteractions(
                    productVariantRepository,
                    productVariantStoreStockRepository,
                    storeInventorySettingsRepository,
                    stockPositionRepository,
                    stockLedgerWriter);
        }
    }

    // ---------------------------------------------------------------- manual stock adjustment

    @Nested
    @DisplayName("manual per-store stock adjustment (W3-D14)")
    class ManualStockAdjustmentTests {

        private BigDecimal adjust(String stock) {
            return stockLedgerEngine.applyStockAdjustment(VARIANT_ID, STORE_ID, new BigDecimal(stock), ACTOR);
        }

        @Test
        @DisplayName("should raise the destination location and write one positive ADJUSTMENT_IN")
        void raiseBooksOneIncreaseAtTheDestination() {
            givenStoreStock("0.00");
            givenSalesLocation(LOCATION_A);
            balance(LOCATION_A, "0.00");

            var result = adjust("5.00");

            assertThat(result).isEqualByComparingTo("5.00");

            // One inbound row, at the destination and for the delta. Its source is the movement's own id — a
            // manual edit names no document, so there is no line to point at (D-2) — and nothing else is
            // written: the projections follow from this row by trigger.
            var bookings = ArgumentCaptor.forClass(StockLedgerWriter.Booking.class);
            var quantity = ArgumentCaptor.forClass(BigDecimal.class);
            verify(stockLedgerWriter).bookAdjustmentIn(bookings.capture(), quantity.capture(), any(), any(), any());
            assertThat(quantity.getValue()).isEqualByComparingTo("5.00");
            assertThat(bookings.getValue().storeLocationId()).isEqualTo(LOCATION_A);
            assertThat(bookings.getValue().productVariantId()).isEqualTo(VARIANT_ID);
            assertThat(bookings.getValue().companyStoreId()).isEqualTo(STORE_ID);
            assertThat(bookings.getValue().sourceType()).isEqualTo(StockLedgerWriter.MANUAL_ADJUSTMENT_SOURCE_TYPE);
            assertThat(bookings.getValue().writeSeq()).isEqualTo(StockLedgerWriter.SINGLE_WRITE);
            assertThat(bookings.getValue().actor()).isEqualTo(ACTOR);
            assertThat(bookings.getValue().sourceId()).isNotNull();
        }

        @Test
        @DisplayName("should lower the destination and write a POSITIVE ADJUSTMENT_OUT (W3-D7)")
        void lowerWritesAPositiveDecrease() {
            givenStoreStock("11.00");
            givenSalesLocation(LOCATION_A);
            balance(LOCATION_A, "11.00");
            givenFifoOrder(LOCATION_A);

            var result = adjust("4.00");

            assertThat(result).isEqualByComparingTo("4.00");

            // The direction is the writer's and the quantity stays positive. The delta is taken from the position
            // (11) and not from the frozen store column, which is what makes the target land where the operator
            // asked instead of against a number nothing maintains.
            var decreases = bookedDecreases();
            assertThat(decreases).hasSize(1);
            assertThat(decreases.getFirst().quantity()).isEqualByComparingTo("7.00");
            assertThat(decreases.getFirst().booking().storeLocationId()).isEqualTo(LOCATION_A);
            assertThat(decreases.getFirst().booking().sourceType())
                    .isEqualTo(StockLedgerWriter.MANUAL_ADJUSTMENT_SOURCE_TYPE);
        }

        @Test
        @DisplayName("should allocate a decrease over the store's locations instead of driving sales negative")
        void decreaseAllocatesOverTheStoresLocations() {
            // The state applyReceipt leaves when the receiving and sales locations differ: the
            // aggregate is 10, the receiving location (B) holds all of it and the sales location (A)
            // holds none. Assigning the whole delta to sales drove it to -5 while the invariant
            // aggregate = SUM(locations) still held, so nothing detected it (F18).
            givenStoreStock("10.00");
            givenSalesLocation(LOCATION_A);
            balance(LOCATION_A, "0.00");
            balance(LOCATION_B, "10.00");
            givenFifoOrder(LOCATION_A, LOCATION_B);

            var result = adjust("5.00");

            assertThat(result).isEqualByComparingTo("5.00");

            // The whole 5 comes out of B, the location that held it: the sales location stays at zero instead
            // of being driven to -5 while the aggregate still summed right (F18).
            var decreases = bookedDecreases();
            assertThat(decreases).hasSize(1);
            assertThat(decreases.getFirst().booking().storeLocationId()).isEqualTo(LOCATION_B);
            assertThat(decreases.getFirst().quantity()).isEqualByComparingTo("5.00");
        }

        @Test
        @DisplayName("should write one ADJUSTMENT_OUT per location a spanning decrease draws from")
        void spanningDecreaseWritesOneMovementPerLocation() {
            givenStoreStock("10.00");
            givenSalesLocation(LOCATION_A);
            balance(LOCATION_A, "2.00");
            balance(LOCATION_B, "8.00");
            givenFifoOrder(LOCATION_A, LOCATION_B);

            var result = adjust("5.00");

            assertThat(result).isEqualByComparingTo("5.00");

            // One row per location drawn from, each fully allocated: the ledger answers where the number went.
            assertThat(bookedDecreases())
                    .extracting(entry -> entry.booking().storeLocationId(), Booked::quantity)
                    .containsExactly(
                            tuple(LOCATION_A, new BigDecimal("2.00")), tuple(LOCATION_B, new BigDecimal("3.00")));
        }

        @Test
        @DisplayName("should empty every location when the decrease equals the whole aggregate")
        void decreaseToZeroEmptiesEveryLocation() {
            givenStoreStock("5.00");
            givenSalesLocation(LOCATION_A);
            balance(LOCATION_A, "2.00");
            balance(LOCATION_B, "3.00");
            givenFifoOrder(LOCATION_A, LOCATION_B);

            var result = adjust("0.00");

            assertThat(result).isEqualByComparingTo("0.00");

            // Every location is drawn down to nothing and each draw is its own row, so the bookings sum to the
            // whole aggregate — which is what "empty" means in the ledger.
            assertThat(bookedDecreases())
                    .extracting(entry -> entry.booking().storeLocationId(), Booked::quantity)
                    .containsExactly(
                            tuple(LOCATION_A, new BigDecimal("2.00")), tuple(LOCATION_B, new BigDecimal("3.00")));
        }

        @Test
        @DisplayName("should fail closed when the location balances cannot cover the decrease")
        void decreaseFailsClosedWhenLocationsCannotCoverIt() {
            // The seeded state breaks aggregate = SUM(locations), which the W3-D15 reset removes and
            // the writers keep true, so the guard is unreachable through the application. It is
            // proven here directly: the edit refuses instead of writing a negative balance (W3-D1).
            givenStoreStock("10.00");
            givenSalesLocation(LOCATION_A);
            balance(LOCATION_A, "1.00");
            balance(LOCATION_B, "1.00");
            givenFifoOrder(LOCATION_A, LOCATION_B);

            assertThatThrownBy(() -> adjust("0.00"))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("requested 10.00, available 2.00");

            // Nothing was booked: the refusal happens before the first movement.
            verify(stockLedgerWriter, never()).bookAdjustmentOut(any(), any());
        }

        @Test
        @DisplayName("should refuse a store with no settings row before touching any balance")
        void refusesWithoutStoreInventorySettings() {
            givenNoStoreInventorySettings();

            assertThatThrownBy(() -> adjust("3.00"))
                    .isInstanceOf(StoreInventorySettingsNotFoundException.class)
                    .hasMessageContaining("Store inventory settings not found");

            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(stockPositionRepository);
            verifyNoInteractions(stockLedgerWriter);
        }

        @Test
        @DisplayName("should write no movement when the target equals the current aggregate")
        void sameStockWritesNoMovement() {
            givenStoreStock("5.00");
            givenSalesLocation(LOCATION_A);

            var result = adjust("5.00");

            assertThat(result).isEqualByComparingTo("5.00");
            // The delta is zero, so the ledger is told nothing: it records facts, not no-ops.
            verifyNoInteractions(stockLedgerWriter);
        }

        @Test
        @DisplayName("should reject a negative or null target without touching any repository")
        void rejectsInvalidTarget() {
            assertThatThrownBy(() -> adjust("-1.00"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be greater than or equal to zero");
            assertThatThrownBy(() -> stockLedgerEngine.applyStockAdjustment(VARIANT_ID, STORE_ID, null, ACTOR))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(storeInventorySettingsRepository);
            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(stockPositionRepository);
            verifyNoInteractions(stockLedgerWriter);
        }
    }
}
