package com.lifecontrol.api.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit coverage of the explicit hierarchy filter resolver: the five optional ids reach one finder
 * unchanged, and the answer is the set of store ids the caller then intersects with its own scope.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyStoreFilterResolver")
class CompanyStoreFilterResolverTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID COUNTRY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID REGION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    private static final UUID ZONE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e4");
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e5");
    private static final UUID SECOND_STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e6");

    @Mock
    private CompanyStoreRepository companyStoreRepository;

    private CompanyStoreFilterResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CompanyStoreFilterResolver(companyStoreRepository);
    }

    @Test
    @DisplayName("should hand the five levels to the hierarchy finder unchanged")
    void passesEveryLevelThrough() {
        when(companyStoreRepository.findIdsByHierarchyFilter(COMPANY_ID, COUNTRY_ID, REGION_ID, ZONE_ID, STORE_ID))
                .thenReturn(List.of(STORE_ID));

        var storeIds = resolver.storeIdsFor(COMPANY_ID, COUNTRY_ID, REGION_ID, ZONE_ID, STORE_ID);

        assertThat(storeIds).containsExactly(STORE_ID);
        verify(companyStoreRepository).findIdsByHierarchyFilter(COMPANY_ID, COUNTRY_ID, REGION_ID, ZONE_ID, STORE_ID);
        verifyNoMoreInteractions(companyStoreRepository);
    }

    @Test
    @DisplayName("should keep every id the finder returns")
    void returnsEveryMatchingStore() {
        when(companyStoreRepository.findIdsByHierarchyFilter(null, null, REGION_ID, null, null))
                .thenReturn(List.of(STORE_ID, SECOND_STORE_ID));

        assertThat(resolver.storeIdsFor(null, null, REGION_ID, null, null))
                .containsExactlyInAnyOrder(STORE_ID, SECOND_STORE_ID);
    }

    @Test
    @DisplayName("should answer an empty set, never null, when the filter matches nothing")
    void emptyResultIsAnEmptySet() {
        when(companyStoreRepository.findIdsByHierarchyFilter(null, null, null, null, STORE_ID))
                .thenReturn(List.of());

        assertThat(resolver.storeIdsFor(null, null, null, null, STORE_ID)).isEmpty();
    }
}
