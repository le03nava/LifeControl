package com.lifecontrol.api.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.product.attribute.service.ProductAttributeCatalogService;
import com.lifecontrol.api.product.category.exception.ProductCategoryNotFoundException;
import com.lifecontrol.api.product.category.model.ProductCategory;
import com.lifecontrol.api.product.category.repository.ProductCategoryRepository;
import com.lifecontrol.api.product.dto.ProductRequest;
import com.lifecontrol.api.product.exception.DuplicateProductException;
import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductService Tests")
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductCategoryRepository productCategoryRepository;

    @Mock
    private ProductAttributeCatalogService productAttributeCatalogService;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    @InjectMocks
    private ProductService productService;

    private UUID companyId;
    private Company company;
    private UUID productId;
    private Product testProduct;
    private ProductRequest testRequest;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        company = Company.builder()
                .id(companyId)
                .companyKey("acme")
                .companyName("Acme")
                .build();
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

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
        testRequest = new ProductRequest("SKU-001", "Test Product", "Test", "SAT-001", "SERVICE", null, null);
    }

    @Nested
    @DisplayName("createProduct")
    class CreateProductTests {

        @Test
        @DisplayName("should create product with all fields and return response")
        void shouldCreateProduct_WithAllFields() {
            var request = new ProductRequest(
                    "SKU-NEW", "New Product", "NP", "SAT-NEW", "GOODS", Map.of("color", "red", "weight", "2kg"), null);
            when(productRepository.existsBySkuAndCompanyId("SKU-NEW", companyId))
                    .thenReturn(false);
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
                var p = (Product) inv.getArgument(0);
                // Simulate JPA assigning ID and timestamps
                p.setId(productId);
                p.setEnabled(true);
                return p;
            });

            var result = productService.createProduct(companyId, request);

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
            var request = new ProductRequest("SKU-SIMPLE", "Simple Product", null, null, null, null, null);
            when(productRepository.existsBySkuAndCompanyId("SKU-SIMPLE", companyId))
                    .thenReturn(false);
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
                var p = (Product) inv.getArgument(0);
                p.setId(productId);
                p.setEnabled(true);
                return p;
            });

            var result = productService.createProduct(companyId, request);

            assertThat(result).isNotNull();
            assertThat(result.sku()).isEqualTo("SKU-SIMPLE");
            assertThat(result.name()).isEqualTo("Simple Product");
            assertThat(result.attributes()).isNull();
        }

        @Test
        @DisplayName("should store the company named by the path")
        void shouldStoreTheCompanyNamedByThePath() {
            var categoryId = UUID.randomUUID();
            var category = ProductCategory.builder()
                    .id(categoryId)
                    .company(company)
                    .categoryName("Calzado")
                    .build();
            var request = new ProductRequest("SKU-CAT", "Zapato", null, null, null, null, categoryId);

            when(productRepository.existsBySkuAndCompanyId("SKU-CAT", companyId))
                    .thenReturn(false);
            when(productCategoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productService.createProduct(companyId, request);

            verify(productRepository)
                    .save(argThat(product -> product.getCompany() != null
                            && companyId.equals(product.getCompany().getId())));
            assertThat(result.companyId()).isEqualTo(companyId);
        }

        @Test
        @DisplayName("should store the company named by the path when the request carries no category")
        void shouldStoreTheCompanyWithoutCategory() {
            var request = new ProductRequest("SKU-ORPHAN", "Sin clasificar", null, null, null, null, null);

            when(productRepository.existsBySkuAndCompanyId("SKU-ORPHAN", companyId))
                    .thenReturn(false);
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productService.createProduct(companyId, request);

            verify(productRepository)
                    .save(argThat(
                            product -> companyId.equals(product.getCompany().getId())));
            assertThat(result.companyId()).isEqualTo(companyId);
            assertThat(result.categoryId()).isNull();
        }

        @Test
        @DisplayName("should verify the caller's access to the company before writing anything")
        void shouldVerifyCompanyAccess() {
            var request = new ProductRequest("SKU-GUARD", "Guardado", null, null, null, null, null);
            when(productRepository.existsBySkuAndCompanyId("SKU-GUARD", companyId))
                    .thenReturn(false);
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            productService.createProduct(companyId, request);

            verify(currentUserContext).verifyCompanyAccess(companyId);
        }

        @Test
        @DisplayName("should throw DuplicateProductException when SKU already exists")
        void shouldThrowDuplicateProductException_WhenSkuExists() {
            var request = new ProductRequest("SKU-DUP", "Dup Product", null, null, null, null, null);
            when(productRepository.existsBySkuAndCompanyId("SKU-DUP", companyId))
                    .thenReturn(true);

            assertThatThrownBy(() -> productService.createProduct(companyId, request))
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
            var request = new ProductRequest("SKU-001", "New Name", "New", "SAT-NEW", "GOODS", null, null);

            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.of(existing));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productService.updateProduct(companyId, productId, request);

            assertThat(result.name()).isEqualTo("New Name");
            assertThat(result.shortName()).isEqualTo("New");
            assertThat(result.satCode()).isEqualTo("SAT-NEW");
            assertThat(result.productType()).isEqualTo("GOODS");
        }

        @Test
        @DisplayName("should replace attributes with the request's set instead of merging")
        void shouldReplaceAttributes_FullUpdate() {
            var existing = Product.builder()
                    .id(productId)
                    .sku("SKU-001")
                    .name("Product")
                    .enabled(true)
                    .attributes(new HashMap<>(Map.of("color", "red", "size", "L")))
                    .build();
            var request = new ProductRequest("SKU-001", "Product", null, null, null, Map.of("weight", "2kg"), null);

            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.of(existing));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productService.updateProduct(companyId, productId, request);

            // The request is authoritative for the set it sends: a key it omits is gone. The previous
            // shallow `putAll` kept `color` and `size` alive and invisible (record L4).
            assertThat(result.attributes()).containsOnlyKeys("weight");
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
            var request = new ProductRequest("SKU-001", "Product", null, null, null, null, null);

            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.of(existing));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productService.updateProduct(companyId, productId, request);

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
            var request = new ProductRequest("SKU-001", "Product", null, null, null, Map.of(), null);

            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.of(existing));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productService.updateProduct(companyId, productId, request);

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
            var request = new ProductRequest("SKU-002", "Product", null, null, null, null, null);

            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.of(existing));
            when(productRepository.existsBySkuAndCompanyIdAndIdNot("SKU-002", companyId, productId))
                    .thenReturn(true);

            assertThatThrownBy(() -> productService.updateProduct(companyId, productId, request))
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
            var request = new ProductRequest("SKU-001", "New Name", null, null, null, null, null);

            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.of(existing));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productService.updateProduct(companyId, productId, request);

            assertThat(result.sku()).isEqualTo("SKU-001");
            verify(productRepository, never()).existsBySkuAndCompanyIdAndIdNot(any(), any(), any());
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product not found")
        void shouldThrowProductNotFoundException_WhenNotFound() {
            var request = new ProductRequest("SKU-001", "Product", null, null, null, null, null);
            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.updateProduct(companyId, productId, request))
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

            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.of(active));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            productService.deleteProduct(companyId, productId);

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

            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.of(disabled));

            assertThatThrownBy(() -> productService.deleteProduct(companyId, productId))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining(productId.toString());

            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product does not exist")
        void shouldThrowProductNotFoundException_WhenNotFound() {
            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.deleteProduct(companyId, productId))
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

            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.of(enabled));

            var result = productService.findProduct(companyId, productId);

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

            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.of(disabled));

            assertThatThrownBy(() -> productService.findProduct(companyId, productId))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining(productId.toString());
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product does not exist")
        void shouldThrowProductNotFoundException_WhenNotFound() {
            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.findProduct(companyId, productId))
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
            when(productRepository.findByCompanyIdAndEnabledTrue(companyId, pageable))
                    .thenReturn(page);

            var result = productService.listProducts(companyId, pageable, null, false);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getTotalElements()).isEqualTo(1);
            verify(productRepository).findByCompanyIdAndEnabledTrue(companyId, pageable);
            verify(productRepository, never()).findByCompanyId(any(), any());
        }

        @Test
        @DisplayName("should return paginated all products when includeDisabled=true and no search")
        void shouldListAllProducts_WhenIncludeDisabled() {
            var products = List.of(testProduct);
            var page = new PageImpl<>(products, pageable, 1);
            when(productRepository.findByCompanyId(companyId, pageable)).thenReturn(page);

            var result = productService.listProducts(companyId, pageable, null, true);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            verify(productRepository).findByCompanyId(companyId, pageable);
        }

        @Test
        @DisplayName("should search enabled products when search term provided and includeDisabled=false")
        void shouldSearchEnabledProducts_WithSearchTerm() {
            var products = List.of(testProduct);
            var page = new PageImpl<>(products, pageable, 1);
            when(productRepository.findByCompanyIdAndSearchTermAndEnabledTrue(companyId, "widget", pageable))
                    .thenReturn(page);

            var result = productService.listProducts(companyId, pageable, "widget", false);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            verify(productRepository).findByCompanyIdAndSearchTermAndEnabledTrue(companyId, "widget", pageable);
        }

        @Test
        @DisplayName("should search all products when search term provided and includeDisabled=true")
        void shouldSearchAllProducts_WithSearchTerm() {
            var products = List.of(testProduct);
            var page = new PageImpl<>(products, pageable, 25);
            when(productRepository.findByCompanyIdAndSearchTerm(eq(companyId), eq("widget"), any(Pageable.class)))
                    .thenReturn(page);

            var result = productService.listProducts(companyId, pageable, "widget", true);

            assertThat(result).isNotNull();
            assertThat(result.getTotalElements()).isEqualTo(25);
            verify(productRepository).findByCompanyIdAndSearchTerm(eq(companyId), eq("widget"), any(Pageable.class));
        }

        @Test
        @DisplayName("should return empty page when no products match")
        void shouldReturnEmptyPage_WhenNoMatch() {
            var page = new PageImpl<Product>(List.of(), pageable, 0);
            when(productRepository.findByCompanyIdAndEnabledTrue(companyId, pageable))
                    .thenReturn(page);

            var result = productService.listProducts(companyId, pageable, null, false);

            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isEqualTo(0);
        }

        @Test
        @DisplayName("should include correct pagination metadata")
        void shouldIncludePaginationMetadata() {
            var products = List.of(testProduct, testProduct, testProduct);
            var page = new PageImpl<>(products, PageRequest.of(1, 10), 25);
            when(productRepository.findByCompanyIdAndEnabledTrue(companyId, PageRequest.of(1, 10)))
                    .thenReturn(page);

            var result = productService.listProducts(companyId, PageRequest.of(1, 10), null, false);

            assertThat(result.getNumber()).isEqualTo(1);
            assertThat(result.getSize()).isEqualTo(10);
            assertThat(result.getTotalElements()).isEqualTo(25);
            assertThat(result.getTotalPages()).isEqualTo(3);
        }
    }

    /**
     * The tenancy boundary of T2, asserted where a mock can express it: the company in the path owns the
     * product, a row of another company is a 404 instead of a 200, and the SKU key is per company. The
     * database side of the same rules (the composite key and the per-company unique index) arrives with
     * V40 and is deliberately not what these pin.
     */
    @Nested
    @DisplayName("company scope")
    class CompanyScopeTests {

        @Test
        @DisplayName("should answer 404 when the product belongs to another company")
        void shouldAnswerNotFoundForAnotherCompanysProduct() {
            // What the company-scoped query answers for a row of another company: nothing.
            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.findProduct(companyId, productId))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining(productId.toString());
        }

        @Test
        @DisplayName("should answer 404 when update names another company's product and write nothing")
        void shouldAnswerNotFoundWhenUpdateNamesAnotherCompanysProduct() {
            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.updateProduct(companyId, productId, testRequest))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining(productId.toString());

            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("should answer 404 when delete names another company's product and write nothing")
        void shouldAnswerNotFoundWhenDeleteNamesAnotherCompanysProduct() {
            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.deleteProduct(companyId, productId))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining(productId.toString());

            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("should refuse a category of another company on create and store nothing")
        void shouldRefuseAnotherCompanysCategoryOnCreate() {
            var categoryId = UUID.randomUUID();
            var foreignCategory = ProductCategory.builder()
                    .id(categoryId)
                    .company(Company.builder()
                            .id(UUID.randomUUID())
                            .companyKey("otra")
                            .companyName("Otra")
                            .build())
                    .categoryName("Calzado")
                    .build();
            var request = new ProductRequest("SKU-FOREIGN", "Ajeno", null, null, null, null, categoryId);

            when(productRepository.existsBySkuAndCompanyId("SKU-FOREIGN", companyId))
                    .thenReturn(false);
            when(productCategoryRepository.findById(categoryId)).thenReturn(Optional.of(foreignCategory));

            assertThatThrownBy(() -> productService.createProduct(companyId, request))
                    .isInstanceOf(ProductCategoryNotFoundException.class)
                    .hasMessageContaining(categoryId.toString());

            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("should refuse a category of another company on update and leave the row untouched")
        void shouldRefuseAnotherCompanysCategoryOnUpdate() {
            var categoryId = UUID.randomUUID();
            var foreignCategory = ProductCategory.builder()
                    .id(categoryId)
                    .company(Company.builder()
                            .id(UUID.randomUUID())
                            .companyKey("otra")
                            .companyName("Otra")
                            .build())
                    .categoryName("Calzado")
                    .build();
            var stored = ProductCategory.builder()
                    .id(UUID.randomUUID())
                    .company(company)
                    .build();
            var existing = Product.builder()
                    .id(productId)
                    .sku("SKU-001")
                    .name("Test Product")
                    .enabled(true)
                    .company(company)
                    .category(stored)
                    .build();
            var request = new ProductRequest("SKU-001", "Renombrado", null, null, null, null, categoryId);

            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.of(existing));
            when(productCategoryRepository.findById(categoryId)).thenReturn(Optional.of(foreignCategory));

            assertThatThrownBy(() -> productService.updateProduct(companyId, productId, request))
                    .isInstanceOf(ProductCategoryNotFoundException.class)
                    .hasMessageContaining(categoryId.toString());

            assertThat(existing.getCategory()).isSameAs(stored);
            assertThat(existing.getName()).isEqualTo("Test Product");
            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("should accept the same SKU in another company")
        void shouldAcceptTheSameSkuInAnotherCompany() {
            // The row this SKU collides with lives in another company, so the company-scoped key is free.
            var request = new ProductRequest("SKU-SHARED", "Compartido", null, null, null, null, null);
            when(productRepository.existsBySkuAndCompanyId("SKU-SHARED", companyId))
                    .thenReturn(false);
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productService.createProduct(companyId, request);

            assertThat(result.sku()).isEqualTo("SKU-SHARED");
            assertThat(result.companyId()).isEqualTo(companyId);
            verify(productRepository)
                    .save(argThat(
                            product -> companyId.equals(product.getCompany().getId())));
        }

        @Test
        @DisplayName("should refuse a repeated SKU inside one company")
        void shouldRefuseARepeatedSkuInsideOneCompany() {
            var request = new ProductRequest("SKU-DUP", "Repetido", null, null, null, null, null);
            when(productRepository.existsBySkuAndCompanyId("SKU-DUP", companyId))
                    .thenReturn(true);

            assertThatThrownBy(() -> productService.createProduct(companyId, request))
                    .isInstanceOf(DuplicateProductException.class)
                    .hasMessageContaining("SKU-DUP");

            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("should list only the products of the company")
        void shouldListOnlyTheProductsOfTheCompany() {
            var pageable = PageRequest.of(0, 10);
            var mine = Product.builder()
                    .id(UUID.randomUUID())
                    .sku("SKU-MINE")
                    .name("Mía")
                    .enabled(true)
                    .company(company)
                    .build();
            when(productRepository.findByCompanyIdAndEnabledTrue(companyId, pageable))
                    .thenReturn(new PageImpl<>(List.of(mine), pageable, 1));

            var result = productService.listProducts(companyId, pageable, null, false);

            // The scope is a predicate of the query, not a filter applied to another company's page.
            assertThat(result.getContent()).extracting(r -> r.sku()).containsExactly("SKU-MINE");
            verify(productRepository).findByCompanyIdAndEnabledTrue(companyId, pageable);
        }
    }

    /**
     * The product -> category link: one nullable FK to the deepest selected node.
     *
     * <p>What these pin is the half the migration cannot: the request carries an id and nothing else,
     * so resolving it is the service's job, and the response has to report the <em>category's</em>
     * company because the product has no company of its own.</p>
     */
    @Nested
    @DisplayName("category link")
    class CategoryLinkTests {

        private UUID categoryId;
        private UUID categoryCompanyId;

        @BeforeEach
        void setUpCategoryIds() {
            categoryId = UUID.randomUUID();
            // The write path refuses a category of another company, so the fixture's category is one of
            // the product's own company: the two ids agree by construction, which is the T2 model.
            categoryCompanyId = companyId;
        }

        @Test
        @DisplayName("should store the resolved category and report its company on create")
        void shouldStoreCategory_WhenCreateReceivesAnId() {
            var category = aCategory(categoryId, categoryCompanyId);
            var request = new ProductRequest("SKU-CAT", "Classified", null, null, null, null, categoryId);
            when(productRepository.existsBySkuAndCompanyId("SKU-CAT", companyId))
                    .thenReturn(false);
            when(productCategoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
                var p = (Product) inv.getArgument(0);
                p.setId(productId);
                p.setEnabled(true);
                return p;
            });

            var result = productService.createProduct(companyId, request);

            var captor = ArgumentCaptor.forClass(Product.class);
            verify(productRepository).save(captor.capture());
            assertThat(captor.getValue().getCategory()).isSameAs(category);
            assertThat(result.categoryId()).isEqualTo(categoryId);
            assertThat(result.categoryCompanyId()).isEqualTo(categoryCompanyId);
        }

        @Test
        @DisplayName("should leave the product unclassified and report two nulls when no id is sent")
        void shouldLeaveCategoryNull_WhenCreateReceivesNoId() {
            var request = new ProductRequest("SKU-PLAIN", "Plain", null, null, null, null, null);
            when(productRepository.existsBySkuAndCompanyId("SKU-PLAIN", companyId))
                    .thenReturn(false);
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
                var p = (Product) inv.getArgument(0);
                p.setId(productId);
                p.setEnabled(true);
                return p;
            });

            var result = productService.createProduct(companyId, request);

            assertThat(result.categoryId()).isNull();
            assertThat(result.categoryCompanyId()).isNull();
            verify(productCategoryRepository, never()).findById(any());
        }

        @Test
        @DisplayName("should throw the category 404 instead of an FK violation when the id does not exist")
        void shouldRejectUnknownCategory_WhenCreateReceivesAnUnknownId() {
            var unknown = UUID.randomUUID();
            var request = new ProductRequest("SKU-BAD", "Bad", null, null, null, null, unknown);
            when(productRepository.existsBySkuAndCompanyId("SKU-BAD", companyId))
                    .thenReturn(false);
            when(productCategoryRepository.findById(unknown)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.createProduct(companyId, request))
                    .isInstanceOf(ProductCategoryNotFoundException.class)
                    .hasMessageContaining(unknown.toString());

            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("should clear the link when update sends no category")
        void shouldClearCategory_WhenUpdateSendsNull() {
            var existing = Product.builder()
                    .id(productId)
                    .sku("SKU-001")
                    .name("Test Product")
                    .enabled(true)
                    // Only has to exist: what the test proves is that the update clears it, so nothing
                    // ever reads its id or its company (a stubbed mock would be a strict-stub failure).
                    .category(mock(ProductCategory.class))
                    .build();
            var request = new ProductRequest("SKU-001", "Test Product", null, null, null, null, null);
            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.of(existing));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productService.updateProduct(companyId, productId, request);

            assertThat(existing.getCategory()).isNull();
            assertThat(result.categoryId()).isNull();
            assertThat(result.categoryCompanyId()).isNull();
        }

        @Test
        @DisplayName("should replace the link when update sends another category")
        void shouldReplaceCategory_WhenUpdateSendsAnotherId() {
            // The previous node is only replaced, never read: no stubs, so no unused stubbing.
            var previous = mock(ProductCategory.class);
            var replacement = aCategory(categoryId, categoryCompanyId);
            var existing = Product.builder()
                    .id(productId)
                    .sku("SKU-001")
                    .name("Test Product")
                    .enabled(true)
                    .category(previous)
                    .build();
            var request = new ProductRequest("SKU-001", "Test Product", null, null, null, null, categoryId);
            when(productRepository.findByIdAndCompanyId(productId, companyId)).thenReturn(Optional.of(existing));
            when(productCategoryRepository.findById(categoryId)).thenReturn(Optional.of(replacement));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productService.updateProduct(companyId, productId, request);

            assertThat(existing.getCategory()).isSameAs(replacement);
            assertThat(result.categoryId()).isEqualTo(categoryId);
            assertThat(result.categoryCompanyId()).isEqualTo(categoryCompanyId);
        }

        /** A category mock: only the two properties the response projects are stubbed. */
        private ProductCategory aCategory(UUID id, UUID companyId) {
            var company = mock(Company.class);
            when(company.getId()).thenReturn(companyId);
            var category = mock(ProductCategory.class);
            when(category.getId()).thenReturn(id);
            when(category.getCompany()).thenReturn(company);
            return category;
        }
    }
}
