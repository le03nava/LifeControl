package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lifecontrol.api.inventory.exception.StoreInventorySettingsNotFoundException;
import com.lifecontrol.api.inventory.model.InventoryMovement;
import com.lifecontrol.api.inventory.model.MovementType;
import com.lifecontrol.api.inventory.model.ProductVariantLocation;
import com.lifecontrol.api.inventory.model.StoreInventorySettings;
import com.lifecontrol.api.inventory.repository.InventoryMovementRepository;
import com.lifecontrol.api.inventory.repository.ProductVariantLocationRepository;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.inventory.service.InventoryService;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.model.ProductVariantStoreStock;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.salesorder.exception.InsufficientStockException;
import java.math.BigDecimal;
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
 * Unit coverage of the sale movement engine: {@link InventoryService#applySaleDeduction} (priority
 * allocation with FIFO spillover, the two fail-closed checks and the {@code SALE} ledger rows),
 * {@link InventoryService#applySaleReversal} (ledger-driven reversal, idempotent by construction)
 * and {@link InventoryService#applyStockAdjustment} (the manual per-store editor of W3-D14).
 *
 * <p>These tests replace the interim expectation that sales never touch a location row. Persistence
 * behaviour on real PostgreSQL stays covered by {@code InventoryIntegrationTest}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InventoryService sale movement engine")
class InventoryServiceSaleMovementTest {

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
    private ProductVariantLocationRepository productVariantLocationRepository;

    @Mock
    private InventoryMovementRepository inventoryMovementRepository;

    @Mock
    private StoreInventorySettingsRepository storeInventorySettingsRepository;

    private InventoryService inventoryService;

    private final Map<UUID, ProductVariantLocation> balances = new LinkedHashMap<>();

    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;
    private Level previousLogLevel;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(
                productVariantRepository,
                productVariantStoreStockRepository,
                productVariantLocationRepository,
                inventoryMovementRepository,
                storeInventorySettingsRepository);

        serviceLogger = (Logger) LoggerFactory.getLogger(InventoryService.class);
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
        when(productVariantRepository.findById(VARIANT_ID))
                .thenReturn(Optional.of(ProductVariant.builder()
                        .id(VARIANT_ID)
                        .barCode("7501234567890")
                        .variantName("Talla M")
                        .enabled(true)
                        .build()));
    }

    private void givenStoreStock(String stock) {
        when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreIdForUpdate(VARIANT_ID, STORE_ID))
                .thenReturn(Optional.of(ProductVariantStoreStock.builder()
                        .id(UUID.fromString("00000000-0000-0000-0000-000000000003"))
                        .productVariantId(VARIANT_ID)
                        .companyStoreId(STORE_ID)
                        .stock(stock != null ? new BigDecimal(stock) : null)
                        .build()));
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

    private void balance(UUID locationId, String stock) {
        balances.put(
                locationId,
                ProductVariantLocation.builder()
                        .id(UUID.randomUUID())
                        .productVariantId(VARIANT_ID)
                        .storeLocationId(locationId)
                        .stock(stock != null ? new BigDecimal(stock) : null)
                        .build());
    }

    /**
     * Registers the store's balance rows in the exact order the FIFO query returns them (oldest row
     * first, {@code store_location_id} as the tie-break).
     */
    private void givenFifoOrder(UUID... locationIdsInFifoOrder) {
        when(productVariantLocationRepository.findByProductVariantIdAndCompanyStoreId(VARIANT_ID, STORE_ID))
                .thenReturn(
                        Arrays.stream(locationIdsInFifoOrder).map(balances::get).toList());
        givenLockableBalances(locationIdsInFifoOrder);
    }

    /** Stubs the locking read of each balance row, which the reversal uses without the FIFO query. */
    private void givenLockableBalances(UUID... locationIds) {
        for (var locationId : locationIds) {
            when(productVariantLocationRepository.findByProductVariantIdAndStoreLocationIdForUpdate(
                            VARIANT_ID, locationId))
                    .thenAnswer(invocation -> Optional.of(balances.get(locationId)));
        }
    }

    private void deduct(String quantity) {
        inventoryService.applySaleDeduction(
                VARIANT_ID, STORE_ID, new BigDecimal(quantity), REFERENCE_TYPE, REFERENCE_ID, ACTOR);
    }

    private void reverse() {
        inventoryService.applySaleReversal(REFERENCE_TYPE, REFERENCE_ID, ACTOR);
    }

    private List<ProductVariantLocation> savedBalances() {
        var captor = ArgumentCaptor.forClass(ProductVariantLocation.class);
        verify(productVariantLocationRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private List<InventoryMovement> savedMovements() {
        var captor = ArgumentCaptor.forClass(InventoryMovement.class);
        verify(inventoryMovementRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private InventoryMovement movement(MovementType type, UUID locationId, String quantity) {
        return InventoryMovement.builder()
                .productVariantId(VARIANT_ID)
                .companyStoreId(STORE_ID)
                .storeLocationId(locationId)
                .movementType(type)
                .quantity(new BigDecimal(quantity))
                .referenceType(REFERENCE_TYPE)
                .referenceId(REFERENCE_ID)
                .createdBy(ACTOR)
                .build();
    }

    private void givenLedger(InventoryMovement... movements) {
        when(inventoryMovementRepository.findByReferenceTypeAndReferenceId(REFERENCE_TYPE, REFERENCE_ID))
                .thenReturn(List.of(movements));
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
            assertThatThrownBy(() -> inventoryService.applySaleDeduction(
                            VARIANT_ID, STORE_ID, null, REFERENCE_TYPE, REFERENCE_ID, ACTOR))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be greater than zero");

            assertNoRepositoryInteraction();
        }

        private void assertNoRepositoryInteraction() {
            verifyNoInteractions(productVariantRepository);
            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(productVariantLocationRepository);
            verifyNoInteractions(inventoryMovementRepository);
            verifyNoInteractions(storeInventorySettingsRepository);
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

            // 5 from the sales location B, then 1 from A: the FIFO prefix after B.
            var saved = savedBalances();
            assertThat(saved)
                    .extracting(ProductVariantLocation::getStoreLocationId)
                    .containsExactly(LOCATION_B, LOCATION_A);
            assertThat(saved)
                    .extracting(ProductVariantLocation::getStock)
                    .containsExactly(new BigDecimal("0.00"), new BigDecimal("1.00"));
            assertThat(balances.get(LOCATION_C).getStock()).isEqualByComparingTo("7.00");

            var movements = savedMovements();
            assertThat(movements)
                    .extracting(InventoryMovement::getStoreLocationId)
                    .containsExactly(LOCATION_B, LOCATION_A);
            assertThat(movements)
                    .extracting(InventoryMovement::getQuantity)
                    .containsExactly(new BigDecimal("5.00"), new BigDecimal("1.00"));

            var storeCaptor = ArgumentCaptor.forClass(ProductVariantStoreStock.class);
            verify(productVariantStoreStockRepository).save(storeCaptor.capture());
            assertThat(storeCaptor.getValue().getStock()).isEqualByComparingTo("14.00");
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

            var saved = savedBalances();
            assertThat(saved)
                    .extracting(ProductVariantLocation::getStoreLocationId)
                    .containsExactly(LOCATION_A, LOCATION_B);
            assertThat(saved)
                    .extracting(ProductVariantLocation::getStock)
                    .containsExactly(new BigDecimal("0.00"), new BigDecimal("0.00"));
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

            var saved = savedBalances();
            assertThat(saved)
                    .extracting(ProductVariantLocation::getStoreLocationId)
                    .containsExactly(LOCATION_B);
            assertThat(saved).extracting(ProductVariantLocation::getStock).containsExactly(new BigDecimal("2.00"));
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

            var saved = savedBalances();
            assertThat(saved)
                    .extracting(ProductVariantLocation::getStoreLocationId)
                    .containsExactly(LOCATION_B, LOCATION_A);
            assertThat(saved)
                    .extracting(ProductVariantLocation::getStock)
                    .containsExactly(new BigDecimal("0.00"), new BigDecimal("3.00"));

            var movements = savedMovements();
            assertThat(movements)
                    .extracting(InventoryMovement::getQuantity)
                    .containsExactly(new BigDecimal("3.00"), new BigDecimal("2.00"));
        }
    }

    // ---------------------------------------------------------------- fail closed

    @Nested
    @DisplayName("fail-closed checks")
    class FailClosedTests {

        @Test
        @DisplayName("should fail closed on the aggregate before reading the locations")
        void failsClosedOnTheAggregate() {
            givenEnabledVariant();
            givenStoreStock("4.00");

            assertThatThrownBy(() -> deduct("6.00"))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("requested 6.00, available 4.00");

            verifyNoInteractions(productVariantLocationRepository);
            verifyNoInteractions(inventoryMovementRepository);
            verifyNoInteractions(storeInventorySettingsRepository);
            verify(productVariantStoreStockRepository, never()).save(any(ProductVariantStoreStock.class));
        }

        @Test
        @DisplayName("should fail closed when the location rows cannot cover what the aggregate promised")
        void failsClosedWhenTheLocationsCannotCoverTheAggregate() {
            givenEnabledVariant();
            givenStoreStock("10.00");
            givenSalesLocation(LOCATION_A);
            balance(LOCATION_A, "2.00");
            balance(LOCATION_B, "2.00");
            givenFifoOrder(LOCATION_A, LOCATION_B);

            assertThatThrownBy(() -> deduct("6.00"))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("requested 6.00, available 4.00");

            verify(productVariantStoreStockRepository, never()).save(any(ProductVariantStoreStock.class));
            verify(productVariantLocationRepository, never()).save(any(ProductVariantLocation.class));
            verify(inventoryMovementRepository, never()).save(any(InventoryMovement.class));
        }

        @Test
        @DisplayName("should fail closed with InsufficientStockException when the store has no stock row")
        void rejectsVariantWithNoStoreRow() {
            givenEnabledVariant();
            when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreIdForUpdate(
                            VARIANT_ID, STORE_ID))
                    .thenReturn(Optional.empty());

            // A sale of a variant the store does not stock is an insufficiency — zero sellable
            // stock — not a programmer error: the API maps InsufficientStockException to 409, while
            // an IllegalArgumentException would surface as 400. The missing row must write nothing.
            assertThatThrownBy(() -> deduct("1.00"))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining(VARIANT_ID.toString())
                    .hasMessageContaining("requested 1.00, available 0");

            verify(productVariantStoreStockRepository, never()).save(any(ProductVariantStoreStock.class));
            verifyNoInteractions(productVariantLocationRepository);
            verifyNoInteractions(inventoryMovementRepository);
        }

        @Test
        @DisplayName("should reject an unknown variant with ProductVariantNotFoundException")
        void rejectsUnknownVariant() {
            when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> deduct("1.00")).isInstanceOf(ProductVariantNotFoundException.class);

            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(productVariantLocationRepository);
            verifyNoInteractions(inventoryMovementRepository);
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

            var movements = savedMovements();
            assertThat(movements).hasSize(2);
            for (var movement : movements) {
                assertThat(movement.getMovementType()).isEqualTo(MovementType.SALE);
                assertThat(movement.getProductVariantId()).isEqualTo(VARIANT_ID);
                assertThat(movement.getCompanyStoreId()).isEqualTo(STORE_ID);
                assertThat(movement.getReferenceType()).isEqualTo(REFERENCE_TYPE);
                assertThat(movement.getReferenceId()).isEqualTo(REFERENCE_ID);
                assertThat(movement.getCreatedBy()).isEqualTo(ACTOR);
                assertThat(movement.getQuantity()).isPositive();
            }
            assertThat(movements)
                    .extracting(InventoryMovement::getStoreLocationId, InventoryMovement::getQuantity)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(LOCATION_B, new BigDecimal("3.00")),
                            org.assertj.core.groups.Tuple.tuple(LOCATION_A, new BigDecimal("2.00")));
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
            givenLockableBalances(LOCATION_A, LOCATION_B);
            givenLedger(
                    movement(MovementType.SALE, LOCATION_B, "3.00"), movement(MovementType.SALE, LOCATION_A, "2.00"));

            reverse();

            var saved = savedBalances();
            assertThat(saved)
                    .extracting(ProductVariantLocation::getStoreLocationId)
                    .containsExactly(LOCATION_A, LOCATION_B);
            assertThat(saved)
                    .extracting(ProductVariantLocation::getStock)
                    .containsExactly(new BigDecimal("5.00"), new BigDecimal("3.00"));

            var movements = savedMovements();
            assertThat(movements).hasSize(2);
            assertThat(movements)
                    .extracting(
                            InventoryMovement::getMovementType,
                            InventoryMovement::getStoreLocationId,
                            InventoryMovement::getQuantity)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(
                                    MovementType.SALE_REVERSAL, LOCATION_A, new BigDecimal("2.00")),
                            org.assertj.core.groups.Tuple.tuple(
                                    MovementType.SALE_REVERSAL, LOCATION_B, new BigDecimal("3.00")));

            var storeCaptor = ArgumentCaptor.forClass(ProductVariantStoreStock.class);
            verify(productVariantStoreStockRepository).save(storeCaptor.capture());
            assertThat(storeCaptor.getValue().getStock()).isEqualByComparingTo("15.00");

            // The reversal never consults the store's priority location (W3-D6/W3-D8).
            verifyNoInteractions(storeInventorySettingsRepository);
        }

        @Test
        @DisplayName("should not restore a location twice: the second reversal is a no-op")
        void secondReversalIsANoOp() {
            givenLedger(
                    movement(MovementType.SALE, LOCATION_B, "3.00"),
                    movement(MovementType.SALE, LOCATION_A, "2.00"),
                    movement(MovementType.SALE_REVERSAL, LOCATION_B, "3.00"),
                    movement(MovementType.SALE_REVERSAL, LOCATION_A, "2.00"));

            reverse();

            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(productVariantLocationRepository);
            verify(inventoryMovementRepository, never()).save(any(InventoryMovement.class));
        }

        @Test
        @DisplayName("should reverse only the uncovered remainder of a partially reversed reference")
        void reversesOnlyTheUncoveredRemainder() {
            givenStoreStock("10.00");
            balance(LOCATION_A, "3.00");
            balance(LOCATION_B, "0.00");
            givenLockableBalances(LOCATION_A);
            givenLedger(
                    movement(MovementType.SALE, LOCATION_B, "3.00"),
                    movement(MovementType.SALE, LOCATION_A, "2.00"),
                    movement(MovementType.SALE_REVERSAL, LOCATION_B, "3.00"));

            reverse();

            var saved = savedBalances();
            assertThat(saved)
                    .extracting(ProductVariantLocation::getStoreLocationId)
                    .containsExactly(LOCATION_A);
            assertThat(saved).extracting(ProductVariantLocation::getStock).containsExactly(new BigDecimal("5.00"));

            var movements = savedMovements();
            assertThat(movements).hasSize(1);
            assertThat(movements.get(0).getMovementType()).isEqualTo(MovementType.SALE_REVERSAL);
            assertThat(movements.get(0).getStoreLocationId()).isEqualTo(LOCATION_A);
            assertThat(movements.get(0).getQuantity()).isEqualByComparingTo("2.00");
        }

        @Test
        @DisplayName("should do nothing when the reference has no SALE movement at all")
        void unknownReferenceIsANoOp() {
            givenLedger();

            reverse();

            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(productVariantLocationRepository);
            verify(inventoryMovementRepository, never()).save(any(InventoryMovement.class));
        }

        @Test
        @DisplayName("should reject a reversal without a usable reference")
        void rejectsMissingReference() {
            assertThatThrownBy(() -> inventoryService.applySaleReversal(REFERENCE_TYPE, null, ACTOR))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> inventoryService.applySaleReversal("  ", REFERENCE_ID, ACTOR))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(inventoryMovementRepository);
            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(productVariantLocationRepository);
            verifyNoInteractions(storeInventorySettingsRepository);
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

            var saved = savedBalances();
            assertThat(saved)
                    .extracting(ProductVariantLocation::getStoreLocationId)
                    .containsExactly(LOCATION_A, LOCATION_B);
            assertThat(saved)
                    .extracting(ProductVariantLocation::getStock)
                    .containsExactly(new BigDecimal("0.00"), new BigDecimal("0.00"));

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
            givenLockableBalances(LOCATION_A);
            givenLedger(movement(MovementType.SALE, LOCATION_A, "2.00"));

            reverse();

            assertThat(savedBalances())
                    .extracting(ProductVariantLocation::getStock)
                    .containsExactly(new BigDecimal("2.00"));
            assertThat(logAppender.list).isEmpty();
            verifyNoInteractions(storeInventorySettingsRepository);
        }
    }

    // ---------------------------------------------------------------- lock order

    @Nested
    @DisplayName("lock order")
    class LockOrderTests {

        @Test
        @DisplayName("should lock the per-store stock row before any interaction with the balance repository")
        void deductionLocksStoreStockFirst() {
            givenEnabledVariant();
            givenStoreStock("10.00");
            givenSalesLocation(LOCATION_B);
            balance(LOCATION_A, "5.00");
            balance(LOCATION_B, "3.00");
            givenFifoOrder(LOCATION_A, LOCATION_B);

            deduct("5.00");

            InOrder lockOrder = inOrder(
                    productVariantRepository, productVariantStoreStockRepository, productVariantLocationRepository);
            lockOrder.verify(productVariantRepository).findById(VARIANT_ID);
            lockOrder
                    .verify(productVariantStoreStockRepository)
                    .findByProductVariantIdAndCompanyStoreIdForUpdate(VARIANT_ID, STORE_ID);
            lockOrder
                    .verify(productVariantLocationRepository)
                    .findByProductVariantIdAndCompanyStoreId(VARIANT_ID, STORE_ID);
            lockOrder
                    .verify(productVariantLocationRepository)
                    .findByProductVariantIdAndStoreLocationIdForUpdate(VARIANT_ID, LOCATION_A);
            lockOrder
                    .verify(productVariantLocationRepository)
                    .findByProductVariantIdAndStoreLocationIdForUpdate(VARIANT_ID, LOCATION_B);
        }

        @Test
        @DisplayName("should lock the per-store stock row before the balances when reversing")
        void reversalLocksStoreStockFirst() {
            givenStoreStock("10.00");
            balance(LOCATION_A, "0.00");
            balance(LOCATION_B, "0.00");
            givenLockableBalances(LOCATION_A, LOCATION_B);
            givenLedger(
                    movement(MovementType.SALE, LOCATION_B, "3.00"), movement(MovementType.SALE, LOCATION_A, "2.00"));

            reverse();

            InOrder lockOrder = inOrder(productVariantStoreStockRepository, productVariantLocationRepository);
            lockOrder
                    .verify(productVariantStoreStockRepository)
                    .findByProductVariantIdAndCompanyStoreIdForUpdate(VARIANT_ID, STORE_ID);
            lockOrder
                    .verify(productVariantLocationRepository)
                    .findByProductVariantIdAndStoreLocationIdForUpdate(VARIANT_ID, LOCATION_A);
            lockOrder
                    .verify(productVariantLocationRepository)
                    .findByProductVariantIdAndStoreLocationIdForUpdate(VARIANT_ID, LOCATION_B);
        }
    }

    // ---------------------------------------------------------------- manual stock adjustment

    @Nested
    @DisplayName("manual per-store stock adjustment (W3-D14)")
    class ManualStockAdjustmentTests {

        private BigDecimal adjust(String stock) {
            return inventoryService.applyStockAdjustment(VARIANT_ID, STORE_ID, new BigDecimal(stock), ACTOR);
        }

        /** Both sides of the invariant: the aggregate saved equals the sum of the location balances. */
        private void assertInvariant(String expectedAggregate) {
            var locationSum = balances.values().stream()
                    .map(location -> location.getStock() != null ? location.getStock() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(locationSum).isEqualByComparingTo(expectedAggregate);
            verify(productVariantStoreStockRepository)
                    .save(argThat(row -> row.getStock().compareTo(new BigDecimal(expectedAggregate)) == 0));
        }

        @Test
        @DisplayName("should raise the destination location and the aggregate, and write one positive movement")
        void raiseWritesAllThreeSides() {
            givenStoreStock("0.00");
            givenSalesLocation(LOCATION_A);
            balance(LOCATION_A, "0.00");
            givenLockableBalances(LOCATION_A);

            var result = adjust("5.00");

            assertThat(result).isEqualByComparingTo("5.00");
            assertThat(savedBalances())
                    .extracting(ProductVariantLocation::getStock)
                    .containsExactly(new BigDecimal("5.00"));
            assertThat(savedMovements()).hasSize(1);
            var movement = savedMovements().getFirst();
            assertThat(movement.getMovementType()).isEqualTo(MovementType.ADJUSTMENT_INCREASE);
            assertThat(movement.getQuantity()).isEqualByComparingTo("5.00");
            assertThat(movement.getStoreLocationId()).isEqualTo(LOCATION_A);
            assertThat(movement.getReferenceType()).isEqualTo("MANUAL_STOCK_EDIT");
            assertThat(movement.getReferenceId()).isNull();
            assertThat(movement.getCreatedBy()).isEqualTo(ACTOR);
            verify(productVariantStoreStockRepository)
                    .save(argThat(row -> row.getStock().compareTo(new BigDecimal("5.00")) == 0));
        }

        @Test
        @DisplayName("should lower the destination and write a POSITIVE ADJUSTMENT_DECREASE (W3-D7)")
        void lowerWritesAPositiveDecrease() {
            givenStoreStock("11.00");
            givenSalesLocation(LOCATION_A);
            balance(LOCATION_A, "11.00");
            givenLockableBalances(LOCATION_A);
            givenFifoOrder(LOCATION_A);

            var result = adjust("4.00");

            assertThat(result).isEqualByComparingTo("4.00");
            assertThat(savedBalances())
                    .extracting(ProductVariantLocation::getStock)
                    .containsExactly(new BigDecimal("4.00"));
            assertThat(savedMovements()).hasSize(1);
            var movement = savedMovements().getFirst();
            assertThat(movement.getMovementType()).isEqualTo(MovementType.ADJUSTMENT_DECREASE);
            // The direction is the type; the quantity stays positive.
            assertThat(movement.getQuantity()).isEqualByComparingTo("7.00");
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
            assertThat(balances.get(LOCATION_A).getStock()).isEqualByComparingTo("0.00");
            assertThat(balances.get(LOCATION_B).getStock()).isEqualByComparingTo("5.00");
            assertThat(savedBalances())
                    .extracting(ProductVariantLocation::getStoreLocationId)
                    .containsExactly(LOCATION_B);
            var movements = savedMovements();
            assertThat(movements).hasSize(1);
            assertThat(movements.getFirst().getMovementType()).isEqualTo(MovementType.ADJUSTMENT_DECREASE);
            assertThat(movements.getFirst().getQuantity()).isEqualByComparingTo("5.00");
            assertThat(movements.getFirst().getStoreLocationId()).isEqualTo(LOCATION_B);
            assertInvariant("5.00");
        }

        @Test
        @DisplayName("should write one ADJUSTMENT_DECREASE per location a spanning decrease draws from")
        void spanningDecreaseWritesOneMovementPerLocation() {
            givenStoreStock("10.00");
            givenSalesLocation(LOCATION_A);
            balance(LOCATION_A, "2.00");
            balance(LOCATION_B, "8.00");
            givenFifoOrder(LOCATION_A, LOCATION_B);

            var result = adjust("5.00");

            assertThat(result).isEqualByComparingTo("5.00");
            assertThat(balances.get(LOCATION_A).getStock()).isEqualByComparingTo("0.00");
            assertThat(balances.get(LOCATION_B).getStock()).isEqualByComparingTo("5.00");
            var movements = savedMovements();
            assertThat(movements)
                    .extracting(InventoryMovement::getStoreLocationId)
                    .containsExactly(LOCATION_A, LOCATION_B);
            assertThat(movements)
                    .extracting(InventoryMovement::getMovementType)
                    .containsOnly(MovementType.ADJUSTMENT_DECREASE);
            assertThat(movements)
                    .extracting(InventoryMovement::getQuantity)
                    .containsExactly(new BigDecimal("2.00"), new BigDecimal("3.00"));
            assertInvariant("5.00");
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
            assertThat(balances.get(LOCATION_A).getStock()).isEqualByComparingTo("0.00");
            assertThat(balances.get(LOCATION_B).getStock()).isEqualByComparingTo("0.00");
            assertThat(savedMovements())
                    .extracting(InventoryMovement::getQuantity)
                    .containsExactly(new BigDecimal("2.00"), new BigDecimal("3.00"));
            assertInvariant("0.00");
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

            assertThat(balances.get(LOCATION_A).getStock()).isEqualByComparingTo("1.00");
            assertThat(balances.get(LOCATION_B).getStock()).isEqualByComparingTo("1.00");
            verify(productVariantStoreStockRepository, never()).save(any());
            verifyNoInteractions(inventoryMovementRepository);
        }

        @Test
        @DisplayName("should refuse a store with no settings row before touching any balance")
        void refusesWithoutStoreInventorySettings() {
            givenNoStoreInventorySettings();

            assertThatThrownBy(() -> adjust("3.00"))
                    .isInstanceOf(StoreInventorySettingsNotFoundException.class)
                    .hasMessageContaining("Store inventory settings not found");

            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(productVariantLocationRepository);
            verifyNoInteractions(inventoryMovementRepository);
        }

        @Test
        @DisplayName("should write no movement when the target equals the current aggregate")
        void sameStockWritesNoMovement() {
            givenStoreStock("5.00");
            givenSalesLocation(LOCATION_A);

            var result = adjust("5.00");

            assertThat(result).isEqualByComparingTo("5.00");
            verifyNoInteractions(productVariantLocationRepository);
            verifyNoInteractions(inventoryMovementRepository);
        }

        @Test
        @DisplayName("should reject a negative or null target without touching any repository")
        void rejectsInvalidTarget() {
            assertThatThrownBy(() -> adjust("-1.00"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be greater than or equal to zero");
            assertThatThrownBy(() -> inventoryService.applyStockAdjustment(VARIANT_ID, STORE_ID, null, ACTOR))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(storeInventorySettingsRepository);
            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(productVariantLocationRepository);
            verifyNoInteractions(inventoryMovementRepository);
        }

        @Test
        @DisplayName("should lock the per-store stock row before the destination balance")
        void adjustmentLocksStoreStockFirst() {
            givenStoreStock("0.00");
            givenSalesLocation(LOCATION_A);
            balance(LOCATION_A, "0.00");
            givenLockableBalances(LOCATION_A);

            adjust("2.00");

            InOrder lockOrder = inOrder(
                    storeInventorySettingsRepository,
                    productVariantStoreStockRepository,
                    productVariantLocationRepository);
            lockOrder.verify(storeInventorySettingsRepository).findByCompanyStoreId(STORE_ID);
            lockOrder
                    .verify(productVariantStoreStockRepository)
                    .findByProductVariantIdAndCompanyStoreIdForUpdate(VARIANT_ID, STORE_ID);
            lockOrder
                    .verify(productVariantLocationRepository)
                    .findByProductVariantIdAndStoreLocationIdForUpdate(VARIANT_ID, LOCATION_A);
        }
    }
}
