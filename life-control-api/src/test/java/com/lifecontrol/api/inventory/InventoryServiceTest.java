package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.inventory.model.InventoryMovement;
import com.lifecontrol.api.inventory.model.MovementType;
import com.lifecontrol.api.inventory.model.ProductVariantLocation;
import com.lifecontrol.api.inventory.repository.InventoryMovementRepository;
import com.lifecontrol.api.inventory.repository.ProductVariantLocationRepository;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.inventory.service.InventoryService;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.model.ProductVariantStoreStock;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
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

    @Captor
    private ArgumentCaptor<InventoryMovement> movementCaptor;

    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(
                productVariantRepository,
                productVariantStoreStockRepository,
                productVariantLocationRepository,
                inventoryMovementRepository,
                storeInventorySettingsRepository);
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

            when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(enabledVariant()));
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
    }

    @Nested
    @DisplayName("additive receipt")
    class AdditiveReceiptTests {

        @Test
        @DisplayName("should append one RECEIPT movement and add the quantity to both balances")
        void firstReceiptIsAdditive() {
            var balanceId = UUID.fromString("00000000-0000-0000-0000-000000000002");
            var referenceId = UUID.randomUUID();

            when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(enabledVariant()));
            when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreIdForUpdate(
                            VARIANT_ID, STORE_ID))
                    .thenReturn(Optional.of(storeRowWithStock("10.00")));
            when(productVariantStoreStockRepository.save(any(ProductVariantStoreStock.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(productVariantLocationRepository.findByProductVariantIdAndStoreLocationIdForUpdate(
                            VARIANT_ID, LOCATION_ID))
                    .thenReturn(Optional.of(balanceWith(balanceId, "0.00")));
            when(productVariantLocationRepository.save(any(ProductVariantLocation.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            inventoryService.applyReceipt(
                    VARIANT_ID,
                    STORE_ID,
                    LOCATION_ID,
                    new BigDecimal("12.50"),
                    "GOODS_RECEIPT",
                    referenceId,
                    "receiver");

            var savedBalance = captureSavedBalance();
            assertThat(savedBalance.getStock()).isEqualByComparingTo("12.50");

            var savedStoreStock = captureSavedStoreStock();
            assertThat(savedStoreStock.getStock()).isEqualByComparingTo("22.50");

            verify(inventoryMovementRepository).save(movementCaptor.capture());
            var movement = movementCaptor.getValue();
            assertThat(movement.getMovementType()).isEqualTo(MovementType.RECEIPT);
            assertThat(movement.getQuantity()).isEqualByComparingTo("12.50");
            assertThat(movement.getProductVariantId()).isEqualTo(VARIANT_ID);
            assertThat(movement.getCompanyStoreId()).isEqualTo(STORE_ID);
            assertThat(movement.getStoreLocationId()).isEqualTo(LOCATION_ID);
            assertThat(movement.getReferenceType()).isEqualTo("GOODS_RECEIPT");
            assertThat(movement.getReferenceId()).isEqualTo(referenceId);
            assertThat(movement.getCreatedBy()).isEqualTo("receiver");
        }

        @Test
        @DisplayName("should accumulate on a store row and a balance row that already exist")
        void secondReceiptAccumulates() {
            var balanceId = UUID.fromString("00000000-0000-0000-0000-000000000002");

            when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(enabledVariant()));
            when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreIdForUpdate(
                            VARIANT_ID, STORE_ID))
                    .thenReturn(Optional.of(storeRowWithStock("3.00")));
            when(productVariantStoreStockRepository.save(any(ProductVariantStoreStock.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(productVariantLocationRepository.findByProductVariantIdAndStoreLocationIdForUpdate(
                            VARIANT_ID, LOCATION_ID))
                    .thenReturn(Optional.of(balanceWith(balanceId, "5.00")));
            when(productVariantLocationRepository.save(any(ProductVariantLocation.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            applyReceipt("2.25");

            assertThat(captureSavedBalance().getStock()).isEqualByComparingTo("7.25");
            assertThat(captureSavedStoreStock().getStock()).isEqualByComparingTo("5.25");
        }

        @Test
        @DisplayName("should treat a NULL store stock as zero and land on exactly the received quantity")
        void nullStockCountsAsZero() {
            var nullBalance = ProductVariantLocation.builder()
                    .id(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                    .productVariantId(VARIANT_ID)
                    .storeLocationId(LOCATION_ID)
                    .stock(null)
                    .build();

            when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(enabledVariant()));
            when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreIdForUpdate(
                            VARIANT_ID, STORE_ID))
                    .thenReturn(Optional.of(storeRowWithStock(null)));
            when(productVariantStoreStockRepository.save(any(ProductVariantStoreStock.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(productVariantLocationRepository.findByProductVariantIdAndStoreLocationIdForUpdate(
                            VARIANT_ID, LOCATION_ID))
                    .thenReturn(Optional.of(nullBalance));
            when(productVariantLocationRepository.save(any(ProductVariantLocation.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            applyReceipt("8.00");

            assertThat(captureSavedBalance().getStock()).isEqualByComparingTo("8.00");
            assertThat(captureSavedStoreStock().getStock()).isEqualByComparingTo("8.00");
        }

        @Test
        @DisplayName("should fail with ProductVariantNotFoundException and write no ledger row")
        void unknownVariantFails() {
            when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> applyReceipt("1.00")).isInstanceOf(ProductVariantNotFoundException.class);

            verifyNoInteractions(inventoryMovementRepository);
            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(productVariantLocationRepository);
        }

        @Test
        @DisplayName("should fail with ProductVariantNotFoundException when the variant is disabled")
        void disabledVariantFails() {
            var disabled = ProductVariant.builder()
                    .id(VARIANT_ID)
                    .barCode("7501234567890")
                    .variantName("Talla M")
                    .enabled(false)
                    .build();
            when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(disabled));

            assertThatThrownBy(() -> applyReceipt("1.00")).isInstanceOf(ProductVariantNotFoundException.class);

            verifyNoInteractions(inventoryMovementRepository);
            verifyNoInteractions(productVariantStoreStockRepository);
            verifyNoInteractions(productVariantLocationRepository);
        }

        private ProductVariantLocation captureSavedBalance() {
            var captured = ArgumentCaptor.forClass(ProductVariantLocation.class);
            verify(productVariantLocationRepository).save(captured.capture());
            return captured.getValue();
        }

        private ProductVariantStoreStock captureSavedStoreStock() {
            var captured = ArgumentCaptor.forClass(ProductVariantStoreStock.class);
            verify(productVariantStoreStockRepository).save(captured.capture());
            return captured.getValue();
        }
    }

    @Nested
    @DisplayName("lock order")
    class LockOrderTests {

        @Test
        @DisplayName("should lock the per-store stock row before any interaction with the balance repository")
        void locksStoreStockBeforeAnyBalanceInteraction() {
            var balanceId = UUID.fromString("00000000-0000-0000-0000-000000000000");

            when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(enabledVariant()));
            when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreIdForUpdate(
                            VARIANT_ID, STORE_ID))
                    .thenReturn(Optional.of(storeRowWithStock("1.00")));
            when(productVariantStoreStockRepository.save(any(ProductVariantStoreStock.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(productVariantLocationRepository.findByProductVariantIdAndStoreLocationIdForUpdate(
                            VARIANT_ID, LOCATION_ID))
                    .thenReturn(Optional.of(balanceWith(balanceId, "1.00")));
            when(productVariantLocationRepository.save(any(ProductVariantLocation.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            applyReceipt("1.00");

            // Not vacuous: the definition read, the store-stock lock and both balance-repository
            // interactions are verified as really invoked, and the store-stock lock must come first.
            InOrder lockOrder = inOrder(
                    productVariantRepository, productVariantStoreStockRepository, productVariantLocationRepository);
            lockOrder.verify(productVariantRepository).findById(VARIANT_ID);
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
            when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(enabledVariant()));
            when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreIdForUpdate(
                            VARIANT_ID, STORE_ID))
                    .thenReturn(Optional.of(storeRowWithStock("0.00")));
            when(productVariantStoreStockRepository.save(any(ProductVariantStoreStock.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(productVariantLocationRepository.findByProductVariantIdAndStoreLocationIdForUpdate(
                            VARIANT_ID, LOCATION_ID))
                    .thenReturn(
                            Optional.of(balanceWith(UUID.fromString("00000000-0000-0000-0000-000000000002"), "0.00")));
            when(productVariantLocationRepository.save(any(ProductVariantLocation.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            applyReceipt("4.00");

            InOrder lockOrder = inOrder(productVariantStoreStockRepository, productVariantLocationRepository);
            lockOrder
                    .verify(productVariantStoreStockRepository)
                    .findByProductVariantIdAndCompanyStoreIdForUpdate(VARIANT_ID, STORE_ID);
            lockOrder.verify(productVariantLocationRepository).insertBalanceIfAbsent(VARIANT_ID, LOCATION_ID);
        }
    }
}
