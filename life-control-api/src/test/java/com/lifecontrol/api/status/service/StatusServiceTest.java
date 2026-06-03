package com.lifecontrol.api.status.service;

import com.lifecontrol.api.status.dto.StatusRequest;
import com.lifecontrol.api.status.dto.StatusResponse;
import com.lifecontrol.api.status.exception.DuplicateStatusException;
import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.exception.StatusTypeNotFoundException;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.status.repository.StatusTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StatusService Tests")
class StatusServiceTest {

    @Mock
    private StatusRepository statusRepository;

    @Mock
    private StatusTypeRepository statusTypeRepository;

    @InjectMocks
    private StatusService statusService;

    private StatusType testStatusType;
    private Status testStatus;
    private StatusRequest testStatusRequest;
    private UUID testStatusTypeId;
    private UUID testStatusId;

    @BeforeEach
    void setUp() {
        testStatusTypeId = UUID.randomUUID();
        testStatusId = UUID.randomUUID();

        testStatusType = StatusType.builder()
                .id(testStatusTypeId)
                .statusTypeName("ORDER")
                .enabled(true)
                .build();

        testStatus = Status.builder()
                .id(testStatusId)
                .statusName("PENDING")
                .statusType(testStatusType)
                .enabled(true)
                .build();

        testStatusRequest = new StatusRequest("PENDING", testStatusTypeId, true);
    }

    @Nested
    @DisplayName("getStatusesByTypeId")
    class GetStatusesByTypeIdTests {

        @Test
        @DisplayName("should return statuses for given type")
        void getStatusesByTypeId_Success() {
            when(statusTypeRepository.existsById(testStatusTypeId)).thenReturn(true);
            when(statusRepository.findByStatusTypeId(testStatusTypeId)).thenReturn(List.of(testStatus));

            List<StatusResponse> result = statusService.getStatusesByTypeId(testStatusTypeId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).statusName()).isEqualTo("PENDING");
            assertThat(result.get(0).statusTypeId()).isEqualTo(testStatusTypeId);
            assertThat(result.get(0).statusTypeName()).isEqualTo("ORDER");
        }

