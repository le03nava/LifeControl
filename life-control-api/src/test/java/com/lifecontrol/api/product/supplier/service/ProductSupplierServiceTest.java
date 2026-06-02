package com.lifecontrol.api.product.supplier.service;

import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.supplier.dto.ProductSupplierRequest;
import com.lifecontrol.api.product.supplier.dto.ProductSupplierResponse;
import com.lifecontrol.api.product.supplier.exception.DuplicateProductSupplierException;
import com.lifecontrol.api.product.supplier.exception.ProductSupplierNotFoundException;
import com.lifecontrol.api.product.supplier.model.ProductSupplier;
import com.lifecontrol.api.product.supplier.repository.ProductSupplierRepository;
import com.lifecontrol.api.supplier.exception.SupplierNotFoundException;
import com.lifecontrol.api.supplier.model.Supplier;
import com.lifecontrol.api.supplier.repository.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductSupplierService Tests")
class ProductSupplierServiceTest {

    @Mock
    private ProductSupplierRepository productSupplierRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private SupplierRepository supplierRepository;

    @InjectMocks
    private ProductSupplierService service;

    private UUID productId;
    private UUID supplierId;
    private UUID relationId;
    private UUID existingRelationId;
    private Product testProduct;
    private Supplier testSupplier;
    private Supplier otherSupplier;
    private ProductSupplier testRelation;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        productId = UUID.randomUUID();
        supplierId = UUID.randomUUID();
        relationId = UUID.randomUUID();
        existingRelationId = UUID.randomUUID();
        now = LocalDateTime.now();

        testProduct = Product.builder()
                .id(productId)
                .sku("SKU-001")
                .name("Test Product")
                .enabled(true)
                .build();

        testSupplier = Supplier.builder()
                .id(supplierId)
                .supplierName("Test Supplier")
                .rfc("XAXX010101000")
                .enabled(true)
                .build();

        otherSupplier = Supplier.builder()
                .id(UUID.randomUUID())
                .supplierName("Other Supplier")
                .rfc("XAXX010101001")
                .enabled(true)
                .build();

        testRelation = ProductSupplier.builder()
                .id(relationId)
                .product(testProduct)
                .supplier(testSupplier)
                .purchaseCost(new BigDecimal("150.00"))
                .main(false)
                .enabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    @Nested
    @DisplayName("listSuppliersByProductId")
    class ListSuppliersByProductIdTests {

