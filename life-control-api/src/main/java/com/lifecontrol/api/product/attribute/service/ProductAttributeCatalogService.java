package com.lifecontrol.api.product.attribute.service;

import com.lifecontrol.api.product.attribute.dto.ProductAttributeDefinitionResponse;
import com.lifecontrol.api.product.attribute.dto.ProductAttributeProfileResponse;
import com.lifecontrol.api.product.attribute.dto.ProductAttributeValueResponse;
import com.lifecontrol.api.product.attribute.exception.ProductAttributeValidationException;
import com.lifecontrol.api.product.attribute.model.ProductAttributeDefinition;
import com.lifecontrol.api.product.attribute.model.ProductAttributeKind;
import com.lifecontrol.api.product.attribute.model.ProductAttributeLevel;
import com.lifecontrol.api.product.attribute.model.ProductAttributeProfile;
import com.lifecontrol.api.product.attribute.model.ProductAttributeValue;
import com.lifecontrol.api.product.attribute.repository.ProductAttributeDefinitionRepository;
import com.lifecontrol.api.product.attribute.repository.ProductAttributeProfileRootRepository;
import com.lifecontrol.api.product.attribute.repository.ProductAttributeValueRepository;
import com.lifecontrol.api.product.category.exception.ProductCategoryNotFoundException;
import com.lifecontrol.api.product.category.model.ProductCategory;
import com.lifecontrol.api.product.category.repository.ProductCategoryRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the attribute profile of a product category and validates the values a product or a variant
 * writes against it.
 *
 * <p>The resolution is one rule with two steps, and both are stated here rather than in a query:
 * a category's profile is the one attached to its <b>root</b> ({@code product_attribute_profile_roots},
 * matched by name — see {@code ProductAttributeProfileRoot}), and a company's own attachment wins over
 * the seeded global one. The root is found by walking {@code parentCategory} up, which is what makes the
 * attachment independent of how deep the operator classified the product: every Calzado sub-branch
 * answers the same profile.</p>
 *
 * <p>Validation is a service rule and not a form rule. The form is not an integrity boundary and any
 * other client writes through the same endpoint, so a value that does not belong to its definition is
 * refused with a {@code 400} that names the code. The rules per kind are the ones
 * {@link ProductAttributeKind} documents; a key that is not an enabled definition of the category's
 * profile <b>at the requested level</b> is refused, which is also what stops a client from writing a
 * variant's {@code color} onto the product.</p>
 *
 * <p>{@code null} attributes are not a special case here: this method validates the state a write will
 * leave behind, so a caller that means "leave the stored set alone" resolves that itself and passes the
 * stored set. {@code null} therefore means an empty set, which is what makes a required attribute
 * impossible to skip by omitting the field.</p>
 */
@Service
public class ProductAttributeCatalogService {

    private static final Logger logger = LoggerFactory.getLogger(ProductAttributeCatalogService.class);

    /**
     * The tree is capped at three levels by the category service, but the database only refuses the
     * trivial self-parent cycle ({@code ck_product_categories_not_self_parent}). The bound is what turns
     * a hypothetical deep cycle into a wrong answer instead of an infinite loop.
     */
    private static final int MAX_TREE_DEPTH = 10;

    private static final int MAX_TEXT_LENGTH = 500;
    private static final int MAX_UNIT_LENGTH = 20;

    private static final String MEASUREMENT_VALUE = "value";
    private static final String MEASUREMENT_UNIT = "unit";

    private final ProductCategoryRepository categoryRepository;
    private final ProductAttributeProfileRootRepository profileRootRepository;
    private final ProductAttributeDefinitionRepository definitionRepository;
    private final ProductAttributeValueRepository valueRepository;

    public ProductAttributeCatalogService(
            ProductCategoryRepository categoryRepository,
            ProductAttributeProfileRootRepository profileRootRepository,
            ProductAttributeDefinitionRepository definitionRepository,
            ProductAttributeValueRepository valueRepository) {
        this.categoryRepository = categoryRepository;
        this.profileRootRepository = profileRootRepository;
        this.definitionRepository = definitionRepository;
        this.valueRepository = valueRepository;
    }

    /**
     * The profile that applies to a category of a company, or empty when the branch declares none.
     *
     * <p>Empty is a legitimate answer and not an error: the seeded catalog attaches four profiles to
     * eleven roots and leaves the rest — "Accesorios y Cuidado", and every category a company built by
     * hand — without one. The screen shows no attribute inputs in that case.</p>
     *
     * @throws ProductCategoryNotFoundException when the category does not exist in that company
     */
    @Transactional(readOnly = true)
    public Optional<ProductAttributeProfileResponse> findProfile(UUID companyId, UUID categoryId) {
        return resolve(companyId, categoryId).map(this::toResponse);
    }

