package com.lifecontrol.api.status.service;

import com.lifecontrol.api.status.dto.StatusTypeResponse;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusTypeRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only catalog of enabled status types, and the bean that owns the {@code statusTypes} cache
 * region's paged read.
 *
 * <p>The cache must not hold a {@link Page}: {@code PageImpl} has no Jackson creator, so a Redis
 * cache hit could not be deserialized and every read failed for the entry's whole TTL. It caches a
 * {@link StatusTypePage} (content plus total) instead, and {@link StatusTypeService} re-wraps that
 * with the request's own {@code Pageable}. The paged JPA query still does the ordering and counting,
 * so a {@code ?sort=} the client sends keeps its database collation.
 *
 * <p>{@link StatusTypeService} delegates here through injection — an internal self-call would bypass
 * the caching proxy. The default cache key ({@code SimpleKey(pageable, search)}) stays a function of
 * both arguments.
 */
@Component
public class StatusTypeCatalog {

    private final StatusTypeRepository statusTypeRepository;

    public StatusTypeCatalog(StatusTypeRepository statusTypeRepository) {
        this.statusTypeRepository = statusTypeRepository;
    }

    @Cacheable(value = "statusTypes")
    @Transactional(readOnly = true)
    public StatusTypePage getPage(Pageable pageable, String search) {
        Page<StatusType> page;
        if (search != null && !search.isBlank()) {
            page = statusTypeRepository.findByEnabledTrueAndStatusTypeNameContainingIgnoreCase(search, pageable);
        } else {
            page = statusTypeRepository.findByEnabledTrue(pageable);
        }
        return new StatusTypePage(page.map(this::toResponse).getContent(), page.getTotalElements());
    }

    private StatusTypeResponse toResponse(StatusType statusType) {
        return new StatusTypeResponse(
                statusType.getId(),
                statusType.getStatusTypeName(),
                statusType.getEnabled(),
                statusType.getCreatedAt(),
                statusType.getUpdatedAt());
    }
}
