package com.lifecontrol.api.product.service;

import com.lifecontrol.api.product.dto.ProductVariantRequest;
import com.lifecontrol.api.product.dto.ProductVariantResponse;
import com.lifecontrol.api.product.dto.ProductVariantSearchResponse;
import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductVariantService Tests")
class ProductVariantServiceTest {

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductVariantService productVariantService;

    private UUID productId;
    private UUID variantId;
    private UUID companyStoreId;
    private ProductVariant testVariant;
    private ProductVariantRequest testVariantRequest;

    @BeforeEach
    void setUp() {
        productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        companyStoreId = UUID.randomUUID();
        var now = LocalDateTime.now();

        testVariant = ProductVariant.builder()
                .id(variantId)
                .productId(productId)
                .companyStoreId(companyStoreId)
                .barCode("7501234567890")
                .sku("SKU-VAR-001")
                .variantName("Talla M")
                .listPrice(new BigDecimal("199.99"))
                .costPrice(new BigDecimal("120.00"))
                .stock(new BigDecimal("50.00"))
                .enabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        testVariantRequest = new ProductVariantRequest(
                productId,
                companyStoreId,
                "7501234567890",
                "SKU-VAR-001",
                "Talla M",
                new BigDecimal("199.99"),
                new BigDecimal("120.00"),
                new BigDecimal("50.00"),
                true
        );
    }

    // ─────────────────────────────────────────────
    // listVariants
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("listVariants")
    class ListVariantsTests {

        @Test
        @DisplayName("should return paginated variants for a product")
        void listVariants_Paginated() {
            var pageable = PageRequest.of(0, 12);
            var variants = List.of(testVariant);
            var expectedPage = new PageImpl<>(variants, pageable, 1);

            when(productRepository.existsById(productId)).thenReturn(true);
            when(productVariantRepository.findByProductIdAndEnabledTrueOrderByCreatedAtDesc(productId, pageable))
                    .thenReturn(expectedPage);

            Page<ProductVariantResponse> result = productVariantService.listVariants(productId, pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).id()).isEqualTo(variantId);
            assertThat(result.getContent().get(0).productId()).isEqualTo(productId);
            assertThat(result.getContent().get(0).variantName()).isEqualTo("Talla M");
            assertThat(result.getContent().get(0).barCode()).isEqualTo("7501234567890");
            assertThat(result.getContent().get(0).listPrice()).isEqualByComparingTo(new BigDecimal("199.99"));
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getTotalPages()).isEqualTo(1);
            verify(productRepository).existsById(productId);
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product does not exist")
        void listVariants_ProductNotFound_ThrowsException() {
            var pageable = PageRequest.of(0, 12);
            when(productRepository.existsById(productId)).thenReturn(false);

            assertThatThrownBy(() -> productVariantService.listVariants(productId, pageable))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining("Product not found with id");

            verify(productVariantRepository, never()).findByProductIdAndEnabledTrueOrderByCreatedAtDesc(any(), any());
        }

        @Test
        @DisplayName("should return empty page when product has no variants")
        void listVariants_EmptyPage() {
            var pageable = PageRequest.of(0, 12);
            var expectedPage = new PageImpl<ProductVariant>(List.of(), pageable, 0);

            when(productRepository.existsById(productId)).thenReturn(true);
            when(productVariantRepository.findByProductIdAndEnabledTrueOrderByCreatedAtDesc(productId, pageable))
                    .thenReturn(expectedPage);

            Page<ProductVariantResponse> result = productVariantService.listVariants(productId, pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }
    }

    // ─────────────────────────────────────────────
    // getVariant
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("getVariant")
    class GetVariantTests {

        @Test
        @DisplayName("should return variant when found and scoped to product")
        void getVariant_Found_ScopedToProduct() {
            when(productRepository.existsById(productId)).thenReturn(true);
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));

