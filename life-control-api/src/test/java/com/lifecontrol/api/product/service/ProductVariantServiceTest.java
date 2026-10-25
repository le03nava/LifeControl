package com.lifecontrol.api.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.inventory.exception.StoreInventorySettingsNotFoundException;
import com.lifecontrol.api.inventory.repository.InventoryMovementRepository;
import com.lifecontrol.api.inventory.service.InventoryService;
import com.lifecontrol.api.measureunit.exception.MeasureUnitNotFoundException;
import com.lifecontrol.api.measureunit.model.MeasureUnit;
import com.lifecontrol.api.measureunit.model.MeasureUnitType;
import com.lifecontrol.api.measureunit.repository.MeasureUnitRepository;
import com.lifecontrol.api.product.dto.ProductVariantRequest;
import com.lifecontrol.api.product.dto.ProductVariantResponse;
import com.lifecontrol.api.product.dto.ProductVariantSearchResponse;
import com.lifecontrol.api.product.dto.ProductVariantStoreStockRequest;
import com.lifecontrol.api.product.dto.ProductVariantStoreStockResponse;
import com.lifecontrol.api.product.exception.DuplicateProductVariantException;
import com.lifecontrol.api.product.exception.MeasureUnitChangeNotAllowedException;
import com.lifecontrol.api.product.exception.MeasureUnitTypeMismatchException;
import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.model.ProductVariantStoreStock;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductVariantService Tests")
class ProductVariantServiceTest {

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CompanyStoreRepository companyStoreRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private MeasureUnitRepository measureUnitRepository;

    @Mock
    private InventoryMovementRepository inventoryMovementRepository;

    private UUID companyId;
    private UUID companyCountryId;
    private UUID regionId;
    private UUID zoneId;
    private CompanyStore testCompanyStore;

    @InjectMocks
    private ProductVariantService productVariantService;

    private UUID productId;
    private UUID variantId;
    private UUID companyStoreId;
    private Product testProduct;
    private ProductVariant testVariant;
    private ProductVariantRequest testVariantRequest;

    private UUID pieceUnitId;
    private UUID kilogramUnitId;
    private UUID serviceUnitId;
    private MeasureUnit pieceUnit;
    private MeasureUnit kilogramUnit;
    private MeasureUnit serviceUnit;

    @BeforeEach
    void setUp() {
        productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        companyStoreId = UUID.randomUUID();
        var now = LocalDateTime.now();

        testProduct = Product.builder()
                .id(productId)
                .sku("PROD-001")
                .name("Producto Test")
                .enabled(true)
                .build();

        testVariant = ProductVariant.builder()
                .id(variantId)
                .productId(productId)
                .barCode("7501234567890")
                .variantName("Talla M")
                .enabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        testVariantRequest = new ProductVariantRequest("7501234567890", "Talla M", null);

        // The catalog: two PRODUCT units (so a same-type unit -> unit transition is expressible) and
        // one SERVICE unit (the only one the D4 guard can contradict at the type level).
        pieceUnitId = UUID.randomUUID();
        kilogramUnitId = UUID.randomUUID();
        serviceUnitId = UUID.randomUUID();
        pieceUnit = MeasureUnit.builder()
                .id(pieceUnitId)
                .measureUnitName("Pieza")
                .measureUnitShortName("Pza")
                .unitType(MeasureUnitType.PRODUCT)
                .satCode("H87")
                .enabled(true)
                .build();
        kilogramUnit = MeasureUnit.builder()
                .id(kilogramUnitId)
                .measureUnitName("Kilogramo")
                .measureUnitShortName("Kg")
                .unitType(MeasureUnitType.PRODUCT)
                .satCode("KGM")
                .enabled(true)
                .build();
        serviceUnit = MeasureUnit.builder()
                .id(serviceUnitId)
                .measureUnitName("Servicio")
                .measureUnitShortName("Serv")
                .unitType(MeasureUnitType.SERVICE)
                .satCode("E48")
                .enabled(true)
                .build();

        companyId = UUID.randomUUID();
        companyCountryId = UUID.randomUUID();
        regionId = UUID.randomUUID();
        zoneId = UUID.randomUUID();

        var company = Company.builder()
                .id(companyId)
                .companyKey("TEST-KEY")
                .companyName("Test Company")
                .rfc("TEST123456ABC")
                .enabled(true)
                .build();
        var companyCountry =
                CompanyCountry.builder().id(companyCountryId).company(company).build();
        var region = CompanyRegion.builder()
                .id(regionId)
                .companyCountry(companyCountry)
                .regionCode("01")
                .regionName("Test Region")
                .enabled(true)
                .build();
        var zone = CompanyZone.builder()
                .id(zoneId)
                .companyRegion(region)
                .zoneCode("01")
                .zoneName("Test Zone")
                .enabled(true)
                .build();
        // The store guard derives company -> country -> region -> zone -> store from the store
        // itself, because the endpoint path carries only the store id, so the fixture must carry
        // the whole chain.
        testCompanyStore = CompanyStore.builder()
                .id(companyStoreId)
                .companyZone(zone)
                .storeName("Test Store")
                .enabled(true)
                .build();
    }

    // ─────────────────────────────────────────────
    // listVariants
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("listVariants")
    class ListVariantsTests {

        @Test
        @DisplayName("should return paginated global definitions for a product")
        void listVariants_Paginated() {
            var pageable = PageRequest.of(0, 12);
            var expectedPage = new PageImpl<>(List.of(testVariant), pageable, 1);

            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findByProductIdAndEnabledTrueOrderByCreatedAtDesc(productId, pageable))
                    .thenReturn(expectedPage);

