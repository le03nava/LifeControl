package com.lifecontrol.api.product.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Read model of a product.
 *
 * <p>{@code companyId} is the company that owns the product, and it is also the scope the resource is
 * addressed through: {@code /api/companies/{companyId}/products}. It is reported so a caller can prove
 * the owner it is acting under, not so it can discover one — a product of another company is never
 * returned by any endpoint of this resource.</p>
 *
 * <p>{@code categoryId} is the deepest node of the company's category tree the product is classified
 * under, and {@code categoryCompanyId} is the company that owns that node — both {@code null} for an
 * unclassified product. The two companies now agree whenever both are present, because the write path
 * refuses a category of another company; {@code categoryCompanyId} is kept because the product form
 * renders its three selects from the tree the response names, and it does so from this one field without
 * a second lookup. It used to be the only way for a client to learn the owning company, which is what
 * the missing company segment in the path made necessary — that is no longer true.</p>
 *
 * <p>The category's own {@code satCode} is deliberately <b>not</b> here: the product's {@code satCode}
 * is a suggestion the operator accepted or edited, so the value that belongs in a response is the one
 * actually stored on the product.</p>
 *
 * <p>{@code variantMode} is ADR-0006 decision 1: {@code SIMPLE} for the single-variant article,
 * {@code MULTI} for the family. It is reported because the client's own behaviour now depends on it — the
 * Angular form decides with it whether the barcode of a variant is required (decision 4) — and because a
 * reader of the catalog has to be able to tell what the creation wrote without re-deriving it from the
 * variant count, which is a different fact (a {@code MULTI} product with one variant is legal).</p>
 */
public record ProductResponse(
        UUID id,
        String sku,
        String name,
        String shortName,
        String satCode,
        String productType,
        Map<String, Object> attributes,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        UUID categoryId,
        UUID categoryCompanyId,
        UUID companyId,
        String variantMode) {}
