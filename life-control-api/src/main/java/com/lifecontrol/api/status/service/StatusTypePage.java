package com.lifecontrol.api.status.service;

import com.lifecontrol.api.status.dto.StatusTypeResponse;
import java.util.List;

/**
 * Cache payload for the {@code statusTypes} region's paged read: the page's content and its total.
 *
 * <p>Why a record and not a {@link org.springframework.data.domain.Page}: {@code PageImpl} has no
 * Jackson creator, so a Redis cache hit could not be deserialized and every read failed for the
 * entry's whole TTL. This record is reconstructible, and {@link StatusTypeService} re-wraps it with
 * the request's own {@code Pageable} so the response still echoes the client's paging and sort.
 */
public record StatusTypePage(List<StatusTypeResponse> content, long total) {}