    /**
     * Validates the attributes a caller is about to store on a product ({@code level = PRODUCT}) or on
     * one of its variants ({@code level = VARIANT}).
     *
     * <p>The category is the product's classification, because a variant has none of its own: the
     * variant form submits the profile of the product it hangs from.</p>
     *
     * @param attributes the set that will end up stored, or {@code null} for "nothing" — this method
     *     validates a state, so the caller passes what the write will leave behind, never a request
     *     field that means "leave it alone"
     * @param categoryId the product's classification, or {@code null} when it has none — an unclassified
     *     product declares no profile, so any attribute is unknown and an empty set passes
     * @throws ProductAttributeValidationException when a code, a value, or a required attribute breaks
     *     the profile
     */
    @Transactional(readOnly = true)
    public void validate(UUID companyId, UUID categoryId, Map<String, Object> attributes, ProductAttributeLevel level) {
        var provided = attributes == null ? Map.<String, Object>of() : attributes;

        var resolution = categoryId == null ? Optional.<Resolution>empty() : resolve(companyId, categoryId);
        var definitions = resolution.map(r -> r.definitionsOf(level)).orElseGet(List::of);

        var byCode = new LinkedHashMap<String, ProductAttributeDefinition>();
        for (var definition : definitions) {
            byCode.put(definition.getAttributeCode(), definition);
        }

        for (var entry : provided.entrySet()) {
            var definition = byCode.get(entry.getKey());
            if (definition == null) {
                throw new ProductAttributeValidationException(String.format(
                        "attribute '%s' is not defined for %s-level attributes of category %s",
                        entry.getKey(), level, categoryId));
            }
            var allowedCodes = resolution.map(r -> r.allowedCodesOf(definition)).orElseGet(Set::of);
            validateValue(definition, entry.getValue(), allowedCodes);
        }

        for (var definition : definitions) {
            if (Boolean.TRUE.equals(definition.getRequired()) && !provided.containsKey(definition.getAttributeCode())) {
                throw new ProductAttributeValidationException(String.format(
                        "attribute '%s' is required for %s-level attributes of category %s",
                        definition.getAttributeCode(), level, categoryId));
            }
        }
    }

    // ---- resolution ----

    private Optional<Resolution> resolve(UUID companyId, UUID categoryId) {
        var category = categoryRepository
                .findByCompanyIdAndId(companyId, categoryId)
                .orElseThrow(() -> new ProductCategoryNotFoundException(categoryId));
        var root = rootOf(category);

        var attachment = profileRootRepository
                .findByCompanyIdAndRootNameIgnoreCase(companyId, root.getCategoryName())
                .or(() -> profileRootRepository.findByCompanyIsNullAndRootNameIgnoreCase(root.getCategoryName()));

        if (attachment.isEmpty()) {
            return Optional.empty();
        }

        var profile = attachment.get().getProfile();
        if (!Boolean.TRUE.equals(profile.getEnabled())) {
            logger.debug(
                    "Attribute profile {} of root {} is disabled; answering no profile",
                    profile.getProfileKey(),
                    root.getCategoryName());
            return Optional.empty();
        }

        var definitions = definitionRepository.findByProfileIdAndEnabledTrueOrderByDisplayOrderAscAttributeCodeAsc(
                profile.getId());
        if (definitions.isEmpty()) {
            return Optional.empty();
        }

        var ids = definitions.stream().map(ProductAttributeDefinition::getId).toList();
        var values = valueRepository.findByDefinitionIdInAndEnabledTrueOrderByDisplayOrderAscLabelAsc(ids).stream()
                .collect(Collectors.groupingBy(
                        value -> value.getDefinition().getId(), LinkedHashMap::new, Collectors.toList()));

        return Optional.of(new Resolution(root, profile, definitions, values));
    }

    private ProductCategory rootOf(ProductCategory category) {
        var node = category;
        for (int level = 1; level < MAX_TREE_DEPTH && node.getParentCategory() != null; level++) {
            node = node.getParentCategory();
        }
        return node;
    }

    private ProductAttributeProfileResponse toResponse(Resolution resolution) {
        var product = new ArrayList<ProductAttributeDefinitionResponse>();
        var variant = new ArrayList<ProductAttributeDefinitionResponse>();

        for (var definition : resolution.definitions()) {
            var response = toDefinitionResponse(definition, resolution);
            if (definition.getAttributeLevel() == ProductAttributeLevel.VARIANT) {
                variant.add(response);
            } else {
                product.add(response);
            }
        }

        return new ProductAttributeProfileResponse(
                resolution.profile().getProfileKey(),
                resolution.profile().getLabel(),
                resolution.root().getCategoryName(),
                product,
                variant);
    }

