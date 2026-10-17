package com.lifecontrol.api.status.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.lifecontrol.api.status.dto.StatusTypeRequest;
import com.lifecontrol.api.status.dto.StatusTypeResponse;
import com.lifecontrol.api.status.exception.DuplicateStatusTypeException;
import com.lifecontrol.api.status.exception.StatusTypeNotFoundException;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusTypeRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
@DisplayName("StatusTypeService Tests")
class StatusTypeServiceTest {

    @Mock
    private StatusTypeRepository statusTypeRepository;

    private StatusTypeCatalog statusTypeCatalog;
    private StatusTypeService statusTypeService;

    private StatusType testStatusType;
    private StatusTypeRequest testStatusTypeRequest;
    private UUID testStatusTypeId;

    @BeforeEach
    void setUp() {
        testStatusTypeId = UUID.randomUUID();

        testStatusType = StatusType.builder()
                .id(testStatusTypeId)
                .statusTypeName("ORDER")
                .enabled(true)
                .build();

        testStatusTypeRequest = new StatusTypeRequest("ORDER", true);

        statusTypeCatalog = new StatusTypeCatalog(statusTypeRepository);
        statusTypeService = new StatusTypeService(statusTypeRepository, statusTypeCatalog);
    }

    @Nested
    @DisplayName("getAllStatusTypes")
    class GetAllStatusTypesTests {

        @Test
        @DisplayName("should use the paged repository query and echo the request pageable, content and total")
        void getAllStatusTypes_PagedQuery_EchoesPageableContentAndTotal() {
            Pageable pageable = PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "statusTypeName"));
            Page<StatusType> page = new PageImpl<>(List.of(testStatusType), pageable, 5);
            when(statusTypeRepository.findByEnabledTrue(pageable)).thenReturn(page);

