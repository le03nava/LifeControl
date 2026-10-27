package com.lifecontrol.api.product.attribute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.product.attribute.dto.ProductAttributeDefinitionResponse;
import com.lifecontrol.api.product.attribute.dto.ProductAttributeProfileResponse;
import com.lifecontrol.api.product.attribute.dto.ProductAttributeValueResponse;
import com.lifecontrol.api.product.attribute.exception.ProductAttributeValidationException;
import com.lifecontrol.api.product.attribute.model.ProductAttributeDefinition;
import com.lifecontrol.api.product.attribute.model.ProductAttributeKind;
import com.lifecontrol.api.product.attribute.model.ProductAttributeLevel;
import com.lifecontrol.api.product.attribute.model.ProductAttributeProfile;
import com.lifecontrol.api.product.attribute.model.ProductAttributeProfileRoot;
import com.lifecontrol.api.product.attribute.model.ProductAttributeValue;
import com.lifecontrol.api.product.attribute.repository.ProductAttributeDefinitionRepository;
import com.lifecontrol.api.product.attribute.repository.ProductAttributeProfileRepository;
import com.lifecontrol.api.product.attribute.repository.ProductAttributeProfileRootRepository;
import com.lifecontrol.api.product.attribute.repository.ProductAttributeValueRepository;
import com.lifecontrol.api.product.category.model.ProductCategory;
import com.lifecontrol.api.product.category.repository.ProductCategoryRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration verification of the two rules {@link ProductAttributeCatalogService} states, against real
 * PostgreSQL with the seeded catalog Flyway wrote.
 *
 * <p>Neither rule can be verified without the database. The resolution walks
 * {@code product_categories.parent_category} up to the root and then matches the root's <b>name</b> against
 * {@code product_attribute_profile_roots}, so a mocked repository would assert the shape of the walk
 * instead of the answer it produces; and the precedence — a company's own attachment beating the seeded
 * global one — is decided by two queries whose order is the rule, not by a branch the test can reach.
 * Validation reads the profile that resolution produced, so it is exercised through the same path the write
 * endpoints use, with the seeded vocabulary as the fixture.</p>
 *
 * <p>The company, the category tree and the probe profiles are created through the repositories and
 * removed leaf-first afterwards: the container is shared per JVM, and a leftover category would collide with
 * the sibling-name index while a leftover profile would silence the next method's attachment. Rows the
 * migrations seed are never modified — they are the fixture under test.</p>
 */
