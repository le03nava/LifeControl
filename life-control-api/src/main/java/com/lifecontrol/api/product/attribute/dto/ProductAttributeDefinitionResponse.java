package com.lifecontrol.api.product.attribute.dto;

import com.lifecontrol.api.product.attribute.model.ProductAttributeKind;
import com.lifecontrol.api.product.attribute.model.ProductAttributeLevel;
import java.util.List;

/**
 * One attribute the form has to render, with everything the control needs and nothing it has to infer.
 *
 * <p>{@code kind} selects the control (text, select, multi-select, number plus unit, switch) and
 * {@code level} says which screen owns it. {@code values} is empty for every kind that has no allowed
 * set, so a client can render the enum controls without a second call.</p>
 *
 * <p>{@code unitLabel} is the hint to print beside a measurement input, and it is <b>not</b> a
 * constraint: the stored value carries its own unit, because the same branch legitimately sells "1/2
 * pulgada" and "10 milímetros".</p>
 */
public record ProductAttributeDefinitionResponse(
        String code,
        String label,
        ProductAttributeLevel level,
        ProductAttributeKind kind,
        String unitLabel,
        Boolean required,
        List<ProductAttributeValueResponse> values) {}