            var result = statusTypeService.getAllStatusTypes(pageable, null);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).statusTypeName()).isEqualTo("ORDER");
            assertThat(result.getTotalElements()).isEqualTo(5);
            assertThat(result.getPageable()).isEqualTo(pageable);
            assertThat(result.getPageable().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "statusTypeName"));
            verify(statusTypeRepository).findByEnabledTrue(pageable);
        }

        @Test
        @DisplayName("should pass the search term and pageable through to the paged repository query")
        void getAllStatusTypes_WithSearch_PassesSearchAndPageableThrough() {
            Pageable pageable = PageRequest.of(0, 12);
            Page<StatusType> page = new PageImpl<>(List.of(testStatusType), pageable, 1);
            when(statusTypeRepository.findByEnabledTrueAndStatusTypeNameContainingIgnoreCase("ORD", pageable))
                    .thenReturn(page);

            var result = statusTypeService.getAllStatusTypes(pageable, "ORD");

            assertThat(result.getContent()).hasSize(1);
            verify(statusTypeRepository).findByEnabledTrueAndStatusTypeNameContainingIgnoreCase("ORD", pageable);
            verify(statusTypeRepository, never()).findByEnabledTrue(any());
        }

        @Test
        @DisplayName("should return an empty page with total zero when the query returns no rows")
        void getAllStatusTypes_EmptyResult_ReturnsEmptyPageWithTotalZero() {
            Pageable pageable = PageRequest.of(0, 12);
            when(statusTypeRepository.findByEnabledTrue(pageable)).thenReturn(Page.empty(pageable));

            var result = statusTypeService.getAllStatusTypes(pageable, null);

            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
            assertThat(result.getPageable()).isEqualTo(pageable);
        }
    }

    @Nested
    @DisplayName("getStatusTypeById")
    class GetStatusTypeByIdTests {

        @Test
        @DisplayName("should return status type when exists")
        void getStatusTypeById_Success() {
            when(statusTypeRepository.findById(testStatusTypeId)).thenReturn(Optional.of(testStatusType));

            StatusTypeResponse result = statusTypeService.getStatusTypeById(testStatusTypeId);

            assertThat(result).isNotNull();
            assertThat(result.statusTypeName()).isEqualTo("ORDER");
            assertThat(result.enabled()).isTrue();
        }

        @Test
        @DisplayName("should throw StatusTypeNotFoundException when not exists")
        void getStatusTypeById_NotFound_ThrowsException() {
            when(statusTypeRepository.findById(testStatusTypeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> statusTypeService.getStatusTypeById(testStatusTypeId))
                    .isInstanceOf(StatusTypeNotFoundException.class)
                    .hasMessageContaining("Status type not found with id");
        }
    }

    @Nested
    @DisplayName("createStatusType")
    class CreateStatusTypeTests {

        @Test
        @DisplayName("should create status type successfully")
        void createStatusType_Success() {
            when(statusTypeRepository.existsByStatusTypeNameIgnoreCase("ORDER")).thenReturn(false);
            when(statusTypeRepository.save(any(StatusType.class))).thenAnswer(inv -> {
                StatusType st = inv.getArgument(0);
                return StatusType.builder()
                        .id(testStatusTypeId)
                        .statusTypeName(st.getStatusTypeName())
                        .enabled(true)
                        .build();
            });

            StatusTypeResponse result = statusTypeService.createStatusType(testStatusTypeRequest);

            assertThat(result).isNotNull();
            assertThat(result.statusTypeName()).isEqualTo("ORDER");
            assertThat(result.enabled()).isTrue();
            verify(statusTypeRepository).save(any(StatusType.class));
        }

        @Test
        @DisplayName("should throw DuplicateStatusTypeException when name exists")
        void createStatusType_DuplicateName_ThrowsException() {
            when(statusTypeRepository.existsByStatusTypeNameIgnoreCase("ORDER")).thenReturn(true);

            assertThatThrownBy(() -> statusTypeService.createStatusType(testStatusTypeRequest))
                    .isInstanceOf(DuplicateStatusTypeException.class)
                    .hasMessageContaining("Status type with name 'ORDER' already exists");
            verify(statusTypeRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateStatusType")
    class UpdateStatusTypeTests {

        @Test
        @DisplayName("should update status type name successfully")
        void updateStatusType_Success() {
            StatusTypeRequest updateRequest = new StatusTypeRequest("ORDER_V2", true);
            when(statusTypeRepository.findById(testStatusTypeId)).thenReturn(Optional.of(testStatusType));
            when(statusTypeRepository.findByStatusTypeNameIgnoreCase("ORDER_V2"))
                    .thenReturn(Optional.empty());
            when(statusTypeRepository.save(any(StatusType.class))).thenAnswer(inv -> inv.getArgument(0));

            StatusTypeResponse result = statusTypeService.updateStatusType(testStatusTypeId, updateRequest);

            assertThat(result).isNotNull();
            assertThat(result.statusTypeName()).isEqualTo("ORDER_V2");
            verify(statusTypeRepository).save(any(StatusType.class));
        }

        @Test
        @DisplayName("should throw StatusTypeNotFoundException when ID not exists")
        void updateStatusType_NotFound_ThrowsException() {
            when(statusTypeRepository.findById(testStatusTypeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> statusTypeService.updateStatusType(testStatusTypeId, testStatusTypeRequest))
                    .isInstanceOf(StatusTypeNotFoundException.class)
                    .hasMessageContaining("Status type not found with id");
        }

        @Test
        @DisplayName("should throw DuplicateStatusTypeException when new name conflicts")
        void updateStatusType_DuplicateName_ThrowsException() {
            UUID otherId = UUID.randomUUID();
            StatusType other = StatusType.builder()
                    .id(otherId)
                    .statusTypeName("PRODUCT")
                    .enabled(true)
                    .build();
            when(statusTypeRepository.findById(testStatusTypeId)).thenReturn(Optional.of(testStatusType));
            when(statusTypeRepository.findByStatusTypeNameIgnoreCase("PRODUCT")).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> statusTypeService.updateStatusType(
                            testStatusTypeId, new StatusTypeRequest("PRODUCT", true)))
                    .isInstanceOf(DuplicateStatusTypeException.class)
                    .hasMessageContaining("Status type with name 'PRODUCT' already exists");
        }
    }

    @Nested
    @DisplayName("deleteStatusType")
    class DeleteStatusTypeTests {

        @Test
        @DisplayName("should soft-delete status type by setting enabled to false")
        void deleteStatusType_Success() {
            when(statusTypeRepository.findById(testStatusTypeId)).thenReturn(Optional.of(testStatusType));
            when(statusTypeRepository.save(any(StatusType.class))).thenAnswer(inv -> inv.getArgument(0));

            statusTypeService.deleteStatusType(testStatusTypeId);

            verify(statusTypeRepository).findById(testStatusTypeId);
            verify(statusTypeRepository).save(any(StatusType.class));
        }

        @Test
        @DisplayName("should throw StatusTypeNotFoundException when not exists")
        void deleteStatusType_NotFound_ThrowsException() {
            when(statusTypeRepository.findById(testStatusTypeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> statusTypeService.deleteStatusType(testStatusTypeId))
                    .isInstanceOf(StatusTypeNotFoundException.class)
                    .hasMessageContaining("Status type not found with id");
            verify(statusTypeRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("enableStatusType")
    class EnableStatusTypeTests {

        @Test
        @DisplayName("should enable a disabled status type")
        void enableStatusType_Success() {
            testStatusType.setEnabled(false);
            when(statusTypeRepository.findById(testStatusTypeId)).thenReturn(Optional.of(testStatusType));
            when(statusTypeRepository.save(any(StatusType.class))).thenAnswer(inv -> inv.getArgument(0));

            StatusTypeResponse result = statusTypeService.enableStatusType(testStatusTypeId);

            assertThat(result).isNotNull();
            verify(statusTypeRepository).save(any(StatusType.class));
        }

        @Test
        @DisplayName("should throw StatusTypeNotFoundException when not exists")
        void enableStatusType_NotFound_ThrowsException() {
            when(statusTypeRepository.findById(testStatusTypeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> statusTypeService.enableStatusType(testStatusTypeId))
                    .isInstanceOf(StatusTypeNotFoundException.class)
                    .hasMessageContaining("Status type not found with id");
            verify(statusTypeRepository, never()).save(any());
        }
    }
}
