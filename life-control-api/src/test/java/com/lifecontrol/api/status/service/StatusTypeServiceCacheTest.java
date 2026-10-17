package com.lifecontrol.api.status.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.lifecontrol.api.status.dto.StatusTypeRequest;
import com.lifecontrol.api.status.dto.StatusTypeResponse;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusTypeRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Pins the {@code statusTypes} region through the Spring cache proxy: a repeated paged read is served
 * from the cache, a different pageable or search term is a different entry, the by-id read shares the
 * region under a different key, and every mutation clears the whole region.
 *
 * <p>The region's value is a {@link StatusTypePage}, never a {@code Page}: {@code PageImpl} has no
 * Jackson creator, so a Redis cache hit could not be deserialized and {@code GET /api/status-types}
 * answered 500 for the entry's whole TTL. {@code CacheValueCodecTest} guards the serializer side of
 * that; this test guards that the caching itself still works, which is what the region is for.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("statusTypes region caching")
class StatusTypeServiceCacheTest {

    @Autowired
    private StatusTypeService statusTypeService;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private StatusTypeRepository statusTypeRepository;

    private StatusType testStatusType;

    @BeforeEach
    void setUp() {
        cacheManager.getCacheNames().stream()
                .map(cacheManager::getCache)
                .filter(java.util.Objects::nonNull)
                .forEach(Cache::clear);

        testStatusType =
                StatusType.builder().statusTypeName("ORDER").enabled(true).build();
    }

    @Test
    @DisplayName("a repeated paged read is served from the cache, and a different pageable is not")
    void getAllStatusTypes_RepeatedRead_IsServedFromTheCache() {
        var sorted = PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "statusTypeName"));
        when(statusTypeRepository.findByEnabledTrue(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(testStatusType), sorted, 5));

        var first = statusTypeService.getAllStatusTypes(sorted, null);
        var second = statusTypeService.getAllStatusTypes(sorted, null);

        assertThat(first.getContent()).isEqualTo(second.getContent());
        verify(statusTypeRepository, times(1)).findByEnabledTrue(sorted);

        statusTypeService.getAllStatusTypes(PageRequest.of(1, 2), null);

        verify(statusTypeRepository, times(1)).findByEnabledTrue(PageRequest.of(1, 2));
    }

    @Test
    @DisplayName("the search term is part of the cache key")
    void getAllStatusTypes_WithSearch_IsADifferentEntry() {
        var pageable = PageRequest.of(0, 12);
        when(statusTypeRepository.findByEnabledTrueAndStatusTypeNameContainingIgnoreCase(
                        eq("ORD"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(testStatusType), pageable, 1));

        statusTypeService.getAllStatusTypes(pageable, "ORD");
        statusTypeService.getAllStatusTypes(pageable, "ORD");

        verify(statusTypeRepository, times(1)).findByEnabledTrueAndStatusTypeNameContainingIgnoreCase("ORD", pageable);
        verify(statusTypeRepository, never()).findByEnabledTrue(any(Pageable.class));
    }

    @Test
    @DisplayName("the paged read and the by-id read share the region under different keys")
    void statusTypesRegion_HoldsBothKeyShapes() {
        var id = UUID.randomUUID();
        var pageable = PageRequest.of(0, 12);
        when(statusTypeRepository.findByEnabledTrue(pageable))
                .thenReturn(new PageImpl<>(List.of(testStatusType), pageable, 1));
        when(statusTypeRepository.findById(id)).thenReturn(Optional.of(testStatusType));

        statusTypeService.getAllStatusTypes(pageable, null);
        StatusTypeResponse byId = statusTypeService.getStatusTypeById(id);

        assertThat(byId.statusTypeName()).isEqualTo("ORDER");
        assertThat(regionKeys()).anyMatch(key -> key instanceof SimpleKey);
        assertThat(regionKeys()).anyMatch(key -> id.equals(key));
    }

    @Test
    @DisplayName("a mutation clears every entry of the region, whatever its key shape")
    void createStatusType_EvictsTheWholeRegion() {
        var pageable = PageRequest.of(0, 12);
        var id = UUID.randomUUID();
        when(statusTypeRepository.findByEnabledTrue(pageable))
                .thenReturn(new PageImpl<>(List.of(testStatusType), pageable, 1));
        when(statusTypeRepository.findById(id)).thenReturn(Optional.of(testStatusType));
        when(statusTypeRepository.existsByStatusTypeNameIgnoreCase("NEW")).thenReturn(false);
        when(statusTypeRepository.save(any(StatusType.class)))
                .thenReturn(
                        StatusType.builder().statusTypeName("NEW").enabled(true).build());

        statusTypeService.getAllStatusTypes(pageable, null);
        statusTypeService.getStatusTypeById(id);
        assertThat(regionKeys()).isNotEmpty();

        statusTypeService.createStatusType(new StatusTypeRequest("NEW", true));

        assertThat(regionKeys()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private java.util.Set<Object> regionKeys() {
        var nativeCache =
                (Map<Object, Object>) cacheManager.getCache("statusTypes").getNativeCache();
        return nativeCache.keySet();
    }
}