        @Test
        @DisplayName("should return list of suppliers for existing product")
        void listSuppliersByProductId_Success() {
            when(productRepository.existsById(productId)).thenReturn(true);
            when(productSupplierRepository.findByProductId(productId))
                    .thenReturn(List.of(testRelation));

            List<ProductSupplierResponse> result = service.listSuppliersByProductId(productId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo(relationId);
            assertThat(result.get(0).productId()).isEqualTo(productId);
            assertThat(result.get(0).supplierId()).isEqualTo(supplierId);
            assertThat(result.get(0).supplierName()).isEqualTo("Test Supplier");
            assertThat(result.get(0).purchaseCost()).isEqualByComparingTo("150.00");
            assertThat(result.get(0).main()).isFalse();
            assertThat(result.get(0).enabled()).isTrue();
        }

        @Test
        @DisplayName("should return empty list when product has no suppliers")
        void listSuppliersByProductId_EmptyList() {
            when(productRepository.existsById(productId)).thenReturn(true);
            when(productSupplierRepository.findByProductId(productId)).thenReturn(List.of());

            List<ProductSupplierResponse> result = service.listSuppliersByProductId(productId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product does not exist")
        void listSuppliersByProductId_ProductNotFound_ThrowsException() {
            when(productRepository.existsById(productId)).thenReturn(false);

            assertThatThrownBy(() -> service.listSuppliersByProductId(productId))
                    .isInstanceOf(ProductNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("addSupplierToProduct")
    class AddSupplierToProductTests {

        @Test
        @DisplayName("should add supplier relation successfully")
        void addSupplierToProduct_Success() {
            var request = new ProductSupplierRequest(supplierId, new BigDecimal("200.00"), false, true);

            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(supplierRepository.findById(supplierId)).thenReturn(Optional.of(testSupplier));
            when(productSupplierRepository.existsByProductIdAndSupplierId(productId, supplierId)).thenReturn(false);
            when(productSupplierRepository.save(any(ProductSupplier.class))).thenAnswer(inv -> {
                var ps = (ProductSupplier) inv.getArgument(0);
                return ProductSupplier.builder()
                        .id(relationId)
                        .product(ps.getProduct())
                        .supplier(ps.getSupplier())
                        .purchaseCost(ps.getPurchaseCost())
                        .main(ps.getMain())
                        .enabled(ps.getEnabled())
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
            });

            var result = service.addSupplierToProduct(productId, request);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(relationId);
            assertThat(result.productId()).isEqualTo(productId);
            assertThat(result.supplierId()).isEqualTo(supplierId);
            assertThat(result.supplierName()).isEqualTo("Test Supplier");
            assertThat(result.purchaseCost()).isEqualByComparingTo("200.00");
            assertThat(result.main()).isFalse();
            assertThat(result.enabled()).isTrue();
            verify(productSupplierRepository).save(any(ProductSupplier.class));
        }

        @Test
        @DisplayName("should add relation with main=true and unset previous main")
        void addSupplierToProduct_WithMainFlag_UnsetsPreviousMain() {
            var request = new ProductSupplierRequest(supplierId, new BigDecimal("300.00"), true, true);

            var existingMain = ProductSupplier.builder()
                    .id(existingRelationId)
                    .product(testProduct)
                    .supplier(otherSupplier)
                    .main(true)
                    .enabled(true)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(supplierRepository.findById(supplierId)).thenReturn(Optional.of(testSupplier));
            when(productSupplierRepository.existsByProductIdAndSupplierId(productId, supplierId)).thenReturn(false);
            when(productSupplierRepository.findMainByProductId(productId)).thenReturn(Optional.of(existingMain));
            when(productSupplierRepository.save(any(ProductSupplier.class))).thenAnswer(inv -> {
                var ps = (ProductSupplier) inv.getArgument(0);
                if (ps.getId() == null) {
                    return ProductSupplier.builder()
                            .id(relationId)
                            .product(ps.getProduct())
                            .supplier(ps.getSupplier())
                            .purchaseCost(ps.getPurchaseCost())
                            .main(ps.getMain())
                            .enabled(ps.getEnabled())
                            .createdAt(now)
                            .updatedAt(now)
                            .build();
                }
                return ps;
            });

            var result = service.addSupplierToProduct(productId, request);

            assertThat(result).isNotNull();
            assertThat(result.main()).isTrue();
            // Previous main should be unset
            assertThat(existingMain.getMain()).isFalse();
            verify(productSupplierRepository).save(existingMain);
        }

        @Test
        @DisplayName("should add relation with main=true when no previous main exists")
        void addSupplierToProduct_MainFlag_NoPreviousMain() {
            var request = new ProductSupplierRequest(supplierId, new BigDecimal("300.00"), true, true);

            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(supplierRepository.findById(supplierId)).thenReturn(Optional.of(testSupplier));
            when(productSupplierRepository.existsByProductIdAndSupplierId(productId, supplierId)).thenReturn(false);
            when(productSupplierRepository.findMainByProductId(productId)).thenReturn(Optional.empty());
            when(productSupplierRepository.save(any(ProductSupplier.class))).thenAnswer(inv -> {
                var ps = (ProductSupplier) inv.getArgument(0);
                return ProductSupplier.builder()
                        .id(relationId)
                        .product(ps.getProduct())
                        .supplier(ps.getSupplier())
                        .purchaseCost(ps.getPurchaseCost())
                        .main(ps.getMain())
                        .enabled(ps.getEnabled())
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
            });

            var result = service.addSupplierToProduct(productId, request);

            assertThat(result).isNotNull();
            assertThat(result.main()).isTrue();
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product not found")
        void addSupplierToProduct_ProductNotFound_ThrowsException() {
            var request = new ProductSupplierRequest(supplierId, BigDecimal.TEN, false, true);

            when(productRepository.findById(productId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addSupplierToProduct(productId, request))
                    .isInstanceOf(ProductNotFoundException.class);
        }

        @Test
        @DisplayName("should throw SupplierNotFoundException when supplier not found")
        void addSupplierToProduct_SupplierNotFound_ThrowsException() {
            var request = new ProductSupplierRequest(supplierId, BigDecimal.TEN, false, true);

            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(supplierRepository.findById(supplierId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addSupplierToProduct(productId, request))
                    .isInstanceOf(SupplierNotFoundException.class);
        }

        @Test
        @DisplayName("should throw DuplicateProductSupplierException when relation already exists")
        void addSupplierToProduct_Duplicate_ThrowsException() {
            var request = new ProductSupplierRequest(supplierId, BigDecimal.TEN, false, true);

            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(supplierRepository.findById(supplierId)).thenReturn(Optional.of(testSupplier));
            when(productSupplierRepository.existsByProductIdAndSupplierId(productId, supplierId)).thenReturn(true);

            assertThatThrownBy(() -> service.addSupplierToProduct(productId, request))
                    .isInstanceOf(DuplicateProductSupplierException.class)
                    .hasMessageContaining("Test Supplier");
        }
    }

    @Nested
    @DisplayName("updateSupplier")
    class UpdateSupplierTests {

        @Test
        @DisplayName("should update relation fields successfully")
        void updateSupplier_Success() {
            var request = new ProductSupplierRequest(supplierId, new BigDecimal("250.50"), false, false);

            when(productSupplierRepository.findByProductIdAndId(productId, relationId))
                    .thenReturn(Optional.of(testRelation));
            when(productSupplierRepository.save(any(ProductSupplier.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = service.updateSupplier(productId, relationId, request);

            assertThat(result).isNotNull();
            assertThat(result.purchaseCost()).isEqualByComparingTo("250.50");
            assertThat(result.main()).isFalse();
            assertThat(result.enabled()).isFalse();
        }

        @Test
        @DisplayName("should transfer main flag when setting main=true on non-main relation")
        void updateSupplier_MainFlagTransfer_UnsetsPreviousMain() {
            var request = new ProductSupplierRequest(supplierId, new BigDecimal("150.00"), true, true);

            var existingMain = ProductSupplier.builder()
                    .id(existingRelationId)
                    .product(testProduct)
                    .supplier(otherSupplier)
                    .main(true)
                    .enabled(true)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            when(productSupplierRepository.findByProductIdAndId(productId, relationId))
                    .thenReturn(Optional.of(testRelation)); // current relation is NOT main
            when(productSupplierRepository.findMainByProductId(productId)).thenReturn(Optional.of(existingMain));
            when(productSupplierRepository.save(any(ProductSupplier.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = service.updateSupplier(productId, relationId, request);

            assertThat(result.main()).isTrue();
            assertThat(existingMain.getMain()).isFalse();
            verify(productSupplierRepository).save(existingMain);
        }

        @Test
        @DisplayName("should not unset previous main when relation is already main and stays main")
        void updateSupplier_MainFlagAlreadyMain_NoUnset() {
            var alreadyMain = ProductSupplier.builder()
                    .id(relationId)
                    .product(testProduct)
                    .supplier(testSupplier)
                    .purchaseCost(new BigDecimal("150.00"))
                    .main(true)
                    .enabled(true)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            var request = new ProductSupplierRequest(supplierId, new BigDecimal("200.00"), true, true);

            when(productSupplierRepository.findByProductIdAndId(productId, relationId))
                    .thenReturn(Optional.of(alreadyMain));
            when(productSupplierRepository.save(any(ProductSupplier.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = service.updateSupplier(productId, relationId, request);

            assertThat(result.main()).isTrue();
            verify(productSupplierRepository, never()).findMainByProductId(any());
        }

        @Test
        @DisplayName("should throw ProductSupplierNotFoundException when relation not found")
        void updateSupplier_NotFound_ThrowsException() {
            var request = new ProductSupplierRequest(supplierId, BigDecimal.TEN, false, true);

            when(productSupplierRepository.findByProductIdAndId(productId, relationId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateSupplier(productId, relationId, request))
                    .isInstanceOf(ProductSupplierNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("removeSupplierFromProduct")
    class RemoveSupplierFromProductTests {

        @Test
        @DisplayName("should delete relation successfully")
        void removeSupplierFromProduct_Success() {
            when(productSupplierRepository.findByProductIdAndId(productId, relationId))
                    .thenReturn(Optional.of(testRelation));

            service.removeSupplierFromProduct(productId, relationId);

            verify(productSupplierRepository).delete(testRelation);
        }

        @Test
        @DisplayName("should throw ProductSupplierNotFoundException when relation not found")
        void removeSupplierFromProduct_NotFound_ThrowsException() {
            when(productSupplierRepository.findByProductIdAndId(productId, relationId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.removeSupplierFromProduct(productId, relationId))
                    .isInstanceOf(ProductSupplierNotFoundException.class);
        }
    }
}
