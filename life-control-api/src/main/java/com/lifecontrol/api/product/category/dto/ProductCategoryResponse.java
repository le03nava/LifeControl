package com.lifecontrol.api.product.category.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Read model of one node of the product category tree.
 *
 * <p>{@code parentCategoryId} is {@code null} for a root, and {@code depth} is the node's level
 * counted from the root (1-based), so the client never has to recompute the hierarchy it was just
 * given. The list endpoint returns nodes depth-first — a parent immediately precedes its children —
 * which is what makes the flat list directly renderable as a tree.</p>
 *
 * <p>{@code satCode} is the optional SAT {@code ClaveProdServ} the category declares, {@code null} when
 * it declares none; the screen shows the absence rather than inventing a key.</p>
 */
public record ProductCategoryResponse(
        UUID id,
        UUID companyId,
        UUID parentCategoryId,
        String categoryName,
        String description,
        String satCode,
        Integer displayOrder,
        Integer depth,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
