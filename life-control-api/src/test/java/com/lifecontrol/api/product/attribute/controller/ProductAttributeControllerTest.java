package com.lifecontrol.api.product.attribute.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.product.category.model.ProductCategory;
import com.lifecontrol.api.product.category.repository.ProductCategoryRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP contract of {@code GET /api/companies/{companyId}/product-categories/{categoryId}/attribute-profile}
 * against real PostgreSQL with the seeded catalog, so the wire shape is asserted on the profile the
 * database actually resolves instead of on a mock's invention.
 *
 * <p>Three answers, and each one is a rule rather than a status code. {@code 200} carries the profile
 * <b>partitioned by level</b>: {@code productAttributes} feeds the product form and
 * {@code variantAttributes} the variant dialog, because rendering a variant-level colour on the product
 * would suggest a product can have one. {@code 204} is a legitimate answer — the branch declares no
 * profile — and the form renders no inputs for it. {@code 404} is the answer for a category of another
 * company, not {@code 403}: the company scope is part of the path, so a foreign id must not be confirmed
 * to exist.</p>
 *
 * <p>Every request carries a real {@code jwt()}, which is what the endpoint's
 * {@code @PreAuthorize("isAuthenticated()")} reads, and the caller is an {@code lc-admin} because that is
 * the role that reaches the category tree this profile belongs to.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("ProductAttributeController Integration Tests")
class ProductAttributeControllerTest extends AbstractPostgresIntegrationTest {

    private static final String PROFILE_URL =
            "/api/companies/{companyId}/product-categories/{categoryId}/attribute-profile";

    private static final String COMPANY_KEY = "PRODUCT-ATTRIBUTE-HTTP-KEY";
    private static final String COMPANY_RFC = "PASC010101ABC";
    private static final String OTHER_COMPANY_KEY = "PRODUCT-ATTRIBUTE-HTTP-OTHER-KEY";
    private static final String OTHER_COMPANY_RFC = "PASD010101ABC";

    private static final String CALZADO_ROOT_NAME = "Calzado para Dama";

    /** A root the seeded catalog deliberately leaves unattached (V38's header): "no profile" is real. */
    private static final String UNATTACHED_ROOT_NAME = "Accesorios y Cuidado";

    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private ProductCategoryRepository categoryRepository;

    private UUID companyId;
    private UUID calzadoDeepChildId;
    private UUID unattachedRootId;
    private UUID otherCompanyCategoryId;

    @BeforeEach
    void setUp() {
        companyId = companyId(COMPANY_KEY, "Product Attribute HTTP Test Company", COMPANY_RFC);
        removeCompanyRows(companyId);

        var calzadoRoot = category(companyId, null, CALZADO_ROOT_NAME, 1);
        var botas = category(companyId, calzadoRoot, "Botas y Botines", 1);
        calzadoDeepChildId = category(companyId, botas, "Botas de Vestir", 1).getId();
        unattachedRootId = category(companyId, null, UNATTACHED_ROOT_NAME, 2).getId();

        var otherCompanyId = companyId(OTHER_COMPANY_KEY, "Product Attribute HTTP Other Company", OTHER_COMPANY_RFC);
        removeCompanyRows(otherCompanyId);
        otherCompanyCategoryId =
                category(otherCompanyId, null, CALZADO_ROOT_NAME, 1).getId();
    }

    @AfterEach
    void tearDown() {
        removeCompanyRows(companyId);
        companyRepository.findByCompanyKey(OTHER_COMPANY_KEY).ifPresent(other -> removeCompanyRows(other.getId()));
    }