        @Test
        @DisplayName("should return empty list when no statuses for type")
        void getStatusesByTypeId_EmptyList() {
            when(statusTypeRepository.existsById(testStatusTypeId)).thenReturn(true);
            when(statusRepository.findByStatusTypeId(testStatusTypeId)).thenReturn(List.of());

            List<StatusResponse> result = statusService.getStatusesByTypeId(testStatusTypeId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should throw StatusTypeNotFoundException when type not exists")
        void getStatusesByTypeId_TypeNotFound_ThrowsException() {
            when(statusTypeRepository.existsById(testStatusTypeId)).thenReturn(false);

            assertThatThrownBy(() -> statusService.getStatusesByTypeId(testStatusTypeId))
                    .isInstanceOf(StatusTypeNotFoundException.class)
                    .hasMessageContaining("Status type not found with id");
        }
    }

    @Nested
    @DisplayName("getStatusById")
    class GetStatusByIdTests {

        @Test
        @DisplayName("should return status when exists")
        void getStatusById_Success() {
            when(statusRepository.findById(testStatusId)).thenReturn(Optional.of(testStatus));

            StatusResponse result = statusService.getStatusById(testStatusId);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("should throw StatusNotFoundException when not exists")
        void getStatusById_NotFound_ThrowsException() {
            when(statusRepository.findById(testStatusId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> statusService.getStatusById(testStatusId))
                    .isInstanceOf(StatusNotFoundException.class)
                    .hasMessageContaining("Status not found with id");
        }
    }

    @Nested
    @DisplayName("getStatusByIdAndTypeId")
    class GetStatusByIdAndTypeIdTests {

        @Test
        @DisplayName("should return status when found with type scope")
        void getStatusByIdAndTypeId_Success() {
            when(statusRepository.findByIdAndStatusTypeId(testStatusId, testStatusTypeId))
                    .thenReturn(Optional.of(testStatus));

            StatusResponse result = statusService.getStatusByIdAndTypeId(testStatusId, testStatusTypeId);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("should throw StatusNotFoundException when not found")
        void getStatusByIdAndTypeId_NotFound_ThrowsException() {
            when(statusRepository.findByIdAndStatusTypeId(testStatusId, testStatusTypeId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> statusService.getStatusByIdAndTypeId(testStatusId, testStatusTypeId))
                    .isInstanceOf(StatusNotFoundException.class)
                    .hasMessageContaining("Status not found with id");
        }
    }

    @Nested
    @DisplayName("createStatus")
    class CreateStatusTests {

        @Test
        @DisplayName("should create status successfully")
        void createStatus_Success() {
            when(statusTypeRepository.findById(testStatusTypeId)).thenReturn(Optional.of(testStatusType));
            when(statusRepository.existsByStatusNameIgnoreCaseAndStatusTypeId("PENDING", testStatusTypeId)).thenReturn(false);
            when(statusRepository.save(any(Status.class))).thenAnswer(inv -> {
                Status s = inv.getArgument(0);
                return Status.builder()
                        .id(testStatusId)
                        .statusName(s.getStatusName())
                        .statusType(testStatusType)
                        .enabled(true)
                        .build();
            });

            StatusResponse result = statusService.createStatus(testStatusRequest);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("PENDING");
            assertThat(result.enabled()).isTrue();
            verify(statusRepository).save(any(Status.class));
        }

        @Test
        @DisplayName("should throw StatusTypeNotFoundException when parent type not exists")
        void createStatus_TypeNotFound_ThrowsException() {
            when(statusTypeRepository.findById(testStatusTypeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> statusService.createStatus(testStatusRequest))
                    .isInstanceOf(StatusTypeNotFoundException.class)
                    .hasMessageContaining("Status type not found with id");
            verify(statusRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw DuplicateStatusException when name exists within type")
        void createStatus_DuplicateName_ThrowsException() {
            when(statusTypeRepository.findById(testStatusTypeId)).thenReturn(Optional.of(testStatusType));
            when(statusRepository.existsByStatusNameIgnoreCaseAndStatusTypeId("PENDING", testStatusTypeId)).thenReturn(true);

            assertThatThrownBy(() -> statusService.createStatus(testStatusRequest))
                    .isInstanceOf(DuplicateStatusException.class)
                    .hasMessageContaining("Status with name 'PENDING' already exists for this status type");
            verify(statusRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateStatus")
    class UpdateStatusTests {

        @Test
        @DisplayName("should update status successfully")
        void updateStatus_Success() {
            StatusRequest updateRequest = new StatusRequest("WAITING", testStatusTypeId, true);
            when(statusRepository.findById(testStatusId)).thenReturn(Optional.of(testStatus));
            when(statusRepository.existsByStatusNameIgnoreCaseAndStatusTypeId("WAITING", testStatusTypeId)).thenReturn(false);
            when(statusRepository.save(any(Status.class))).thenAnswer(inv -> inv.getArgument(0));

            StatusResponse result = statusService.updateStatus(testStatusId, updateRequest);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("WAITING");
            verify(statusRepository).save(any(Status.class));
        }

        @Test
        @DisplayName("should throw StatusNotFoundException when ID not exists")
        void updateStatus_NotFound_ThrowsException() {
            when(statusRepository.findById(testStatusId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> statusService.updateStatus(testStatusId, testStatusRequest))
                    .isInstanceOf(StatusNotFoundException.class)
                    .hasMessageContaining("Status not found with id");
        }

        @Test
        @DisplayName("should throw DuplicateStatusException when new name conflicts within type")
        void updateStatus_DuplicateName_ThrowsException() {
            StatusRequest conflictRequest = new StatusRequest("SHIPPED", testStatusTypeId, true);
            when(statusRepository.findById(testStatusId)).thenReturn(Optional.of(testStatus));
            when(statusRepository.existsByStatusNameIgnoreCaseAndStatusTypeId("SHIPPED", testStatusTypeId)).thenReturn(true);

            assertThatThrownBy(() -> statusService.updateStatus(testStatusId, conflictRequest))
                    .isInstanceOf(DuplicateStatusException.class)
                    .hasMessageContaining("Status with name 'SHIPPED' already exists for this status type");
        }

        @Test
        @DisplayName("should throw StatusTypeNotFoundException when new type not exists")
        void updateStatus_NewTypeNotFound_ThrowsException() {
            UUID newTypeId = UUID.randomUUID();
            StatusRequest newTypeRequest = new StatusRequest("PENDING", newTypeId, true);
            when(statusRepository.findById(testStatusId)).thenReturn(Optional.of(testStatus));
            when(statusTypeRepository.findById(newTypeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> statusService.updateStatus(testStatusId, newTypeRequest))
                    .isInstanceOf(StatusTypeNotFoundException.class)
                    .hasMessageContaining("Status type not found with id");
        }
    }

    @Nested
    @DisplayName("deleteStatus")
    class DeleteStatusTests {

        @Test
        @DisplayName("should soft-delete status by setting enabled to false")
        void deleteStatus_Success() {
            when(statusRepository.findById(testStatusId)).thenReturn(Optional.of(testStatus));
            when(statusRepository.save(any(Status.class))).thenAnswer(inv -> inv.getArgument(0));

            statusService.deleteStatus(testStatusId);

            verify(statusRepository).findById(testStatusId);
            verify(statusRepository).save(any(Status.class));
        }

        @Test
        @DisplayName("should throw StatusNotFoundException when not exists")
        void deleteStatus_NotFound_ThrowsException() {
            when(statusRepository.findById(testStatusId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> statusService.deleteStatus(testStatusId))
                    .isInstanceOf(StatusNotFoundException.class)
                    .hasMessageContaining("Status not found with id");
            verify(statusRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("enableStatus")
    class EnableStatusTests {

        @Test
        @DisplayName("should enable a disabled status")
        void enableStatus_Success() {
            testStatus.setEnabled(false);
            when(statusRepository.findById(testStatusId)).thenReturn(Optional.of(testStatus));
            when(statusRepository.save(any(Status.class))).thenAnswer(inv -> inv.getArgument(0));

            StatusResponse result = statusService.enableStatus(testStatusId);

            assertThat(result).isNotNull();
            verify(statusRepository).save(any(Status.class));
        }

        @Test
        @DisplayName("should throw StatusNotFoundException when not exists")
        void enableStatus_NotFound_ThrowsException() {
            when(statusRepository.findById(testStatusId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> statusService.enableStatus(testStatusId))
                    .isInstanceOf(StatusNotFoundException.class)
                    .hasMessageContaining("Status not found with id");
            verify(statusRepository, never()).save(any());
        }
    }
}