            Page<ProductVariantResponse> result = productVariantService.listVariants(productId, pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).id()).isEqualTo(variantId);
            assertThat(result.getContent().get(0).productId()).isEqualTo(productId);
            assertThat(result.getContent().get(0).variantName()).isEqualTo("Talla M");
            assertThat(result.getContent().get(0).barCode()).isEqualTo("7501234567890");
            // No store selected: the store-scoped fields are null and sku is the PRODUCT sku.
            assertThat(result.getContent().get(0).companyStoreId()).isNull();
            assertThat(result.getContent().get(0).listPrice()).isNull();
            assertThat(result.getContent().get(0).costPrice()).isNull();
            assertThat(result.getContent().get(0).stock()).isNull();
            assertThat(result.getContent().get(0).sku()).isEqualTo("PROD-001");
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getTotalPages()).isEqualTo(1);
            verify(productRepository).findById(productId);
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product does not exist")
        void listVariants_ProductNotFound_ThrowsException() {
            var pageable = PageRequest.of(0, 12);
            when(productRepository.findById(productId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productVariantService.listVariants(productId, pageable))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining("Product not found with id");

            verify(productVariantRepository, never()).findByProductIdAndEnabledTrueOrderByCreatedAtDesc(any(), any());
            verify(productVariantRepository, never()).findStoreScopedByProductIdAndStoreId(any(), any(), any());
        }

        @Test
        @DisplayName("should use the store-joined projection when a store id is supplied")
        void listVariants_WithStoreId_UsesStoreScopedQuery() {
            var pageable = PageRequest.of(0, 12);
            var storeResponse = new ProductVariantResponse(
                    variantId,
                    productId,
                    companyStoreId,
                    "7501234567890",
                    "PROD-001",
                    "Talla M",
                    new BigDecimal("199.99"),
                    new BigDecimal("120.00"),
                    new BigDecimal("50.00"),
                    true,
                    testVariant.getCreatedAt(),
                    testVariant.getUpdatedAt(),
                    null);
            var expectedPage = new PageImpl<>(List.of(storeResponse), pageable, 1);

            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.findStoreScopedByProductIdAndStoreId(productId, companyStoreId, pageable))
                    .thenReturn(expectedPage);

            Page<ProductVariantResponse> result =
                    productVariantService.listVariants(productId, companyStoreId, pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).companyStoreId()).isEqualTo(companyStoreId);
            assertThat(result.getContent().get(0).stock()).isEqualByComparingTo(new BigDecimal("50.00"));
            assertThat(result.getContent().get(0).sku()).isEqualTo("PROD-001");
            // The store-scoped branch must verify the chain derived from the store, not the raw id.
            verify(currentUserContext)
                    .verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, companyStoreId);
            verify(productVariantRepository).findStoreScopedByProductIdAndStoreId(productId, companyStoreId, pageable);
            verify(productVariantRepository, never()).findByProductIdAndEnabledTrueOrderByCreatedAtDesc(any(), any());
        }

        @Test
        @DisplayName("should reject a store outside the caller's scope and read nothing")
        void listVariants_StoreOutsideCallerScope_Throws403() {
            var pageable = PageRequest.of(0, 12);
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            doThrow(new AccessDeniedException("Access denied"))
                    .when(currentUserContext)
                    .verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, companyStoreId);

            assertThatThrownBy(() -> productVariantService.listVariants(productId, companyStoreId, pageable))
                    .isInstanceOf(AccessDeniedException.class);

            verify(productVariantRepository, never()).findStoreScopedByProductIdAndStoreId(any(), any(), any());
        }

        @Test
        @DisplayName("should throw CompanyStoreNotFoundException when the store does not exist")
        void listVariants_UnknownStore_ThrowsException() {
            var pageable = PageRequest.of(0, 12);
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productVariantService.listVariants(productId, companyStoreId, pageable))
                    .isInstanceOf(CompanyStoreNotFoundException.class)
                    .hasMessageContaining("Store not found with id");

            verify(currentUserContext, never()).verifyCompanyStoreAccess(any(), any(), any(), any(), any());
            verify(productVariantRepository, never()).findStoreScopedByProductIdAndStoreId(any(), any(), any());
        }

        @Test
        @DisplayName("should keep using the product-scoped finder when the store id is null")
        void listVariants_NullStoreId_UsesProductScopedFinder() {
            var pageable = PageRequest.of(0, 12);
            var expectedPage = new PageImpl<>(List.of(testVariant), pageable, 1);

            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findByProductIdAndEnabledTrueOrderByCreatedAtDesc(productId, pageable))
                    .thenReturn(expectedPage);

            Page<ProductVariantResponse> result = productVariantService.listVariants(productId, (UUID) null, pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).id()).isEqualTo(variantId);
            // The global branch must neither read nor guard the store.
            verify(companyStoreRepository, never()).findById(any());
            verify(currentUserContext, never()).verifyCompanyStoreAccess(any(), any(), any(), any(), any());
            verify(productVariantRepository).findByProductIdAndEnabledTrueOrderByCreatedAtDesc(productId, pageable);
            verify(productVariantRepository, never()).findStoreScopedByProductIdAndStoreId(any(), any(), any());
        }

        @Test
        @DisplayName("should return empty page when product has no variants")
        void listVariants_EmptyPage() {
            var pageable = PageRequest.of(0, 12);
            var expectedPage = new PageImpl<ProductVariant>(List.of(), pageable, 0);

            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findByProductIdAndEnabledTrueOrderByCreatedAtDesc(productId, pageable))
                    .thenReturn(expectedPage);

            Page<ProductVariantResponse> result = productVariantService.listVariants(productId, pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }

        @Test
        @DisplayName("should default the new overload to enabled-only when includeDisabled is false")
        void listVariants_IncludeDisabledFalse_UsesEnabledOnlyFinder() {
            var pageable = PageRequest.of(0, 12);
            var expectedPage = new PageImpl<>(List.of(testVariant), pageable, 1);

            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findByProductIdAndEnabledTrueOrderByCreatedAtDesc(productId, pageable))
                    .thenReturn(expectedPage);

            Page<ProductVariantResponse> result = productVariantService.listVariants(productId, null, false, pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(productVariantRepository).findByProductIdAndEnabledTrueOrderByCreatedAtDesc(productId, pageable);
            verify(productVariantRepository, never()).findByProductIdOrderByCreatedAtDesc(any(), any());
        }

        @Test
        @DisplayName("should include disabled global definitions when includeDisabled is true")
        void listVariants_IncludeDisabledTrue_UsesUnfilteredFinderAndIncludesDisabled() {
            var pageable = PageRequest.of(0, 12);
            var disabledVariant = ProductVariant.builder()
                    .id(UUID.randomUUID())
                    .productId(productId)
                    .barCode("7509999999999")
                    .variantName("Talla S")
                    .enabled(false)
                    .createdAt(testVariant.getCreatedAt())
                    .updatedAt(testVariant.getUpdatedAt())
                    .build();
            var expectedPage = new PageImpl<>(List.of(testVariant, disabledVariant), pageable, 2);

            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findByProductIdOrderByCreatedAtDesc(productId, pageable))
                    .thenReturn(expectedPage);

            Page<ProductVariantResponse> result = productVariantService.listVariants(productId, null, true, pageable);

            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getContent())
                    .extracting(ProductVariantResponse::enabled)
                    .containsExactly(true, false);
            verify(productVariantRepository).findByProductIdOrderByCreatedAtDesc(productId, pageable);
            verify(productVariantRepository, never()).findByProductIdAndEnabledTrueOrderByCreatedAtDesc(any(), any());
        }

        @Test
        @DisplayName("should keep the existing two-arg overload filtering disabled definitions")
        void listVariants_DefaultOverload_KeepsEnabledOnlyBehavior() {
            var pageable = PageRequest.of(0, 12);
            var expectedPage = new PageImpl<>(List.of(testVariant), pageable, 1);

            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findByProductIdAndEnabledTrueOrderByCreatedAtDesc(productId, pageable))
                    .thenReturn(expectedPage);

            productVariantService.listVariants(productId, pageable);

            verify(productVariantRepository).findByProductIdAndEnabledTrueOrderByCreatedAtDesc(productId, pageable);
            verify(productVariantRepository, never()).findByProductIdOrderByCreatedAtDesc(any(), any());
        }

        @Test
        @DisplayName("should ignore includeDisabled on the store-scoped branch and keep filtering enabled")
        void listVariants_StoreScoped_IgnoresIncludeDisabled() {
            var pageable = PageRequest.of(0, 12);
            var expectedPage = new PageImpl<ProductVariantResponse>(List.of(), pageable, 0);

            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.findStoreScopedByProductIdAndStoreId(productId, companyStoreId, pageable))
                    .thenReturn(expectedPage);

            productVariantService.listVariants(productId, companyStoreId, true, pageable);

            verify(currentUserContext)
                    .verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, companyStoreId);
            verify(productVariantRepository).findStoreScopedByProductIdAndStoreId(productId, companyStoreId, pageable);
            verify(productVariantRepository, never()).findByProductIdOrderByCreatedAtDesc(any(), any());
            verify(productVariantRepository, never()).findByProductIdAndEnabledTrueOrderByCreatedAtDesc(any(), any());
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
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));

            ProductVariantResponse result = productVariantService.getVariant(productId, variantId);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(variantId);
            assertThat(result.productId()).isEqualTo(productId);
            assertThat(result.variantName()).isEqualTo("Talla M");
            assertThat(result.barCode()).isEqualTo("7501234567890");
            assertThat(result.sku()).isEqualTo("PROD-001");
            assertThat(result.companyStoreId()).isNull();
            assertThat(result.stock()).isNull();
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product does not exist")
        void getVariant_ProductNotFound_ThrowsException() {
            when(productRepository.findById(productId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productVariantService.getVariant(productId, variantId))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining("Product not found with id");

            verify(productVariantRepository, never()).findById(any());
        }

        @Test
        @DisplayName("should throw ProductVariantNotFoundException when variant not found")
        void getVariant_VariantNotFound_ThrowsException() {
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
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
                    .barCode("7500000000000")
                    .variantName("Talla X")
                    .enabled(true)
                    .build();

            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
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
        @DisplayName("should create the global definition and return the response")
        void createVariant_Success() {
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.existsByBarCode("7501234567890")).thenReturn(false);
            when(productVariantRepository.existsByProductIdAndVariantName(productId, "Talla M"))
                    .thenReturn(false);
            when(productVariantRepository.save(any(ProductVariant.class))).thenReturn(testVariant);

            ProductVariantResponse result = productVariantService.createVariant(productId, testVariantRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(variantId);
            assertThat(result.productId()).isEqualTo(productId);
            assertThat(result.variantName()).isEqualTo("Talla M");
            assertThat(result.barCode()).isEqualTo("7501234567890");
            assertThat(result.sku()).isEqualTo("PROD-001");
            assertThat(result.enabled()).isTrue();
            verify(productRepository).findById(productId);
            verify(productVariantRepository).save(any(ProductVariant.class));
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product does not exist")
        void createVariant_ProductNotFound_ThrowsException() {
            when(productRepository.findById(productId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productVariantService.createVariant(productId, testVariantRequest))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining("Product not found with id");

            verify(productVariantRepository, never()).save(any(ProductVariant.class));
        }

        @Test
        @DisplayName("should throw DuplicateProductVariantException when the barCode exists")
        void createVariant_DuplicateBarCode_ThrowsException() {
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.existsByBarCode("7501234567890")).thenReturn(true);

            assertThatThrownBy(() -> productVariantService.createVariant(productId, testVariantRequest))
                    .isInstanceOf(DuplicateProductVariantException.class)
                    .hasMessageContaining("barCode already exists");

            verify(productVariantRepository, never()).save(any(ProductVariant.class));
        }

        @Test
        @DisplayName("should throw DuplicateProductVariantException when (product, variantName) exists")
        void createVariant_DuplicateVariantName_ThrowsException() {
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.existsByBarCode("7501234567890")).thenReturn(false);
            when(productVariantRepository.existsByProductIdAndVariantName(productId, "Talla M"))
                    .thenReturn(true);

            assertThatThrownBy(() -> productVariantService.createVariant(productId, testVariantRequest))
                    .isInstanceOf(DuplicateProductVariantException.class)
                    .hasMessageContaining("already exists for product");

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
        @DisplayName("should update the definition and return the response")
        void updateVariant_Success() {
            var updateRequest = new ProductVariantRequest("7509876543210", "Talla L", null);

            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));
            when(productVariantRepository.existsByBarCodeAndIdNot("7509876543210", variantId))
                    .thenReturn(false);
            when(productVariantRepository.existsByProductIdAndVariantNameAndIdNot(productId, "Talla L", variantId))
                    .thenReturn(false);
            when(productVariantRepository.save(any(ProductVariant.class))).thenAnswer(inv -> inv.getArgument(0));

            ProductVariantResponse result = productVariantService.updateVariant(productId, variantId, updateRequest);

            assertThat(result).isNotNull();
            assertThat(result.barCode()).isEqualTo("7509876543210");
            assertThat(result.variantName()).isEqualTo("Talla L");
            assertThat(result.sku()).isEqualTo("PROD-001");
            assertThat(result.enabled()).isTrue();
            verify(productVariantRepository).save(any(ProductVariant.class));
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product does not exist")
        void updateVariant_ProductNotFound_ThrowsException() {
            when(productRepository.findById(productId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productVariantService.updateVariant(productId, variantId, testVariantRequest))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining("Product not found with id");

            verify(productVariantRepository, never()).findById(any());
            verify(productVariantRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw ProductVariantNotFoundException when variant not found")
        void updateVariant_VariantNotFound_ThrowsException() {
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
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
                    .barCode("7500000000000")
                    .variantName("Talla X")
                    .enabled(true)
                    .build();

            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(otherVariant));

            assertThatThrownBy(() -> productVariantService.updateVariant(productId, variantId, testVariantRequest))
                    .isInstanceOf(ProductVariantNotFoundException.class)
                    .hasMessageContaining("Product variant not found with id");

            verify(productVariantRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw DuplicateProductVariantException when changing to an existing barCode")
        void updateVariant_DuplicateBarCode_ThrowsException() {
            var updateRequest = new ProductVariantRequest("7509876543210", "Talla M", null);

            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));
            when(productVariantRepository.existsByBarCodeAndIdNot("7509876543210", variantId))
                    .thenReturn(true);

            assertThatThrownBy(() -> productVariantService.updateVariant(productId, variantId, updateRequest))
                    .isInstanceOf(DuplicateProductVariantException.class)
                    .hasMessageContaining("barCode already exists");

            verify(productVariantRepository, never()).save(any());
        }

        @Test
        @DisplayName("should skip the barCode gate when the barCode is unchanged")
        void updateVariant_SameBarCode_DoesNotProbeDuplicate() {
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));
            when(productVariantRepository.existsByProductIdAndVariantNameAndIdNot(productId, "Talla L", variantId))
                    .thenReturn(false);
            when(productVariantRepository.save(any(ProductVariant.class))).thenAnswer(inv -> inv.getArgument(0));

            productVariantService.updateVariant(
                    productId, variantId, new ProductVariantRequest("7501234567890", "Talla L", null));

            verify(productVariantRepository, never()).existsByBarCodeAndIdNot(any(), any());
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
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));
            when(productVariantRepository.save(any(ProductVariant.class))).thenAnswer(inv -> inv.getArgument(0));

            productVariantService.deleteVariant(productId, variantId);

            assertThat(testVariant.getEnabled()).isFalse();
            verify(productVariantRepository).findById(variantId);
            verify(productVariantRepository).save(any(ProductVariant.class));
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product does not exist")
        void deleteVariant_ProductNotFound_ThrowsException() {
            when(productRepository.findById(productId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productVariantService.deleteVariant(productId, variantId))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining("Product not found with id");

            verify(productVariantRepository, never()).findById(any());
            verify(productVariantRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw ProductVariantNotFoundException when variant not found")
        void deleteVariant_VariantNotFound_ThrowsException() {
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productVariantService.deleteVariant(productId, variantId))
                    .isInstanceOf(ProductVariantNotFoundException.class)
                    .hasMessageContaining("Product variant not found with id");

            verify(productVariantRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────
    // enableVariant
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("enableVariant")
    class EnableVariantTests {

        @Test
        @DisplayName("should re-enable a soft-deleted variant by setting enabled to true")
        void enableVariant_Success() {
            testVariant.setEnabled(false);
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));
            when(productVariantRepository.save(any(ProductVariant.class))).thenAnswer(inv -> inv.getArgument(0));

            var response = productVariantService.enableVariant(productId, variantId);

            assertThat(testVariant.getEnabled()).isTrue();
            assertThat(response.enabled()).isTrue();
            assertThat(response.id()).isEqualTo(variantId);
            verify(productVariantRepository).save(testVariant);
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product does not exist")
        void enableVariant_ProductNotFound_ThrowsException() {
            when(productRepository.findById(productId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productVariantService.enableVariant(productId, variantId))
                    .isInstanceOf(ProductNotFoundException.class)
                    .hasMessageContaining("Product not found with id");

            verify(productVariantRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw ProductVariantNotFoundException when variant not found")
        void enableVariant_VariantNotFound_ThrowsException() {
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productVariantService.enableVariant(productId, variantId))
                    .isInstanceOf(ProductVariantNotFoundException.class)
                    .hasMessageContaining("Product variant not found with id");

            verify(productVariantRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────
    // upsertStoreStock
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("upsertStoreStock")
    class UpsertStoreStockTests {

        @Test
        @DisplayName("should create the store row with the request values")
        void upsertStoreStock_Create() {
            var request = new ProductVariantStoreStockRequest(
                    new BigDecimal("199.99"), new BigDecimal("120.00"), new BigDecimal("50.00"));
            var row = ProductVariantStoreStock.builder()
                    .id(UUID.randomUUID())
                    .productVariantId(variantId)
                    .companyStoreId(companyStoreId)
                    .stock(new BigDecimal("50.00"))
                    .listPrice(new BigDecimal("199.99"))
                    .costPrice(new BigDecimal("120.00"))
                    .build();

            when(productVariantRepository.existsById(variantId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreIdForUpdate(
                            variantId, companyStoreId))
                    .thenReturn(Optional.of(row));
            when(productVariantStoreStockRepository.save(any(ProductVariantStoreStock.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(currentUserContext.getUsername()).thenReturn("tester");
            when(inventoryService.applyStockAdjustment(variantId, companyStoreId, new BigDecimal("50.00"), "tester"))
                    .thenReturn(new BigDecimal("50.00"));

            ProductVariantStoreStockResponse result =
                    productVariantService.upsertStoreStock(variantId, companyStoreId, request);

            assertThat(result.companyStoreId()).isEqualTo(companyStoreId);
            assertThat(result.listPrice()).isEqualByComparingTo(new BigDecimal("199.99"));
            assertThat(result.costPrice()).isEqualByComparingTo(new BigDecimal("120.00"));
            assertThat(result.stock()).isEqualByComparingTo(new BigDecimal("50.00"));
            verify(productVariantStoreStockRepository).insertStoreStockIfAbsent(variantId, companyStoreId);
            // The stock half is delegated to the engine, never written here (W3-D14).
            verify(inventoryService).applyStockAdjustment(variantId, companyStoreId, new BigDecimal("50.00"), "tester");
        }

        @Test
        @DisplayName("should update the existing store row in place")
        void upsertStoreStock_Update() {
            var request = new ProductVariantStoreStockRequest(new BigDecimal("249.99"), null, null);
            var row = ProductVariantStoreStock.builder()
                    .id(UUID.randomUUID())
                    .productVariantId(variantId)
                    .companyStoreId(companyStoreId)
                    .stock(new BigDecimal("50.00"))
                    .listPrice(new BigDecimal("199.99"))
                    .costPrice(new BigDecimal("120.00"))
                    .build();

            when(productVariantRepository.existsById(variantId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreIdForUpdate(
                            variantId, companyStoreId))
                    .thenReturn(Optional.of(row));
            when(productVariantStoreStockRepository.save(any(ProductVariantStoreStock.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            ProductVariantStoreStockResponse result =
                    productVariantService.upsertStoreStock(variantId, companyStoreId, request);

            // Only listPrice is provided: costPrice and stock keep their stored values.
            assertThat(result.listPrice()).isEqualByComparingTo(new BigDecimal("249.99"));
            assertThat(result.costPrice()).isEqualByComparingTo(new BigDecimal("120.00"));
            assertThat(result.stock()).isEqualByComparingTo(new BigDecimal("50.00"));
            // No stock was sent, so the engine is never asked to move it.
            verify(inventoryService, never()).applyStockAdjustment(any(), any(), any(), any());
        }

        @Test
        @DisplayName("should refuse a stock edit on a store with no settings row, propagating the engine's 404")
        void upsertStoreStock_StoreWithoutSettings_IsRefusedByTheEngine() {
            var request = new ProductVariantStoreStockRequest(null, null, new BigDecimal("5.00"));
            var row = ProductVariantStoreStock.builder()
                    .id(UUID.randomUUID())
                    .productVariantId(variantId)
                    .companyStoreId(companyStoreId)
                    .stock(BigDecimal.ZERO)
                    .build();

            when(productVariantRepository.existsById(variantId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreIdForUpdate(
                            variantId, companyStoreId))
                    .thenReturn(Optional.of(row));
            when(productVariantStoreStockRepository.save(any(ProductVariantStoreStock.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(currentUserContext.getUsername()).thenReturn("tester");
            when(inventoryService.applyStockAdjustment(variantId, companyStoreId, new BigDecimal("5.00"), "tester"))
                    .thenThrow(new StoreInventorySettingsNotFoundException(companyStoreId));

            assertThatThrownBy(() -> productVariantService.upsertStoreStock(variantId, companyStoreId, request))
                    .isInstanceOf(StoreInventorySettingsNotFoundException.class)
                    .hasMessageContaining("Store inventory settings not found");

            // The [row] was never touched: the refused write must not reach the aggregate either.
            assertThat(row.getStock()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("should throw ProductVariantNotFoundException when the variant does not exist")
        void upsertStoreStock_UnknownVariant_ThrowsException() {
            when(productVariantRepository.existsById(variantId)).thenReturn(false);

            assertThatThrownBy(() -> productVariantService.upsertStoreStock(
                            variantId,
                            companyStoreId,
                            new ProductVariantStoreStockRequest(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)))
                    .isInstanceOf(ProductVariantNotFoundException.class)
                    .hasMessageContaining("Product variant not found with id");

            verify(productVariantStoreStockRepository, never()).insertStoreStockIfAbsent(any(), any());
        }

        @Test
        @DisplayName("should throw CompanyStoreNotFoundException when the store does not exist")
        void upsertStoreStock_UnknownStore_ThrowsException() {
            when(productVariantRepository.existsById(variantId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productVariantService.upsertStoreStock(
                            variantId,
                            companyStoreId,
                            new ProductVariantStoreStockRequest(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)))
                    .isInstanceOf(CompanyStoreNotFoundException.class)
                    .hasMessageContaining("Store not found with id");

            verify(productVariantStoreStockRepository, never()).insertStoreStockIfAbsent(any(), any());
        }

        @Test
        @DisplayName("should reject a store outside the caller's scope and write nothing")
        void upsertStoreStock_StoreOutsideCallerScope_IsRejected() {
            when(productVariantRepository.existsById(variantId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            doThrow(new AccessDeniedException("Access denied"))
                    .when(currentUserContext)
                    .verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, companyStoreId);

            assertThatThrownBy(() -> productVariantService.upsertStoreStock(
                            variantId,
                            companyStoreId,
                            new ProductVariantStoreStockRequest(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)))
                    .isInstanceOf(AccessDeniedException.class);

            // The guard must receive the chain derived from the store, not the raw store id.
            verify(currentUserContext)
                    .verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, companyStoreId);
            verify(productVariantStoreStockRepository, never()).insertStoreStockIfAbsent(any(), any());
            verify(productVariantStoreStockRepository, never()).save(any());
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
            var pageable = PageRequest.of(0, 20);
            var searchResponse = new ProductVariantSearchResponse(
                    variantId,
                    productId,
                    companyStoreId,
                    "7501234567890",
                    "PROD-001",
                    "Talla M",
                    new BigDecimal("199.99"),
                    new BigDecimal("120.00"),
                    new BigDecimal("50.00"),
                    true,
                    "Producto Test",
                    "PROD-001",
                    testVariant.getCreatedAt(),
                    testVariant.getUpdatedAt());
            var expectedPage = new PageImpl<>(List.of(searchResponse), pageable, 1);

            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.searchByQuery(query, companyStoreId, pageable))
                    .thenReturn(expectedPage);

            var result = productVariantService.searchVariants(query, companyStoreId, pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            var item = result.getContent().get(0);
            assertThat(item.id()).isEqualTo(variantId);
            assertThat(item.barCode()).isEqualTo("7501234567890");
            assertThat(item.sku()).isEqualTo("PROD-001");
            assertThat(item.productName()).isEqualTo("Producto Test");
            assertThat(item.productSku()).isEqualTo("PROD-001");
            assertThat(item.variantName()).isEqualTo("Talla M");
            verify(productVariantRepository).searchByQuery(query, companyStoreId, pageable);
        }

        @Test
        @DisplayName("should verify the derived store chain before querying")
        void searchVariants_VerifiesDerivedChain() {
            var query = "7501234567890";
            var pageable = PageRequest.of(0, 20);

            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.searchByQuery(query, companyStoreId, pageable))
                    .thenReturn(Page.empty(pageable));

            productVariantService.searchVariants(query, companyStoreId, pageable);

            // The guard must receive the chain derived from the store, not the raw store id.
            verify(currentUserContext)
                    .verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, companyStoreId);
        }

        @Test
        @DisplayName("should reject a store outside the caller's scope and read nothing")
        void searchVariants_StoreOutsideCallerScope_Throws403() {
            var query = "7501234567890";
            var pageable = PageRequest.of(0, 20);

            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            doThrow(new AccessDeniedException("Access denied"))
                    .when(currentUserContext)
                    .verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, companyStoreId);

            assertThatThrownBy(() -> productVariantService.searchVariants(query, companyStoreId, pageable))
                    .isInstanceOf(AccessDeniedException.class);

            verify(productVariantRepository, never()).searchByQuery(any(), any(), any());
        }

        @Test
        @DisplayName("should throw CompanyStoreNotFoundException when the store does not exist")
        void searchVariants_UnknownStore_ThrowsException() {
            var query = "7501234567890";
            var pageable = PageRequest.of(0, 20);

            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productVariantService.searchVariants(query, companyStoreId, pageable))
                    .isInstanceOf(CompanyStoreNotFoundException.class)
                    .hasMessageContaining("Store not found with id");

            verify(currentUserContext, never()).verifyCompanyStoreAccess(any(), any(), any(), any(), any());
            verify(productVariantRepository, never()).searchByQuery(any(), any(), any());
        }

        @Test
        @DisplayName("should return empty page when query is null")
        void searchVariants_NullQuery_ReturnsEmpty() {
            var storeId = UUID.randomUUID();
            var pageable = PageRequest.of(0, 20);

            var result = productVariantService.searchVariants(null, storeId, pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEmpty();
            // The blank short-circuit must neither read nor guard the store.
            verify(companyStoreRepository, never()).findById(any());
            verify(currentUserContext, never()).verifyCompanyStoreAccess(any(), any(), any(), any(), any());
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
            // The blank short-circuit must neither read nor guard the store.
            verify(companyStoreRepository, never()).findById(any());
            verify(currentUserContext, never()).verifyCompanyStoreAccess(any(), any(), any(), any(), any());
            verify(productVariantRepository, never()).searchByQuery(any(), any(), any());
        }

        @Test
        @DisplayName("should pass pageable through to repository")
        void searchVariants_Pagination() {
            var query = "test";
            var pageable = PageRequest.of(1, 5);
            var searchResponse = new ProductVariantSearchResponse(
                    variantId,
                    productId,
                    companyStoreId,
                    "7501234567890",
                    "PROD-001",
                    "Talla M",
                    new BigDecimal("199.99"),
                    new BigDecimal("120.00"),
                    new BigDecimal("50.00"),
                    true,
                    "Producto Test",
                    "PROD-001",
                    testVariant.getCreatedAt(),
                    testVariant.getUpdatedAt());
            var expectedPage = new PageImpl<>(List.of(searchResponse), pageable, 1);

            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.searchByQuery(query, companyStoreId, pageable))
                    .thenReturn(expectedPage);

            var result = productVariantService.searchVariants(query, companyStoreId, pageable);

            assertThat(result.getNumber()).isEqualTo(1);
            assertThat(result.getSize()).isEqualTo(5);
            verify(productVariantRepository).searchByQuery(query, companyStoreId, pageable);
        }
    }

    // ─────────────────────────────────────────────
    // measure unit (D1 relation + D3/D4 guards)
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("measure unit")
    class MeasureUnitTests {

        @Test
        @DisplayName("should persist the unit on create and return it")
        void createVariant_WithEnabledUnit_PersistsAndReturnsIt() {
            stubCreateGates();
            when(measureUnitRepository.findById(pieceUnitId)).thenReturn(Optional.of(pieceUnit));
            when(productVariantRepository.findUsedMeasureUnitTypesByProductId(productId))
                    .thenReturn(List.of());
            var captor = ArgumentCaptor.forClass(ProductVariant.class);

            var result = productVariantService.createVariant(
                    productId, new ProductVariantRequest("7501234567890", "Talla M", pieceUnitId));

            assertThat(result.measureUnitId()).isEqualTo(pieceUnitId);
            verify(productVariantRepository).save(captor.capture());
            assertThat(captor.getValue().getMeasureUnitId()).isEqualTo(pieceUnitId);
        }

        @Test
        @DisplayName("should keep the variant unitless when the request omits the unit")
        void createVariant_WithoutUnit_KeepsPreviousBehavior() {
            stubCreateGates();

            var result = productVariantService.createVariant(
                    productId, new ProductVariantRequest("7501234567890", "Talla M", null));

            assertThat(result.measureUnitId()).isNull();
            // No unit was named, so neither the catalog nor the D4 query is read (S7).
            verify(measureUnitRepository, never()).findById(any());
            verify(productVariantRepository, never()).findUsedMeasureUnitTypesByProductId(any());
        }

        @Test
        @DisplayName("should reject an unknown unit with 404 and write nothing")
        void createVariant_UnknownUnit_ThrowsNotFound() {
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(measureUnitRepository.findById(pieceUnitId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productVariantService.createVariant(
                            productId, new ProductVariantRequest("7501234567890", "Talla M", pieceUnitId)))
                    .isInstanceOf(MeasureUnitNotFoundException.class)
                    .hasMessageContaining("Measure unit not found with id");

            verify(productVariantRepository, never()).save(any(ProductVariant.class));
        }

        @Test
        @DisplayName("should reject a disabled unit with 400 and write nothing")
        void createVariant_DisabledUnit_Throws400() {
            var disabledUnit = MeasureUnit.builder()
                    .id(pieceUnitId)
                    .measureUnitName("Pieza")
                    .measureUnitShortName("Pza")
                    .unitType(MeasureUnitType.PRODUCT)
                    .satCode("H87")
                    .enabled(false)
                    .build();
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(measureUnitRepository.findById(pieceUnitId)).thenReturn(Optional.of(disabledUnit));

            assertThatThrownBy(() -> productVariantService.createVariant(
                            productId, new ProductVariantRequest("7501234567890", "Talla M", pieceUnitId)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Measure unit is disabled")
                    .hasMessageContaining("Pza");

            verify(productVariantRepository, never()).findUsedMeasureUnitTypesByProductId(any());
            verify(productVariantRepository, never()).save(any(ProductVariant.class));
        }

        @Test
        @DisplayName("should reject a unit_type that contradicts the product's other variants with 400")
        void createVariant_UnitTypeMismatch_Throws400() {
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(measureUnitRepository.findById(pieceUnitId)).thenReturn(Optional.of(pieceUnit));
            when(productVariantRepository.findUsedMeasureUnitTypesByProductId(productId))
                    .thenReturn(List.of(MeasureUnitType.SERVICE));

            assertThatThrownBy(() -> productVariantService.createVariant(
                            productId, new ProductVariantRequest("7501234567890", "Talla M", pieceUnitId)))
                    .isInstanceOf(MeasureUnitTypeMismatchException.class)
                    .hasMessageContaining("already uses measure units of type SERVICE")
                    .hasMessageContaining("cannot use a measure unit of type PRODUCT");

            verify(productVariantRepository, never()).save(any(ProductVariant.class));
        }

        @Test
        @DisplayName("should accept the first declaration even with stock, without probing quantity")
        void updateVariant_FirstDeclarationWithStock_IsAllowed() {
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));
            when(measureUnitRepository.findById(pieceUnitId)).thenReturn(Optional.of(pieceUnit));
            when(productVariantRepository.findUsedMeasureUnitTypesByProductIdExcludingVariantId(productId, variantId))
                    .thenReturn(List.of(MeasureUnitType.PRODUCT));
            when(productVariantRepository.save(any(ProductVariant.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productVariantService.updateVariant(
                    productId, variantId, new ProductVariantRequest("7501234567890", "Talla M", pieceUnitId));

            assertThat(result.measureUnitId()).isEqualTo(pieceUnitId);
            assertThat(testVariant.getMeasureUnitId()).isEqualTo(pieceUnitId);
            // null -> unit reinterprets nothing, so the quantity probes are never consulted.
            verify(productVariantStoreStockRepository, never()).existsByProductVariantIdAndStockNot(any(), any());
            verify(inventoryMovementRepository, never()).existsByProductVariantId(any());
        }

        @Test
        @DisplayName("should reject unit -> unit while a store holds stock, leaving the variant untouched")
        void updateVariant_ChangeWithStock_IsRejected() {
            testVariant.setMeasureUnitId(pieceUnitId);
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));
            when(measureUnitRepository.findById(kilogramUnitId)).thenReturn(Optional.of(kilogramUnit));
            when(productVariantRepository.findUsedMeasureUnitTypesByProductIdExcludingVariantId(productId, variantId))
                    .thenReturn(List.of(MeasureUnitType.PRODUCT));
            when(productVariantStoreStockRepository.existsByProductVariantIdAndStockNot(variantId, BigDecimal.ZERO))
                    .thenReturn(true);

            assertThatThrownBy(() -> productVariantService.updateVariant(
                            productId,
                            variantId,
                            new ProductVariantRequest("7501234567890", "Talla M", kilogramUnitId)))
                    .isInstanceOf(MeasureUnitChangeNotAllowedException.class)
                    .hasMessageContaining("cannot be changed")
                    .hasMessageContaining(variantId.toString());

            assertThat(testVariant.getMeasureUnitId()).isEqualTo(pieceUnitId);
            verify(productVariantRepository, never()).save(any(ProductVariant.class));
        }

        @Test
        @DisplayName("should reject unit -> unit while the ledger holds a movement")
        void updateVariant_ChangeWithMovement_IsRejected() {
            testVariant.setMeasureUnitId(pieceUnitId);
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));
            when(measureUnitRepository.findById(kilogramUnitId)).thenReturn(Optional.of(kilogramUnit));
            when(productVariantRepository.findUsedMeasureUnitTypesByProductIdExcludingVariantId(productId, variantId))
                    .thenReturn(List.of(MeasureUnitType.PRODUCT));
            when(productVariantStoreStockRepository.existsByProductVariantIdAndStockNot(variantId, BigDecimal.ZERO))
                    .thenReturn(false);
            when(inventoryMovementRepository.existsByProductVariantId(variantId))
                    .thenReturn(true);

            assertThatThrownBy(() -> productVariantService.updateVariant(
                            productId,
                            variantId,
                            new ProductVariantRequest("7501234567890", "Talla M", kilogramUnitId)))
                    .isInstanceOf(MeasureUnitChangeNotAllowedException.class)
                    .hasMessageContaining("the inventory ledger already holds movements");

            assertThat(testVariant.getMeasureUnitId()).isEqualTo(pieceUnitId);
            verify(productVariantRepository, never()).save(any(ProductVariant.class));
        }

        @Test
        @DisplayName("should allow unit -> unit when there is no stock and no movement")
        void updateVariant_ChangeWithoutQuantityMeaning_IsAllowed() {
            testVariant.setMeasureUnitId(pieceUnitId);
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));
            when(measureUnitRepository.findById(kilogramUnitId)).thenReturn(Optional.of(kilogramUnit));
            when(productVariantRepository.findUsedMeasureUnitTypesByProductIdExcludingVariantId(productId, variantId))
                    .thenReturn(List.of(MeasureUnitType.PRODUCT));
            when(productVariantStoreStockRepository.existsByProductVariantIdAndStockNot(variantId, BigDecimal.ZERO))
                    .thenReturn(false);
            when(inventoryMovementRepository.existsByProductVariantId(variantId))
                    .thenReturn(false);
            when(productVariantRepository.save(any(ProductVariant.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productVariantService.updateVariant(
                    productId, variantId, new ProductVariantRequest("7501234567890", "Talla M", kilogramUnitId));

            assertThat(result.measureUnitId()).isEqualTo(kilogramUnitId);
            assertThat(testVariant.getMeasureUnitId()).isEqualTo(kilogramUnitId);
        }

        @Test
        @DisplayName("should reject unit -> null while a store holds stock")
        void updateVariant_ClearUnitWithStock_IsRejected() {
            testVariant.setMeasureUnitId(pieceUnitId);
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));
            when(productVariantStoreStockRepository.existsByProductVariantIdAndStockNot(variantId, BigDecimal.ZERO))
                    .thenReturn(true);

            assertThatThrownBy(() -> productVariantService.updateVariant(
                            productId, variantId, new ProductVariantRequest("7501234567890", "Talla M", null)))
                    .isInstanceOf(MeasureUnitChangeNotAllowedException.class)
                    .hasMessageContaining("cannot be changed");

            assertThat(testVariant.getMeasureUnitId()).isEqualTo(pieceUnitId);
            verify(measureUnitRepository, never()).findById(any());
            verify(productVariantRepository, never()).save(any(ProductVariant.class));
        }

        @Test
        @DisplayName("should allow an unchanged unit without probing the quantity meaning")
        void updateVariant_UnchangedUnit_IsAllowedWithoutProbes() {
            testVariant.setMeasureUnitId(pieceUnitId);
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));
            when(measureUnitRepository.findById(pieceUnitId)).thenReturn(Optional.of(pieceUnit));
            when(productVariantRepository.findUsedMeasureUnitTypesByProductIdExcludingVariantId(productId, variantId))
                    .thenReturn(List.of(MeasureUnitType.PRODUCT));
            when(productVariantRepository.save(any(ProductVariant.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = productVariantService.updateVariant(
                    productId, variantId, new ProductVariantRequest("7501234567890", "Talla M", pieceUnitId));

            assertThat(result.measureUnitId()).isEqualTo(pieceUnitId);
            verify(productVariantStoreStockRepository, never()).existsByProductVariantIdAndStockNot(any(), any());
            verify(inventoryMovementRepository, never()).existsByProductVariantId(any());
        }

        @Test
        @DisplayName("should report the 404 of an unknown unit before the 409 of a blocked change")
        void updateVariant_UnknownUnitWithStock_ReportsNotFoundFirst() {
            testVariant.setMeasureUnitId(pieceUnitId);
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));
            when(measureUnitRepository.findById(kilogramUnitId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productVariantService.updateVariant(
                            productId,
                            variantId,
                            new ProductVariantRequest("7501234567890", "Talla M", kilogramUnitId)))
                    .isInstanceOf(MeasureUnitNotFoundException.class);

            // A request that is not even valid never reaches the state conflict.
            verify(productVariantStoreStockRepository, never()).existsByProductVariantIdAndStockNot(any(), any());
            verify(inventoryMovementRepository, never()).existsByProductVariantId(any());
        }

        @Test
        @DisplayName("should exclude the variant being updated from the unit_type gate")
        void updateVariant_TypeGateExcludesTheVariantItself() {
            testVariant.setMeasureUnitId(pieceUnitId);
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));
            when(measureUnitRepository.findById(pieceUnitId)).thenReturn(Optional.of(pieceUnit));
            when(productVariantRepository.findUsedMeasureUnitTypesByProductIdExcludingVariantId(productId, variantId))
                    .thenReturn(List.of());
            when(productVariantRepository.save(any(ProductVariant.class))).thenAnswer(inv -> inv.getArgument(0));

            productVariantService.updateVariant(
                    productId, variantId, new ProductVariantRequest("7501234567890", "Talla M", pieceUnitId));

            verify(productVariantRepository)
                    .findUsedMeasureUnitTypesByProductIdExcludingVariantId(productId, variantId);
            verify(productVariantRepository, never()).findUsedMeasureUnitTypesByProductId(any());
        }

        @Test
        @DisplayName("should carry the unit id in the read projection")
        void getVariant_CarriesMeasureUnitId() {
            testVariant.setMeasureUnitId(pieceUnitId);
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.findById(variantId)).thenReturn(Optional.of(testVariant));

            var result = productVariantService.getVariant(productId, variantId);

            assertThat(result.measureUnitId()).isEqualTo(pieceUnitId);
        }

        private void stubCreateGates() {
            when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
            when(productVariantRepository.existsByBarCode("7501234567890")).thenReturn(false);
            when(productVariantRepository.existsByProductIdAndVariantName(productId, "Talla M"))
                    .thenReturn(false);
            when(productVariantRepository.save(any(ProductVariant.class))).thenAnswer(inv -> inv.getArgument(0));
        }
    }
}
