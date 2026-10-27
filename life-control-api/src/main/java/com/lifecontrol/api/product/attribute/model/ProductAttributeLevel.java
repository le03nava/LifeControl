package com.lifecontrol.api.product.attribute.model;

/**
 * Which entity an attribute belongs to.
 *
 * <p>The level lives on the definition rather than on a second profile because one branch needs both:
 * in {@code calzado} the {@code material_externo} is a fact of the product while the {@code color} is a
 * fact of the variant — two colours are two barcodes, two stock rows and two prices. Splitting the
 * catalog in two copies would let them drift.</p>
 */
public enum ProductAttributeLevel {
    PRODUCT,
    VARIANT
}