    @Test
    @DisplayName("returns 200 with the profile resolved from the deep child's root, partitioned by level")
    void returnsTheProfilePartitionedByLevel() throws Exception {
        var result = mockMvc.perform(
                        get(PROFILE_URL, companyId, calzadoDeepChildId).with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileKey").value("calzado"))
                .andExpect(jsonPath("$.label").value("Calzado"))
                .andExpect(jsonPath("$.resolvedRootName").value(CALZADO_ROOT_NAME))
                .andExpect(jsonPath("$.productAttributes.length()").value(5))
                .andExpect(jsonPath("$.productAttributes[0].code").value("material_externo"))
                .andExpect(jsonPath("$.productAttributes[0].level").value("PRODUCT"))
                .andExpect(jsonPath("$.productAttributes[0].kind").value("ENUM"))
                .andExpect(jsonPath("$.productAttributes[0].required").value(true))
                .andExpect(jsonPath("$.productAttributes[0].unitLabel").doesNotExist())
                .andExpect(jsonPath("$.productAttributes[0].values[0].code").value("piel_vacuno"))
                .andExpect(jsonPath("$.productAttributes[0].values[0].label").value("Piel Vacuno"))
                .andExpect(jsonPath("$.productAttributes[4].code").value("altura_tacon"))
                .andExpect(jsonPath("$.productAttributes[4].kind").value("MEASUREMENT"))
                .andExpect(jsonPath("$.productAttributes[4].unitLabel").value("cm"))
                .andExpect(jsonPath("$.productAttributes[4].values.length()").value(0))
                .andExpect(jsonPath("$.variantAttributes.length()").value(2))
                .andExpect(jsonPath("$.variantAttributes[0].code").value("color"))
                .andExpect(jsonPath("$.variantAttributes[0].level").value("VARIANT"))
                .andExpect(jsonPath("$.variantAttributes[1].code").value("acabado"))
                .andReturn();

        var body = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(codesOf(body.get("productAttributes")))
                .as("the product form renders the product-level attributes in display_order")
                .containsExactly("material_externo", "tipo_ajuste", "genero", "tipo_suela", "altura_tacon");
        assertThat(codesOf(body.get("variantAttributes")))
                .as("two colours are two barcodes: colour and finish belong to the variant only")
                .containsExactly("color", "acabado");
    }

    @Test
    @DisplayName("answers 204 with no body when the branch declares no profile")
    void answersNoContentForAnUnattachedRoot() throws Exception {
        var result = mockMvc.perform(
                        get(PROFILE_URL, companyId, unattachedRootId).with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .as("no content, not an empty profile: the form has nothing to render")
                .isEmpty();
    }

    @Test
    @DisplayName("answers 404 for a category of another company")
    void answersNotFoundForACategoryOfAnotherCompany() throws Exception {
        // The same root name on purpose: the id belongs to another tenant, and the company-scoped lookup
        // is what refuses it, not the profile's absence.
        mockMvc.perform(get(PROFILE_URL, companyId, otherCompanyCategoryId).with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isNotFound());
    }

    private List<String> codesOf(JsonNode definitions) {
        var codes = new ArrayList<String>();
        definitions.forEach(definition -> codes.add(definition.get("code").asText()));
        return codes;
    }

    private UUID companyId(String companyKey, String companyName, String rfc) {
        return TenantFixtures.findOrCreateAndFlush(companyRepository, companyKey, companyName, rfc)
                .getId();
    }

    private ProductCategory category(UUID ownerId, ProductCategory parent, String name, int displayOrder) {
        return categoryRepository.saveAndFlush(ProductCategory.builder()
                .company(companyRepository.findById(ownerId).orElseThrow())
                .parentCategory(parent)
                .categoryName(name)
                .displayOrder(displayOrder)
                .enabled(true)
                .build());
    }

    /**
     * Removes the category rows this suite created, leaf-first: a parent cannot be deleted while a child
     * references it. Only rows of the given company are touched, and the seeded catalog — which carries
     * no company — is out of reach.
     */
    private void removeCompanyRows(UUID ownerId) {
        jdbcTemplate.update(
                "DELETE FROM product_categories WHERE company_id = ? AND parent_category_id IS NOT NULL", ownerId);
        jdbcTemplate.update("UPDATE product_categories SET parent_category_id = NULL WHERE company_id = ?", ownerId);
        jdbcTemplate.update("DELETE FROM product_categories WHERE company_id = ?", ownerId);
    }
}
