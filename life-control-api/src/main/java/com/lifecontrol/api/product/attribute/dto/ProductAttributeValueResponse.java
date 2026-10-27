package com.lifecontrol.api.product.attribute.dto;

/**
 * One allowed value of an enumerable attribute, as the form renders it.
 *
 * <p>{@code code} is what the product JSON stores and what the API validates; {@code label} is what the
 * operator reads. They travel together so the screen never has to translate a code back into words.</p>
 */
public record ProductAttributeValueResponse(String code, String label) {}