    private ProductAttributeDefinitionResponse toDefinitionResponse(
            ProductAttributeDefinition definition, Resolution resolution) {
        // Values exist to be chosen; a kind that has no allowed set never renders them, so offering a
        // stray row seeded against it would put an option on screen that the validator rejects.
        var values =
                switch (definition.getKind()) {
                    case ENUM, MULTI_ENUM ->
                        resolution.valuesOf(definition).stream()
                                .map(value -> new ProductAttributeValueResponse(value.getValueCode(), value.getLabel()))
                                .toList();
                    case TEXT, MEASUREMENT, BOOLEAN -> List.<ProductAttributeValueResponse>of();
                };

        return new ProductAttributeDefinitionResponse(
                definition.getAttributeCode(),
                definition.getLabel(),
                definition.getAttributeLevel(),
                definition.getKind(),
                definition.getUnitLabel(),
                definition.getRequired(),
                values);
    }

    // ---- per-kind validation ----

    private void validateValue(ProductAttributeDefinition definition, Object value, Set<String> allowedCodes) {
        var code = definition.getAttributeCode();

        if (value == null) {
            throw new ProductAttributeValidationException(
                    String.format("attribute '%s' must not be null; omit it to leave it unset", code));
        }

        switch (definition.getKind()) {
            case TEXT -> {
                if (!(value instanceof String text) || text.isBlank() || text.length() > MAX_TEXT_LENGTH) {
                    throw new ProductAttributeValidationException(String.format(
                            "attribute '%s' must be a non-blank text of at most %d characters", code, MAX_TEXT_LENGTH));
                }
            }
            case ENUM -> {
                if (!(value instanceof String text) || !allowedCodes.contains(text)) {
                    throw new ProductAttributeValidationException(
                            String.format("attribute '%s' must be one of %s, got %s", code, allowedCodes, value));
                }
            }
            case MULTI_ENUM -> validateMultiEnum(code, value, allowedCodes);
            case MEASUREMENT -> validateMeasurement(definition, value);
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) {
                    throw new ProductAttributeValidationException(
                            String.format("attribute '%s' must be a boolean, got %s", code, value));
                }
            }
        }
    }

    private void validateMultiEnum(String code, Object value, Set<String> allowedCodes) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new ProductAttributeValidationException(
                    String.format("attribute '%s' must be a non-empty list of %s", code, allowedCodes));
        }

        var seen = new LinkedHashSet<String>();
        for (var element : list) {
            if (!(element instanceof String text) || !allowedCodes.contains(text)) {
                throw new ProductAttributeValidationException(
                        String.format("attribute '%s' accepts only %s, got %s", code, allowedCodes, element));
            }
            if (!seen.add(text)) {
                throw new ProductAttributeValidationException(
                        String.format("attribute '%s' repeats the value '%s'", code, text));
            }
        }
    }

    private void validateMeasurement(ProductAttributeDefinition definition, Object value) {
        var code = definition.getAttributeCode();
        if (!(value instanceof Map<?, ?> measurement)) {
            throw new ProductAttributeValidationException(
                    String.format("attribute '%s' must be an object with 'value' and 'unit', got %s", code, value));
        }

        for (var key : measurement.keySet()) {
            if (!MEASUREMENT_VALUE.equals(key) && !MEASUREMENT_UNIT.equals(key)) {
                throw new ProductAttributeValidationException(String.format(
                        "attribute '%s' does not accept the key '%s'; only 'value' and 'unit'", code, key));
            }
        }

        if (!(measurement.get(MEASUREMENT_VALUE) instanceof Number number)
                || Double.isNaN(number.doubleValue())
                || Double.isInfinite(number.doubleValue())
                || number.doubleValue() < 0) {
            throw new ProductAttributeValidationException(String.format(
                    "attribute '%s' needs a non-negative numeric 'value', got %s",
                    code, measurement.get(MEASUREMENT_VALUE)));
        }

        if (!(measurement.get(MEASUREMENT_UNIT) instanceof String unit)
                || unit.isBlank()
                || unit.length() > MAX_UNIT_LENGTH) {
            // The definition's unitLabel is a hint and not a constraint: the same branch sells "1/2
            // pulgada" and "10 milímetros", so the stored value carries its own unit.
            throw new ProductAttributeValidationException(String.format(
                    "attribute '%s' needs a non-blank 'unit' of at most %d characters (the definition suggests '%s')",
                    code, MAX_UNIT_LENGTH, definition.getUnitLabel()));
        }
    }

    /**
     * One resolved profile for one category: the root it was found under, the profile, its enabled
     * definitions and their enabled values.
     */
    private record Resolution(
            ProductCategory root,
            ProductAttributeProfile profile,
            List<ProductAttributeDefinition> definitions,
            Map<UUID, List<ProductAttributeValue>> valuesByDefinition) {

        List<ProductAttributeDefinition> definitionsOf(ProductAttributeLevel level) {
            return definitions.stream()
                    .filter(definition -> definition.getAttributeLevel() == level)
                    .toList();
        }

        Set<String> allowedCodesOf(ProductAttributeDefinition definition) {
            return valuesOf(definition).stream()
                    .map(ProductAttributeValue::getValueCode)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        List<ProductAttributeValue> valuesOf(ProductAttributeDefinition definition) {
            return valuesByDefinition.getOrDefault(definition.getId(), List.of());
        }
    }
}