@SpringBootTest
@DisplayName("Product Attribute Catalog Service Tests")
class ProductAttributeCatalogServiceTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "PRODUCT-ATTRIBUTE-CATALOG-KEY";
    private static final String COMPANY_RFC = "PASB010101ABC";

    private static final String PROBE_PROFILE_KEY = "catalogo_prueba";
    private static final String DISABLED_PROFILE_KEY = "catalogo_apagado";
    private static final String OVERRIDE_PROFILE_KEY = "catalogo_propio";

    /** A root the seeded catalog deliberately leaves unattached (V38's header), so "no profile" is real. */
    private static final String UNATTACHED_ROOT_NAME = "Accesorios y Cuidado";

    /** The seeded root whose name is attached to the {@code calzado} profile. */
    private static final String CALZADO_ROOT_NAME = "Calzado para Dama";

    private static final String PROBE_ROOT_NAME = "Pruebas de Atributos";
    private static final String DISABLED_ROOT_NAME = "Pruebas Apagadas";

    @Autowired
    private ProductAttributeCatalogService catalogService;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private ProductCategoryRepository categoryRepository;

    @Autowired
    private ProductAttributeProfileRepository profileRepository;

    @Autowired
    private ProductAttributeDefinitionRepository definitionRepository;

    @Autowired
    private ProductAttributeValueRepository valueRepository;

    @Autowired
    private ProductAttributeProfileRootRepository profileRootRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Company company;
    private UUID companyId;
    private UUID calzadoDeepChildId;
    private UUID ferreteriaChildId;
    private UUID electroChildId;
    private UUID unattachedRootId;
    private UUID probeRootId;

    @BeforeEach
    void setUp() {
        company = companyRepository
                .findByCompanyKey(COMPANY_KEY)
                .orElseGet(() -> companyRepository.saveAndFlush(Company.builder()
                        .companyKey(COMPANY_KEY)
                        .companyName("Product Attribute Catalog Test Company")
                        .rfc(COMPANY_RFC)
                        .enabled(true)
                        .build()));
        companyId = company.getId();

        // A previous method of this class may have failed before its cleanup; the shared container is
        // what makes that state visible to this one.
        removeCompanyRows();

        var calzadoRoot = category(null, CALZADO_ROOT_NAME, 1);
        var botas = category(calzadoRoot, "Botas y Botines", 1);
        calzadoDeepChildId = category(botas, "Botas de Vestir", 1).getId();

        ferreteriaChildId = category(category(null, "Ferretería", 2), "Herramientas Manuales", 1)
                .getId();
        electroChildId = category(category(null, "Electrodomésticos", 3), "Refrigeración", 1)
                .getId();
        unattachedRootId = category(null, UNATTACHED_ROOT_NAME, 4).getId();
        probeRootId = category(null, PROBE_ROOT_NAME, 5).getId();
        var disabledRoot = category(null, DISABLED_ROOT_NAME, 6);

        createProbeCatalog();
        createDisabledProfile(disabledRoot);
    }

    @AfterEach
    void tearDown() {
        removeCompanyRows();
    }

    // ──────────────────────────────────────
    // resolution
    // ──────────────────────────────────────

    @Nested
    @DisplayName("resolution")
    class ResolutionTests {

        @Test
        @DisplayName("a product classified on a deep child resolves the profile of its root")
        void deepChildResolvesTheProfileOfItsRoot() {
            // The classification is three levels down and the attachment is by root NAME, so this is the
            // walk that makes every Calzado sub-branch answer the same profile.
            var profile = resolve(calzadoDeepChildId);

            assertThat(profile.profileKey()).isEqualTo("calzado");
            assertThat(profile.resolvedRootName())
                    .as("the root is what the attachment matched, not the classified node")
                    .isEqualTo(CALZADO_ROOT_NAME);
            assertThat(profile.label()).isEqualTo("Calzado");
        }

        @Test
        @DisplayName("a company's own attachment wins over the seeded global one")
        void companyAttachmentWinsOverTheGlobalOne() {
            var ownProfile = profile(OVERRIDE_PROFILE_KEY, true);
            definition(ownProfile, "sku_interno", "SKU Interno", ProductAttributeLevel.PRODUCT, false, 10);
            attach(ownProfile, CALZADO_ROOT_NAME);

            var profile = resolve(calzadoDeepChildId);

            assertThat(profile.profileKey())
                    .as("the global calzado attachment is still there and loses")
                    .isEqualTo(OVERRIDE_PROFILE_KEY);
            assertThat(profile.resolvedRootName()).isEqualTo(CALZADO_ROOT_NAME);
            assertThat(profile.productAttributes())
                    .extracting(ProductAttributeDefinitionResponse::code)
                    .containsExactly("sku_interno");
        }

        @Test
        @DisplayName("a root with no attachment answers empty instead of inventing a profile")
        void rootWithoutAttachmentAnswersEmpty() {
            assertThat(catalogService.findProfile(companyId, unattachedRootId))
                    .as("V38 leaves Accesorios y Cuidado unattached on purpose")
                    .isEmpty();
        }

        @Test
        @DisplayName("a disabled profile answers empty even though its definitions are enabled")
        void disabledProfileAnswersEmpty() {
            // The probe profile attached to this root is disabled while its single definition is not:
            // the answer has to come from the profile's own flag, not from an empty definition set.
            assertThat(catalogService.findProfile(companyId, probeRootId))
                    .as("the enabled probe profile is on a different root")
                    .isPresent();

            var disabledRootId = categoryId(DISABLED_ROOT_NAME);

            assertThat(catalogService.findProfile(companyId, disabledRootId)).isEmpty();
        }

        @Test
        @DisplayName("the profile is partitioned by level: the variant set is color and acabado only")
        void profileIsPartitionedByLevel() {
            var profile = resolve(calzadoDeepChildId);

            assertThat(profile.productAttributes())
                    .as("a shoe's material and fit belong to the product")
                    .hasSizeGreaterThanOrEqualTo(4)
                    .allSatisfy(definition -> assertThat(definition.level()).isEqualTo(ProductAttributeLevel.PRODUCT));
            assertThat(profile.variantAttributes())
                    .as("two colours are two barcodes, so colour is a variant fact")
                    .extracting(ProductAttributeDefinitionResponse::code)
                    .containsExactly("color", "acabado");

            var material = attribute(profile, "material_externo");
            assertThat(material.kind()).isEqualTo(ProductAttributeKind.ENUM);
            assertThat(material.required()).as("calzado asks for the material").isTrue();
            assertThat(material.values())
                    .extracting(ProductAttributeValueResponse::code)
                    .contains("piel_vacuno", "sintetico", "gamuza", "lona");

            var heel = attribute(profile, "altura_tacon");
            assertThat(heel.kind()).isEqualTo(ProductAttributeKind.MEASUREMENT);
            assertThat(heel.unitLabel())
                    .as("the hint a measurement input prints")
                    .isEqualTo("cm");
            assertThat(heel.values())
                    .as("a measurement has no allowed set to render")
                    .isEmpty();
        }

        @Test
        @DisplayName("values come back in display_order and only the enabled ones")
        void valuesComeBackOrderedAndOnlyEnabled() {
            var seeded = attribute(resolve(calzadoDeepChildId), "color");
            assertThat(seeded.values())
                    .extracting(ProductAttributeValueResponse::code)
                    .as("the seed's own display_order governs the picker")
                    .containsExactly(
                            "negro", "cafe", "rojo", "blanco", "azul", "beige", "vino", "gris", "rosa", "multicolor");

            // The probe catalog was inserted out of order and with one retired value and one retired
            // definition, so the three rules cannot pass by coincidence: `tono_prueba` was written
            // "claro" (30) before "oscuro" (10), `color_prueba` has a disabled "verde", and
            // `oculto_prueba` is disabled altogether.
            var probe = resolve(probeRootId);

            assertThat(probe.productAttributes())
                    .extracting(ProductAttributeDefinitionResponse::code)
                    .as("a retired definition is not offered")
                    .containsExactlyInAnyOrder("flag_prueba", "color_prueba", "tono_prueba");
            assertThat(attribute(probe, "color_prueba").values())
                    .extracting(ProductAttributeValueResponse::code)
                    .containsExactly("rojo", "azul");
            assertThat(attribute(probe, "tono_prueba").values())
                    .extracting(ProductAttributeValueResponse::code)
                    .as("display_order decides, not insertion order")
                    .containsExactly("oscuro", "claro");
        }
    }

    // ──────────────────────────────────────
    // validation — one rule per test
    // ──────────────────────────────────────

    @Nested
    @DisplayName("validation")
    class ValidationTests {

        @Test
        @DisplayName("refuses a code the profile does not declare, naming it")
        void unknownCodeIsRefused() {
            assertRefused(calzadoDeepChildId, Map.of("desconocido", "x"), ProductAttributeLevel.PRODUCT, "desconocido");
        }

        @Test
        @DisplayName("refuses a VARIANT code sent at PRODUCT level: color is not a product fact")
        void variantCodeAtProductLevelIsRefused() {
            assertRefused(calzadoDeepChildId, Map.of("color", "negro"), ProductAttributeLevel.PRODUCT, "color");

            assertThatCode(() -> catalogService.validate(
                            companyId, calzadoDeepChildId, Map.of("color", "negro"), ProductAttributeLevel.VARIANT))
                    .as("the same code is accepted at the level it belongs to")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a set that omits a required attribute, naming the missing code")
        void missingRequiredAttributeIsRefused() {
            assertRefused(calzadoDeepChildId, Map.of(), ProductAttributeLevel.PRODUCT, "material_externo");

            assertThatCode(() -> catalogService.validate(
                            companyId,
                            calzadoDeepChildId,
                            attributes("material_externo", "piel_vacuno", "tipo_ajuste", "agujeta", "genero", "dama"),
                            ProductAttributeLevel.PRODUCT))
                    .as("every required attribute present is a complete set")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses an ENUM value outside the definition's allowed set")
        void enumValueOutsideTheAllowedSetIsRefused() {
            assertRefused(
                    calzadoDeepChildId,
                    Map.of("material_externo", "kevlar"),
                    ProductAttributeLevel.PRODUCT,
                    "material_externo");

            assertThatCode(() -> catalogService.validate(
                            companyId,
                            calzadoDeepChildId,
                            attributes("material_externo", "gamuza", "tipo_ajuste", "velcro", "genero", "unisex"),
                            ProductAttributeLevel.PRODUCT))
                    .as("an allowed value passes the same check")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses an empty MULTI_ENUM list: the kind means at least one")
        void emptyMultiEnumIsRefused() {
            assertRefused(
                    electroChildId, Map.of("conectividad", List.of()), ProductAttributeLevel.PRODUCT, "conectividad");
        }

        @Test
        @DisplayName("refuses a MULTI_ENUM list with a value outside the allowed set")
        void multiEnumValueOutsideTheAllowedSetIsRefused() {
            assertRefused(
                    electroChildId,
                    Map.of("conectividad", List.of("wifi", "lora")),
                    ProductAttributeLevel.PRODUCT,
                    "conectividad");

            assertThatCode(() -> catalogService.validate(
                            companyId,
                            electroChildId,
                            attributes("voltaje", "110v", "conectividad", List.of("wifi", "bluetooth")),
                            ProductAttributeLevel.PRODUCT))
                    .as("Wi-Fi AND Bluetooth is the case the kind exists for")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a MULTI_ENUM list that repeats a value")
        void duplicatedMultiEnumElementIsRefused() {
            assertRefused(
                    electroChildId,
                    Map.of("conectividad", List.of("wifi", "wifi")),
                    ProductAttributeLevel.PRODUCT,
                    "conectividad");
        }

        @Test
        @DisplayName("refuses a MEASUREMENT without its unit")
        void measurementWithoutUnitIsRefused() {
            assertRefused(
                    ferreteriaChildId,
                    Map.of("medida_nominal", Map.of("value", 10)),
                    ProductAttributeLevel.PRODUCT,
                    "medida_nominal");
        }

        @Test
        @DisplayName("refuses a MEASUREMENT with a negative value")
        void negativeMeasurementIsRefused() {
            assertRefused(
                    ferreteriaChildId,
                    Map.of("medida_nominal", Map.of("value", -1, "unit", "mm")),
                    ProductAttributeLevel.PRODUCT,
                    "medida_nominal");
        }

        @Test
        @DisplayName("refuses a MEASUREMENT carrying a key other than value and unit")
        void measurementWithAnExtraKeyIsRefused() {
            // `{value, unit}` is the whole shape: a third key would be stored and read by nobody, which is
            // the free-form jsonb this catalog replaces.
            assertRefused(
                    ferreteriaChildId,
                    Map.of("medida_nominal", Map.of("value", 10, "unit", "mm", "precision", 2)),
                    ProductAttributeLevel.PRODUCT,
                    "medida_nominal");
        }

        @Test
        @DisplayName("accepts a MEASUREMENT whose unit differs from the definition's hint (S3: 1/2 pulgada)")
        void measurementWithADifferentUnitIsAccepted() {
            // The hint is what the form prints, not a constraint: the same branch sells "1/2 pulgada" and
            // "10 milímetros", and a definition that forced its unit would make the user's own example
            // unrepresentable.
            assertThat(attribute(resolve(ferreteriaChildId), "medida_nominal").unitLabel())
                    .isEqualTo("mm");

            assertThatCode(() -> catalogService.validate(
                            companyId,
                            ferreteriaChildId,
                            attributes("material", "pvc", "medida_nominal", Map.of("value", 0.5, "unit", "pulgada")),
                            ProductAttributeLevel.PRODUCT))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a blank TEXT value")
        void blankTextIsRefused() {
            assertRefused(ferreteriaChildId, Map.of("marca", "   "), ProductAttributeLevel.PRODUCT, "marca");

            assertThatCode(() -> catalogService.validate(
                            companyId,
                            ferreteriaChildId,
                            attributes("material", "cobre", "marca", "Truper"),
                            ProductAttributeLevel.PRODUCT))
                    .as("a free string is what TEXT is for")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a BOOLEAN that is not a boolean")
        void nonBooleanIsRefused() {
            // No seeded profile declares a BOOLEAN, so the probe catalog carries one: the per-kind rules
            // are the service's, and this is the only kind the starter vocabulary does not exercise.
            assertRefused(probeRootId, Map.of("flag_prueba", "si"), ProductAttributeLevel.PRODUCT, "flag_prueba");

            assertThatCode(() -> catalogService.validate(
                            companyId, probeRootId, Map.of("flag_prueba", true), ProductAttributeLevel.PRODUCT))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null attributes are validated as an empty set, so a required attribute cannot be skipped")
        void nullAttributesAreValidatedAsEmpty() {
            // `validate` checks the state a write leaves behind, so a caller that means "leave the stored
            // set alone" resolves that itself and passes the stored set. Reading `null` as a no-op here is
            // what let the variant update path keep a missing required colour after its product was
            // classified (finding 1 of this unit's review), so `null` means an empty set and the required
            // check applies to it.
            assertRefused(calzadoDeepChildId, null, ProductAttributeLevel.PRODUCT, "material_externo");
            assertRefused(calzadoDeepChildId, null, ProductAttributeLevel.VARIANT, "color");
        }

        @Test
        @DisplayName("an unclassified product passes an empty set and is refused any key")
        void unclassifiedProductCases() {
            // Without a category there is no profile, so there are no codes: an empty set is a legal
            // write, and any key is unknown — the same refusal an unknown code gets on a classified one.
            assertThatCode(() -> catalogService.validate(companyId, null, Map.of(), ProductAttributeLevel.PRODUCT))
                    .doesNotThrowAnyException();

            assertRefused(
                    null, Map.of("material_externo", "piel_vacuno"), ProductAttributeLevel.PRODUCT, "material_externo");
        }
    }

    // ──────────────────────────────────────
    // fixtures
    // ──────────────────────────────────────

    private ProductAttributeProfileResponse resolve(UUID categoryId) {
        return catalogService
                .findProfile(companyId, categoryId)
                .orElseThrow(() -> new AssertionError("expected a profile for category " + categoryId));
    }

    /** The definition a resolved profile declares under this code, at whichever level it lives. */
    private ProductAttributeDefinitionResponse attribute(ProductAttributeProfileResponse profile, String code) {
        return Stream.concat(profile.productAttributes().stream(), profile.variantAttributes().stream())
                .filter(definition -> definition.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected the profile to declare '" + code + "', got "
                        + Stream.concat(profile.productAttributes().stream(), profile.variantAttributes().stream())
                                .map(ProductAttributeDefinitionResponse::code)
                                .toList()));
    }

    /** A map whose iteration order is the insertion order, so a required attribute cannot shadow a fault. */
    private Map<String, Object> attributes(Object... pairs) {
        var attributes = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) {
            attributes.put((String) pairs[index], pairs[index + 1]);
        }
        return attributes;
    }

    /**
     * Asserts the refusal is a {@link ProductAttributeValidationException} and that its message names the
     * offending code: the operator's next action depends on which attribute the API refused, so a message
     * that names none is a worse answer than the 400 it explains.
     */
    private void assertRefused(
            UUID categoryId, Map<String, Object> attributes, ProductAttributeLevel level, String offendingCode) {
        assertThatThrownBy(() -> catalogService.validate(companyId, categoryId, attributes, level))
                .as("'%s' must be refused at %s level", offendingCode, level)
                .isInstanceOf(ProductAttributeValidationException.class)
                .hasMessageContaining("'" + offendingCode + "'");
    }

    private UUID categoryId(String rootName) {
        return jdbcTemplate.queryForObject("""
                SELECT id FROM product_categories
                WHERE company_id = ? AND parent_category_id IS NULL AND category_name = ?
                """, UUID.class, companyId, rootName);
    }

    private ProductCategory category(ProductCategory parent, String name, int displayOrder) {
        return categoryRepository.saveAndFlush(ProductCategory.builder()
                .company(company)
                .parentCategory(parent)
                .categoryName(name)
                .displayOrder(displayOrder)
                .enabled(true)
                .build());
    }

    private ProductAttributeProfile profile(String profileKey, boolean enabled) {
        var profile = new ProductAttributeProfile();
        profile.setCompany(company);
        profile.setProfileKey(profileKey);
        profile.setLabel("Catálogo " + profileKey);
        profile.setEnabled(enabled);
        return profileRepository.saveAndFlush(profile);
    }

    private ProductAttributeDefinition definition(
            ProductAttributeProfile profile,
            String code,
            String label,
            ProductAttributeLevel level,
            boolean required,
            int displayOrder) {
        var definition = new ProductAttributeDefinition();
        definition.setProfile(profile);
        definition.setAttributeCode(code);
        definition.setLabel(label);
        definition.setAttributeLevel(level);
        definition.setKind(ProductAttributeKind.TEXT);
        definition.setRequired(required);
        definition.setDisplayOrder(displayOrder);
        definition.setEnabled(true);
        return definitionRepository.saveAndFlush(definition);
    }

    private ProductAttributeDefinition definition(
            ProductAttributeProfile profile,
            String code,
            String label,
            ProductAttributeLevel level,
            ProductAttributeKind kind,
            int displayOrder,
            boolean enabled) {
        var definition = new ProductAttributeDefinition();
        definition.setProfile(profile);
        definition.setAttributeCode(code);
        definition.setLabel(label);
        definition.setAttributeLevel(level);
        definition.setKind(kind);
        definition.setRequired(false);
        definition.setDisplayOrder(displayOrder);
        definition.setEnabled(enabled);
        return definitionRepository.saveAndFlush(definition);
    }

    private void value(
            ProductAttributeDefinition definition, String code, String label, int displayOrder, boolean enabled) {
        var value = new ProductAttributeValue();
        value.setDefinition(definition);
        value.setValueCode(code);
        value.setLabel(label);
        value.setDisplayOrder(displayOrder);
        value.setEnabled(enabled);
        valueRepository.saveAndFlush(value);
    }

    /** Attaches the profile to a root of this company; the profile's own owner decides the scope. */
    private void attach(ProductAttributeProfile profile, String rootName) {
        var attachment = new ProductAttributeProfileRoot();
        attachment.setProfile(profile);
        attachment.setCompany(profile.getCompany());
        attachment.setRootName(rootName);
        profileRootRepository.saveAndFlush(attachment);
    }

    /**
     * The probe catalog: the kinds and states the seeded vocabulary does not exercise — a BOOLEAN, a
     * retired definition, a retired value and a display order that differs from the insertion order.
     */
    private void createProbeCatalog() {
        var probeProfile = profile(PROBE_PROFILE_KEY, true);
        definition(
                probeProfile,
                "flag_prueba",
                "Bandera de Prueba",
                ProductAttributeLevel.PRODUCT,
                ProductAttributeKind.BOOLEAN,
                10,
                true);
        var color = definition(
                probeProfile,
                "color_prueba",
                "Color de Prueba",
                ProductAttributeLevel.PRODUCT,
                ProductAttributeKind.ENUM,
                20,
                true);
        var tone = definition(
                probeProfile,
                "tono_prueba",
                "Tono de Prueba",
                ProductAttributeLevel.PRODUCT,
                ProductAttributeKind.ENUM,
                30,
                true);
        var retired = definition(
                probeProfile,
                "oculto_prueba",
                "Oculto de Prueba",
                ProductAttributeLevel.PRODUCT,
                ProductAttributeKind.ENUM,
                40,
                false);

        value(color, "rojo", "Rojo", 10, true);
        value(color, "azul", "Azul", 20, true);
        value(color, "verde", "Verde", 30, false);
        // Written "claro" before "oscuro" on purpose: display_order, not insertion order, is the contract.
        value(tone, "claro", "Claro", 30, true);
        value(tone, "oscuro", "Oscuro", 10, true);
        value(retired, "visible", "Visible", 10, true);

        attach(probeProfile, PROBE_ROOT_NAME);
    }

    /** A disabled profile whose definition is enabled, so the profile's own flag is what answers empty. */
    private void createDisabledProfile(ProductCategory disabledRoot) {
        var disabledProfile = profile(DISABLED_PROFILE_KEY, false);
        definition(disabledProfile, "nota_apagada", "Nota Apagada", ProductAttributeLevel.PRODUCT, false, 10);
        attach(disabledProfile, disabledRoot.getCategoryName());
    }

    /**
     * Removes every row this suite created, leaf-first: values reference definitions, definitions and
     * attachments reference profiles, and a category cannot be deleted while a child references it. The
     * seeded catalog carries {@code company_id IS NULL} and is therefore out of reach of every statement.
     */
    private void removeCompanyRows() {
        var ownProfiles = "SELECT id FROM product_attribute_profiles WHERE company_id = ?";
        jdbcTemplate.update(
                "DELETE FROM product_attribute_values WHERE definition_id IN "
                        + "(SELECT d.id FROM product_attribute_definitions d WHERE d.profile_id IN ("
                        + ownProfiles + "))",
                companyId);
        jdbcTemplate.update(
                "DELETE FROM product_attribute_definitions WHERE profile_id IN (" + ownProfiles + ")", companyId);
        jdbcTemplate.update(
                "DELETE FROM product_attribute_profile_roots WHERE company_id = ? OR profile_id IN (" + ownProfiles
                        + ")",
                companyId,
                companyId);
        jdbcTemplate.update("DELETE FROM product_attribute_profiles WHERE company_id = ?", companyId);
        jdbcTemplate.update(
                "DELETE FROM product_categories WHERE company_id = ? AND parent_category_id IS NOT NULL", companyId);
        jdbcTemplate.update("UPDATE product_categories SET parent_category_id = NULL WHERE company_id = ?", companyId);
        jdbcTemplate.update("DELETE FROM product_categories WHERE company_id = ?", companyId);
    }
}