            ProductVariantResponse result = productVariantService.getVariant(productId, variantId);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(variantId);
            assertThat(result.productId()).isEqualTo(productId);
            assertThat(result.variantName()).isEqualTo("Talla M");
            assertThat(result.barCode()).isEqualTo("7501234567890");
            assertThat(result.sku()).isEqualTo("SKU-VAR-001");
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product does not exist")
        void getVariant_ProductNotFound_ThrowsException() {
            when(productRepository.existsById(productId)).thenReturn(false);

            assertThatThrownBy(() -> productVariantService.getVariant(productId, variantId))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining("Product not found with id");

            verify(productVariantRepository, never()).findById(any());
        }

        @Test
        @DisplayName("should throw ProductVariantNotFoundException when variant not found")
        void getVariant_VariantNotFound_ThrowsException() {
            when(productRepository.existsById(productId)).thenReturn(true);
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productVariantService.getVariant(productId, variantId))
                    .isInstanceOf(ProductVariantNotFoundException.class)
                    .hasMessageContaining("Product variant not found with id");
        }

        @Test
        @DisplayName("should throw ProductVariantNotFoundException when variant belongs to different product")
        void getVariant_WrongProduct_ThrowsException() {
            var otherProductId = UUID.randomUUID();
            var otherVariant = ProductVariant.builder()
                    .id(variantId)
                    .productId(otherProductId)
                    .companyStoreId(companyStoreId)
                    .build();

            when(productRepository.existsById(productId)).thenReturn(true);
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(otherVariant));

            assertThatThrownBy(() -> productVariantService.getVariant(productId, variantId))
                    .isInstanceOf(ProductVariantNotFoundException.class)
                    .hasMessageContaining("Product variant not found with id");
        }
    }

    // ─────────────────────────────────────────────
    // createVariant
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("createVariant")
    class CreateVariantTests {

        @Test
        @DisplayName("should create variant and return response with generated ID")
        void createVariant_Success() {
            when(productRepository.existsById(productId)).thenReturn(true);
            when(productVariantRepository.save(any(ProductVariant.class))).thenReturn(testVariant);

            ProductVariantResponse result = productVariantService.createVariant(productId, testVariantRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(variantId);
            assertThat(result.productId()).isEqualTo(productId);
            assertThat(result.variantName()).isEqualTo("Talla M");
            assertThat(result.barCode()).isEqualTo("7501234567890");
            assertThat(result.sku()).isEqualTo("SKU-VAR-001");
            assertThat(result.listPrice()).isEqualByComparingTo(new BigDecimal("199.99"));
            assertThat(result.costPrice()).isEqualByComparingTo(new BigDecimal("120.00"));
            assertThat(result.stock()).isEqualByComparingTo(new BigDecimal("50.00"));
            assertThat(result.enabled()).isTrue();
            verify(productRepository).existsById(productId);
            verify(productVariantRepository).save(any(ProductVariant.class));
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product does not exist")
        void createVariant_ProductNotFound_ThrowsException() {
            when(productRepository.existsById(productId)).thenReturn(false);

            assertThatThrownBy(() -> productVariantService.createVariant(productId, testVariantRequest))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining("Product not found with id");

            verify(productVariantRepository, never()).save(any(ProductVariant.class));
        }
    }

    // ─────────────────────────────────────────────
    // updateVariant
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("updateVariant")
    class UpdateVariantTests {

        @Test
        @DisplayName("should update variant fields and return response")
        void updateVariant_Success() {
            var updateRequest = new ProductVariantRequest(
                    productId,
                    companyStoreId,
                    "7509876543210",
                    "SKU-VAR-UPD",
                    "Talla L",
                    new BigDecimal("249.99"),
                    new BigDecimal("150.00"),
                    new BigDecimal("30.00"),
                    false
            );

            when(productRepository.existsById(productId)).thenReturn(true);
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));
            when(productVariantRepository.save(any(ProductVariant.class))).thenAnswer(inv -> inv.getArgument(0));

            ProductVariantResponse result = productVariantService.updateVariant(productId, variantId, updateRequest);

            assertThat(result).isNotNull();
            assertThat(result.barCode()).isEqualTo("7509876543210");
            assertThat(result.sku()).isEqualTo("SKU-VAR-UPD");
            assertThat(result.variantName()).isEqualTo("Talla L");
            assertThat(result.listPrice()).isEqualByComparingTo(new BigDecimal("249.99"));
            assertThat(result.costPrice()).isEqualByComparingTo(new BigDecimal("150.00"));
            assertThat(result.stock()).isEqualByComparingTo(new BigDecimal("30.00"));
            assertThat(result.enabled()).isFalse();
            verify(productVariantRepository).save(any(ProductVariant.class));
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product does not exist")
        void updateVariant_ProductNotFound_ThrowsException() {
            when(productRepository.existsById(productId)).thenReturn(false);

            assertThatThrownBy(() -> productVariantService.updateVariant(productId, variantId, testVariantRequest))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining("Product not found with id");

            verify(productVariantRepository, never()).findById(any());
            verify(productVariantRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw ProductVariantNotFoundException when variant not found")
        void updateVariant_VariantNotFound_ThrowsException() {
            when(productRepository.existsById(productId)).thenReturn(true);
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productVariantService.updateVariant(productId, variantId, testVariantRequest))
                    .isInstanceOf(ProductVariantNotFoundException.class)
                    .hasMessageContaining("Product variant not found with id");

            verify(productVariantRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw ProductVariantNotFoundException when variant belongs to different product")
        void updateVariant_WrongProduct_ThrowsException() {
            var otherProductId = UUID.randomUUID();
            var otherVariant = ProductVariant.builder()
                    .id(variantId)
                    .productId(otherProductId)
                    .companyStoreId(companyStoreId)
                    .build();

            when(productRepository.existsById(productId)).thenReturn(true);
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(otherVariant));

            assertThatThrownBy(() -> productVariantService.updateVariant(productId, variantId, testVariantRequest))
                    .isInstanceOf(ProductVariantNotFoundException.class)
                    .hasMessageContaining("Product variant not found with id");

            verify(productVariantRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────
    // deleteVariant
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("deleteVariant")
    class DeleteVariantTests {

        @Test
        @DisplayName("should soft-delete variant by setting enabled to false")
        void deleteVariant_Success() {
            when(productRepository.existsById(productId)).thenReturn(true);
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));
            when(productVariantRepository.save(any(ProductVariant.class))).thenAnswer(inv -> inv.getArgument(0));

            productVariantService.deleteVariant(productId, variantId);

            verify(productVariantRepository).findById(variantId);
            verify(productVariantRepository).save(any(ProductVariant.class));
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product does not exist")
        void deleteVariant_ProductNotFound_ThrowsException() {
            when(productRepository.existsById(productId)).thenReturn(false);

            assertThatThrownBy(() -> productVariantService.deleteVariant(productId, variantId))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining("Product not found with id");

            verify(productVariantRepository, never()).findById(any());
            verify(productVariantRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw ProductVariantNotFoundException when variant not found")
        void deleteVariant_VariantNotFound_ThrowsException() {
            when(productRepository.existsById(productId)).thenReturn(true);
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productVariantService.deleteVariant(productId, variantId))
                    .isInstanceOf(ProductVariantNotFoundException.class)
                    .hasMessageContaining("Product variant not found with id");

            verify(productVariantRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────
    // searchVariants
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("searchVariants")
    class SearchVariantsTests {

        @Test
        @DisplayName("should return variants matching exact barcode query")
        void searchVariants_ExactBarcode() {
            var query = "7501234567890";
            var storeId = UUID.randomUUID();
            var pageable = PageRequest.of(0, 20);
            var searchResponse = new ProductVariantSearchResponse(
                    variantId, productId, companyStoreId, "7501234567890", "SKU-VAR-001",
                    "Talla M", new BigDecimal("199.99"), new BigDecimal("120.00"),
                    new BigDecimal("50.00"), true, "Producto Test", "PROD-001",
                    testVariant.getCreatedAt(), testVariant.getUpdatedAt()
            );
            var expectedPage = new PageImpl<>(List.of(searchResponse), pageable, 1);

            when(productVariantRepository.searchByQuery(query, storeId, pageable))
                    .thenReturn(expectedPage);

            var result = productVariantService.searchVariants(query, storeId, pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            var item = result.getContent().get(0);
            assertThat(item.id()).isEqualTo(variantId);
            assertThat(item.barCode()).isEqualTo("7501234567890");
            assertThat(item.sku()).isEqualTo("SKU-VAR-001");
            assertThat(item.productName()).isEqualTo("Producto Test");
            assertThat(item.productSku()).isEqualTo("PROD-001");
            assertThat(item.variantName()).isEqualTo("Talla M");
            verify(productVariantRepository).searchByQuery(query, storeId, pageable);
        }

        @Test
        @DisplayName("should return variants matching LIKE fallback on partial query")
        void searchVariants_LikeFallback() {
            var query = "talla";
            var storeId = UUID.randomUUID();
            var pageable = PageRequest.of(0, 20);
            var searchResponse = new ProductVariantSearchResponse(
                    variantId, productId, companyStoreId, "7501234567890", "SKU-VAR-001",
                    "Talla M", new BigDecimal("199.99"), new BigDecimal("120.00"),
                    new BigDecimal("50.00"), true, "Producto Test", "PROD-001",
                    testVariant.getCreatedAt(), testVariant.getUpdatedAt()
            );
            var expectedPage = new PageImpl<>(List.of(searchResponse), pageable, 1);

            when(productVariantRepository.searchByQuery(query, storeId, pageable))
                    .thenReturn(expectedPage);

            var result = productVariantService.searchVariants(query, storeId, pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).variantName()).isEqualTo("Talla M");
            assertThat(result.getContent().get(0).productName()).isEqualTo("Producto Test");
        }

        @Test
        @DisplayName("should return empty page when no variants match in the given store")
        void searchVariants_CrossStoreExclusion() {
            var query = "7501234567890";
            var otherStoreId = UUID.randomUUID();
            var pageable = PageRequest.of(0, 20);
            var expectedPage = new PageImpl<ProductVariantSearchResponse>(List.of(), pageable, 0);

            when(productVariantRepository.searchByQuery(query, otherStoreId, pageable))
                    .thenReturn(expectedPage);

            var result = productVariantService.searchVariants(query, otherStoreId, pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }

        @Test
        @DisplayName("should return empty page when query is null")
        void searchVariants_NullQuery_ReturnsEmpty() {
            var storeId = UUID.randomUUID();
            var pageable = PageRequest.of(0, 20);

            var result = productVariantService.searchVariants(null, storeId, pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEmpty();
            verify(productVariantRepository, never()).searchByQuery(any(), any(), any());
        }

        @Test
        @DisplayName("should return empty page when query is blank")
        void searchVariants_BlankQuery_ReturnsEmpty() {
            var storeId = UUID.randomUUID();
            var pageable = PageRequest.of(0, 20);

            var result = productVariantService.searchVariants("   ", storeId, pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEmpty();
            verify(productVariantRepository, never()).searchByQuery(any(), any(), any());
        }

        @Test
        @DisplayName("should pass pageable through to repository")
        void searchVariants_Pagination() {
            var query = "test";
            var storeId = UUID.randomUUID();
            var pageable = PageRequest.of(1, 5);
            var searchResponse = new ProductVariantSearchResponse(
                    variantId, productId, companyStoreId, "7501234567890", "SKU-VAR-001",
                    "Talla M", new BigDecimal("199.99"), new BigDecimal("120.00"),
                    new BigDecimal("50.00"), true, "Producto Test", "PROD-001",
                    testVariant.getCreatedAt(), testVariant.getUpdatedAt()
            );
            var expectedPage = new PageImpl<>(List.of(searchResponse), pageable, 1);

            when(productVariantRepository.searchByQuery(query, storeId, pageable))
                    .thenReturn(expectedPage);

            var result = productVariantService.searchVariants(query, storeId, pageable);

            assertThat(result.getNumber()).isEqualTo(1);
            assertThat(result.getSize()).isEqualTo(5);
            verify(productVariantRepository).searchByQuery(query, storeId, pageable);
        }
    }
}
