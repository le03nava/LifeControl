package com.lifecontrol.api.product.service;

import com.lifecontrol.api.product.dto.ProductRequest;
import com.lifecontrol.api.product.dto.ProductResponse;
import com.lifecontrol.api.product.exception.DuplicateProductException;
import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.repository.ProductRepository;
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

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductService Tests")
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    private UUID productId;
    private Product testProduct;
    private ProductRequest testRequest;

    @BeforeEach
    void setUp() {
        productId = UUID.randomUUID();
        testProduct = Product.builder()
                .id(productId)
                .sku("SKU-001")
                .name("Test Product")
                .shortName("Test")
                .satCode("SAT-001")
                .productType("SERVICE")
                .enabled(true)
                .build();
        testRequest = new ProductRequest("SKU-001", "Test Product", "Test", "SAT-001", "SERVICE", null);
    }

    @Nested
    @DisplayName("createProduct")
    class CreateProductTests {

        @Test
        @DisplayName("should create product with all fields and return response")
        void shouldCreateProduct_WithAllFields() {
            var request = new ProductRequest("SKU-NEW", "New Product", "NP", "SAT-NEW", "GOODS",
                    Map.of("color", "red", "weight", "2kg"));
            when(productRepository.existsBySku("SKU-NEW")).thenReturn(false);
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
                var p = (Product) inv.getArgument(0);
                // Simulate JPA assigning ID and timestamps
                p.setId(productId);
                p.setEnabled(true);
                return p;
            });

            var result = productService.createProduct(request);

            assertThat(result).isNotNull();
            assertThat(result.sku()).isEqualTo("SKU-NEW");
            assertThat(result.name()).isEqualTo("New Product");
            assertThat(result.shortName()).isEqualTo("NP");
            assertThat(result.satCode()).isEqualTo("SAT-NEW");
            assertThat(result.productType()).isEqualTo("GOODS");
            assertThat(result.attributes()).containsEntry("color", "red");
            assertThat(result.attributes()).containsEntry("weight", "2kg");
            assertThat(result.enabled()).isTrue();
            assertThat(result.id()).isEqualTo(productId);
        }

        @Test
        @DisplayName("should create product without attributes")
        void shouldCreateProduct_WithoutAttributes() {
            var request = new ProductRequest("SKU-SIMPLE", "Simple Product", null, null, null, null);
            when(productRepository.existsBySku("SKU-SIMPLE")).thenReturn(false);
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
                var p = (Product) inv.getArgument(0);
                p.setId(productId);
                p.setEnabled(true);
                return p;
            });

            var result = productService.createProduct(request);

            assertThat(result).isNotNull();
            assertThat(result.sku()).isEqualTo("SKU-SIMPLE");
            assertThat(result.name()).isEqualTo("Simple Product");
            assertThat(result.attributes()).isNull();
        }

        @Test
        @DisplayName("should throw DuplicateProductException when SKU already exists")
        void shouldThrowDuplicateProductException_WhenSkuExists() {
            var request = new ProductRequest("SKU-DUP", "Dup Product", null, null, null, null);
            when(productRepository.existsBySku("SKU-DUP")).thenReturn(true);

            assertThatThrownBy(() -> productService.createProduct(request))
                    .isInstanceOf(DuplicateProductException.class)
                    .hasMessageContaining("SKU-DUP");

            verify(productRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateProduct")
    class UpdateProductTests {

        @Test
        @DisplayName("should update all scalar fields")
        void shouldUpdateProduct_AllScalarFields() {
            var existing = Product.builder()
                    .id(productId)
                    .sku("SKU-001")
                    .name("Old Name")
                    .shortName("Old")
                    .enabled(true)
                    .build();
            var request = new ProductRequest("SKU-001", "New Name", "New", "SAT-NEW", "GOODS", null);

            when(productRepository.findById(productId)).thenReturn(Optional.of(existing));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productService.updateProduct(productId, request);

            assertThat(result.name()).isEqualTo("New Name");
            assertThat(result.shortName()).isEqualTo("New");
            assertThat(result.satCode()).isEqualTo("SAT-NEW");
            assertThat(result.productType()).isEqualTo("GOODS");
        }

        @Test
        @DisplayName("should merge attributes via putAll (preserve existing, add new)")
        void shouldMergeAttributes_PartialUpdate() {
            var existing = Product.builder()
                    .id(productId)
                    .sku("SKU-001")
                    .name("Product")
                    .enabled(true)
                    .attributes(new HashMap<>(Map.of("color", "red", "size", "L")))
                    .build();
            var request = new ProductRequest("SKU-001", "Product", null, null, null,
                    Map.of("weight", "2kg"));

            when(productRepository.findById(productId)).thenReturn(Optional.of(existing));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productService.updateProduct(productId, request);

            assertThat(result.attributes()).containsEntry("color", "red");
            assertThat(result.attributes()).containsEntry("size", "L");
            assertThat(result.attributes()).containsEntry("weight", "2kg");
        }

        @Test
        @DisplayName("should preserve existing attributes when none provided in request")
        void shouldPreserveAttributes_WhenNoneProvided() {
            Map<String, Object> existingAttrs = new HashMap<>(Map.of("color", "blue"));
            var existing = Product.builder()
                    .id(productId)
                    .sku("SKU-001")
                    .name("Product")
                    .enabled(true)
                    .attributes(existingAttrs)
                    .build();
            var request = new ProductRequest("SKU-001", "Product", null, null, null, null);

            when(productRepository.findById(productId)).thenReturn(Optional.of(existing));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productService.updateProduct(productId, request);

            assertThat(result.attributes()).containsEntry("color", "blue");
        }

        @Test
        @DisplayName("should clear attributes when empty map provided in request")
        void shouldClearAttributes_WhenEmptyMapProvided() {
            var existing = Product.builder()
                    .id(productId)
                    .sku("SKU-001")
                    .name("Product")
                    .enabled(true)
                    .attributes(new HashMap<>(Map.of("color", "red")))
                    .build();
            var request = new ProductRequest("SKU-001", "Product", null, null, null, Map.of());

            when(productRepository.findById(productId)).thenReturn(Optional.of(existing));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productService.updateProduct(productId, request);

            assertThat(result.attributes()).isEmpty();
        }

        @Test
        @DisplayName("should throw DuplicateProductException when SKU conflicts with another product")
        void shouldThrowDuplicateProductException_WhenSkuConflicts() {
            var existing = Product.builder()
                    .id(productId)
                    .sku("SKU-001")
                    .name("Product")
                    .enabled(true)
                    .build();
            var request = new ProductRequest("SKU-002", "Product", null, null, null, null);

            when(productRepository.findById(productId)).thenReturn(Optional.of(existing));
            when(productRepository.existsBySkuAndIdNot("SKU-002", productId)).thenReturn(true);

            assertThatThrownBy(() -> productService.updateProduct(productId, request))
                    .isInstanceOf(DuplicateProductException.class)
                    .hasMessageContaining("SKU-002");

            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("should not check SKU uniqueness when SKU has not changed")
        void shouldNotCheckSkuUniqueness_WhenSkuUnchanged() {
            var existing = Product.builder()
                    .id(productId)
                    .sku("SKU-001")
                    .name("Old Name")
                    .enabled(true)
                    .build();
            var request = new ProductRequest("SKU-001", "New Name", null, null, null, null);

            when(productRepository.findById(productId)).thenReturn(Optional.of(existing));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productService.updateProduct(productId, request);

            assertThat(result.sku()).isEqualTo("SKU-001");
            verify(productRepository, never()).existsBySkuAndIdNot(any(), any());
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product not found")
        void shouldThrowProductNotFoundException_WhenNotFound() {
            var request = new ProductRequest("SKU-001", "Product", null, null, null, null);
            when(productRepository.findById(productId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.updateProduct(productId, request))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining(productId.toString());
        }
    }

    @Nested
    @DisplayName("deleteProduct")
    class DeleteProductTests {

        @Test
        @DisplayName("should soft-delete active product by setting enabled=false")
        void shouldSoftDelete_ActiveProduct() {
            var active = Product.builder()
                    .id(productId)
                    .sku("SKU-001")
                    .name("Product")
                    .enabled(true)
                    .build();

            when(productRepository.findById(productId)).thenReturn(Optional.of(active));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            productService.deleteProduct(productId);

            assertThat(active.getEnabled()).isFalse();
            verify(productRepository).save(active);
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product already soft-deleted")
        void shouldThrowProductNotFoundException_WhenAlreadyDeleted() {
            var disabled = Product.builder()
                    .id(productId)
                    .sku("SKU-001")
                    .name("Product")
                    .enabled(false)
                    .build();

            when(productRepository.findById(productId)).thenReturn(Optional.of(disabled));

            assertThatThrownBy(() -> productService.deleteProduct(productId))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining(productId.toString());

            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product does not exist")
        void shouldThrowProductNotFoundException_WhenNotFound() {
            when(productRepository.findById(productId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.deleteProduct(productId))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining(productId.toString());
        }
    }

    @Nested
    @DisplayName("findProduct")
    class FindProductTests {

        @Test
        @DisplayName("should return product when found and enabled")
        void shouldReturnProduct_WhenFoundAndEnabled() {
            var enabled = Product.builder()
                    .id(productId)
                    .sku("SKU-001")
                    .name("Product")
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(productRepository.findById(productId)).thenReturn(Optional.of(enabled));

            var result = productService.findProduct(productId);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(productId);
            assertThat(result.sku()).isEqualTo("SKU-001");
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product is soft-deleted")
        void shouldThrowProductNotFoundException_WhenDisabled() {
            var disabled = Product.builder()
                    .id(productId)
                    .sku("SKU-001")
                    .name("Product")
                    .enabled(false)
                    .build();

            when(productRepository.findById(productId)).thenReturn(Optional.of(disabled));

            assertThatThrownBy(() -> productService.findProduct(productId))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining(productId.toString());
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product does not exist")
        void shouldThrowProductNotFoundException_WhenNotFound() {
            when(productRepository.findById(productId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.findProduct(productId))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining(productId.toString());
        }
    }

    @Nested
    @DisplayName("listProducts")
    class ListProductsTests {

        private final Pageable pageable = PageRequest.of(0, 10);

        @Test
        @DisplayName("should return paginated enabled products when no search and includeDisabled=false")
        void shouldListEnabledProducts_WithoutSearch() {
            var products = List.of(testProduct);
            var page = new PageImpl<>(products, pageable, 1);
            when(productRepository.findByEnabledTrue(pageable)).thenReturn(page);

            var result = productService.listProducts(pageable, null, false);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getTotalElements()).isEqualTo(1);
            verify(productRepository).findByEnabledTrue(pageable);
            verify(productRepository, never()).findAll(pageable);
        }

        @Test
        @DisplayName("should return paginated all products when includeDisabled=true and no search")
        void shouldListAllProducts_WhenIncludeDisabled() {
            var products = List.of(testProduct);
            var page = new PageImpl<>(products, pageable, 1);
            when(productRepository.findAll(pageable)).thenReturn(page);

            var result = productService.listProducts(pageable, null, true);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            verify(productRepository).findAll(pageable);
        }

        @Test
        @DisplayName("should search enabled products when search term provided and includeDisabled=false")
        void shouldSearchEnabledProducts_WithSearchTerm() {
            var products = List.of(testProduct);
            var page = new PageImpl<>(products, pageable, 1);
            when(productRepository.findBySearchTermAndEnabledTrue("widget", pageable)).thenReturn(page);

            var result = productService.listProducts(pageable, "widget", false);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            verify(productRepository).findBySearchTermAndEnabledTrue("widget", pageable);
        }

        @Test
        @DisplayName("should search all products when search term provided and includeDisabled=true")
        void shouldSearchAllProducts_WithSearchTerm() {
            var products = List.of(testProduct);
            var page = new PageImpl<>(products, pageable, 25);
            when(productRepository.findBySearchTerm(eq("widget"), any(Pageable.class))).thenReturn(page);

            var result = productService.listProducts(pageable, "widget", true);

            assertThat(result).isNotNull();
            assertThat(result.getTotalElements()).isEqualTo(25);
            verify(productRepository).findBySearchTerm(eq("widget"), any(Pageable.class));
        }

        @Test
        @DisplayName("should return empty page when no products match")
        void shouldReturnEmptyPage_WhenNoMatch() {
            var page = new PageImpl<Product>(List.of(), pageable, 0);
            when(productRepository.findByEnabledTrue(pageable)).thenReturn(page);

            var result = productService.listProducts(pageable, null, false);

            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isEqualTo(0);
        }

        @Test
        @DisplayName("should include correct pagination metadata")
        void shouldIncludePaginationMetadata() {
            var products = List.of(testProduct, testProduct, testProduct);
            var page = new PageImpl<>(products, PageRequest.of(1, 10), 25);
            when(productRepository.findByEnabledTrue(PageRequest.of(1, 10))).thenReturn(page);

            var result = productService.listProducts(PageRequest.of(1, 10), null, false);

            assertThat(result.getNumber()).isEqualTo(1);
            assertThat(result.getSize()).isEqualTo(10);
            assertThat(result.getTotalElements()).isEqualTo(25);
            assertThat(result.getTotalPages()).isEqualTo(3);
        }
    }
}
