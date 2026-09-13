package com.lifecontrol.api.status.service;

import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.purchaseorder.config.PurchaseOrderStatusInitializer;
import com.lifecontrol.api.salesorder.config.SalesOrderStatusInitializer;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.status.repository.StatusTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Integration tests for {@link StatusService} caching behavior.
 * <p>
 * Uses {@link SpringBootTest} with the {@code test} profile so that Redis is
 * excluded and {@code spring.cache.type=simple} applies. Spring AOP processes
 * the {@code @Cacheable} annotations on {@link StatusService}, so these tests
 * verify the actual caching behavior, including cache-key isolation between
 * {@code getStatusById} and {@code getStatusByIdAndTypeId}.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("StatusService Caching Integration Tests")
class StatusServiceCacheTest {

    @Autowired
    private StatusService statusService;

    @MockBean
    private StatusRepository statusRepository;

    @MockBean
    private StatusTypeRepository statusTypeRepository;

    @MockBean
    private SalesOrderStatusInitializer salesOrderStatusInitializer;

    @MockBean
    private PurchaseOrderStatusInitializer purchaseOrderStatusInitializer;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCacheNames().stream()
                .map(cacheManager::getCache)
                .filter(Objects::nonNull)
                .forEach(cache -> cache.clear());
    }

    @Nested
    @DisplayName("getStatusByIdAndTypeId cache-key isolation")
    class CacheKeyIsolationTests {

        @Test
        @DisplayName("should not return a status cached by getStatusById when the type does not match")
        void getStatusByIdAndTypeId_DoesNotHitGetStatusByIdCacheEntry() {
            var statusId = UUID.randomUUID();
            var statusTypeId = UUID.randomUUID();
            var differentTypeId = UUID.randomUUID();
            var statusType = buildStatusType(statusTypeId, "ORDER");
            var status = buildStatus(statusId, "PENDING", statusType);

            when(statusRepository.findById(statusId)).thenReturn(Optional.of(status));
            when(statusRepository.findByIdAndStatusTypeId(statusId, differentTypeId))
                    .thenReturn(Optional.empty());

            statusService.getStatusById(statusId);

            assertThatThrownBy(() -> statusService.getStatusByIdAndTypeId(statusId, differentTypeId))
                    .isInstanceOf(StatusNotFoundException.class)
                    .hasMessageContaining("Status not found with id");
            verify(statusRepository).findByIdAndStatusTypeId(statusId, differentTypeId);
        }

        @Test
        @DisplayName("should return cached entry for the same id and type on second call")
        void getStatusByIdAndTypeId_CacheHit_ReturnsCachedData() {
            var statusId = UUID.randomUUID();
            var statusTypeId = UUID.randomUUID();
            var statusType = buildStatusType(statusTypeId, "ORDER");
            var status = buildStatus(statusId, "PENDING", statusType);

            when(statusRepository.findByIdAndStatusTypeId(statusId, statusTypeId))
                    .thenReturn(Optional.of(status));

            statusService.getStatusByIdAndTypeId(statusId, statusTypeId);

            when(statusRepository.findByIdAndStatusTypeId(statusId, statusTypeId))
                    .thenThrow(new RuntimeException("Should not reach DB"));

            var secondResult = statusService.getStatusByIdAndTypeId(statusId, statusTypeId);

            assertThat(secondResult).isNotNull();
            assertThat(secondResult.statusTypeId()).isEqualTo(statusTypeId);
            verify(statusRepository, times(1)).findByIdAndStatusTypeId(statusId, statusTypeId);
        }

        @Test
        @DisplayName("should query DB for the same id with a different type (composite key)")
        void getStatusByIdAndTypeId_DifferentType_QueriesDatabase() {
            var statusId = UUID.randomUUID();
            var statusTypeId = UUID.randomUUID();
            var differentTypeId = UUID.randomUUID();
            var statusType = buildStatusType(statusTypeId, "ORDER");
            var differentType = buildStatusType(differentTypeId, "PAYMENT");
            var status = buildStatus(statusId, "PENDING", statusType);
            var otherStatus = buildStatus(UUID.randomUUID(), "PAID", differentType);

            when(statusRepository.findById(statusId)).thenReturn(Optional.of(status));
            when(statusRepository.findByIdAndStatusTypeId(statusId, differentTypeId))
                    .thenReturn(Optional.of(otherStatus));

            statusService.getStatusById(statusId);
            var result = statusService.getStatusByIdAndTypeId(statusId, differentTypeId);

            assertThat(result).isNotNull();
            assertThat(result.statusTypeId()).isEqualTo(differentTypeId);
            verify(statusRepository, times(1)).findByIdAndStatusTypeId(statusId, differentTypeId);
        }
    }

    private StatusType buildStatusType(UUID id, String name) {
        return StatusType.builder()
                .id(id)
                .statusTypeName(name)
                .enabled(true)
                .build();
    }

    private Status buildStatus(UUID id, String name, StatusType statusType) {
        return Status.builder()
                .id(id)
                .statusName(name)
                .statusType(statusType)
                .enabled(true)
                .build();
    }
}