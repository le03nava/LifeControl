package com.lifecontrol.api.shift.service;

import com.lifecontrol.api.shift.dto.ShiftRequest;
import com.lifecontrol.api.shift.dto.ShiftResponse;
import com.lifecontrol.api.shift.exception.ShiftAlreadyOpenException;
import com.lifecontrol.api.shift.exception.ShiftNotFoundException;
import com.lifecontrol.api.shift.model.Shift;
import com.lifecontrol.api.shift.repository.ShiftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftService Tests")
class ShiftServiceTest {

    @Mock
    private ShiftRepository shiftRepository;

    @InjectMocks
    private ShiftService shiftService;

    private UUID shiftId;
    private UUID companyStoreId;
    private Shift testShift;
    private ShiftRequest testShiftRequest;

    @BeforeEach
    void setUp() {
        shiftId = UUID.randomUUID();
        companyStoreId = UUID.randomUUID();
        var now = LocalDateTime.now();

        testShift = Shift.builder()
                .id(shiftId)
                .companyStoreId(companyStoreId)
                .userId("user123")
                .openedAt(now.minusHours(2))
                .status("ABIERTO")
                .enabled(true)
                .build();

        testShiftRequest = new ShiftRequest(
                companyStoreId,
                "user123",
                true
        );
    }

    // ─────────────────────────────────────────────
    // getAllShifts
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("getAllShifts")
    class GetAllShiftsTests {

        @Test
        @DisplayName("should return paginated enabled shifts ordered by openedAt desc")
        void getAllShifts_Paginated() {
            var pageable = PageRequest.of(0, 12);
            var shifts = List.of(testShift);
            var expectedPage = new PageImpl<>(shifts, pageable, 1);

            when(shiftRepository.findByEnabledTrueOrderByOpenedAtDesc(pageable)).thenReturn(expectedPage);

            Page<ShiftResponse> result = shiftService.getAllShifts(pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).id()).isEqualTo(shiftId);
            assertThat(result.getContent().get(0).companyStoreId()).isEqualTo(companyStoreId);
            assertThat(result.getContent().get(0).userId()).isEqualTo("user123");
            assertThat(result.getContent().get(0).status()).isEqualTo("ABIERTO");
            assertThat(result.getContent().get(0).enabled()).isTrue();
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getTotalPages()).isEqualTo(1);
            verify(shiftRepository).findByEnabledTrueOrderByOpenedAtDesc(pageable);
        }

        @Test
        @DisplayName("should return empty page when no shifts exist")
        void getAllShifts_EmptyPage() {
            var pageable = PageRequest.of(0, 12);
            var expectedPage = new PageImpl<Shift>(List.of(), pageable, 0);

            when(shiftRepository.findByEnabledTrueOrderByOpenedAtDesc(pageable)).thenReturn(expectedPage);

            Page<ShiftResponse> result = shiftService.getAllShifts(pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }
    }

    // ─────────────────────────────────────────────
    // getShiftById
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("getShiftById")
    class GetShiftByIdTests {

        @Test
        @DisplayName("should return shift when found")
        void getShiftById_Found() {
            when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(testShift));

            ShiftResponse result = shiftService.getShiftById(shiftId);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(shiftId);
            assertThat(result.companyStoreId()).isEqualTo(companyStoreId);
            assertThat(result.userId()).isEqualTo("user123");
            assertThat(result.status()).isEqualTo("ABIERTO");
            assertThat(result.enabled()).isTrue();
        }

        @Test
        @DisplayName("should throw ShiftNotFoundException when not found")
        void getShiftById_NotFound_ThrowsException() {
            when(shiftRepository.findById(shiftId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> shiftService.getShiftById(shiftId))
                    .isInstanceOf(ShiftNotFoundException.class)
                    .hasMessageContaining("Shift not found with id");
        }
    }

    // ─────────────────────────────────────────────
    // createShift
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("createShift")
    class CreateShiftTests {

        @Test
        @DisplayName("should create shift with status ABIERTO and return response")
        void createShift_Success() {
            when(shiftRepository.save(any(Shift.class))).thenReturn(testShift);

            ShiftResponse result = shiftService.createShift(testShiftRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(shiftId);
            assertThat(result.companyStoreId()).isEqualTo(companyStoreId);
            assertThat(result.userId()).isEqualTo("user123");
            assertThat(result.status()).isEqualTo("ABIERTO");
            assertThat(result.enabled()).isTrue();
            verify(shiftRepository).save(any(Shift.class));
        }

        @Test
        @DisplayName("should default enabled to true when not provided")
        void createShift_DefaultsEnabledToTrue() {
            var requestWithoutEnabled = new ShiftRequest(companyStoreId, "user456", null);
            var savedShift = Shift.builder()
                    .id(UUID.randomUUID())
                    .companyStoreId(companyStoreId)
                    .userId("user456")
                    .openedAt(LocalDateTime.now())
                    .status("ABIERTO")
                    .enabled(true)
                    .build();

            when(shiftRepository.save(any(Shift.class))).thenReturn(savedShift);

            ShiftResponse result = shiftService.createShift(requestWithoutEnabled);

            assertThat(result).isNotNull();
            assertThat(result.userId()).isEqualTo("user456");
            assertThat(result.enabled()).isTrue();
            verify(shiftRepository).save(any(Shift.class));
        }
    }

    // ─────────────────────────────────────────────
    // updateShift
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("updateShift")
    class UpdateShiftTests {

        @Test
        @DisplayName("should update shift fields and return response")
        void updateShift_Success() {
            var newStoreId = UUID.randomUUID();
            var updateRequest = new ShiftRequest(newStoreId, "user789", false);

            when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(testShift));
            when(shiftRepository.save(any(Shift.class))).thenAnswer(inv -> inv.getArgument(0));

            ShiftResponse result = shiftService.updateShift(shiftId, updateRequest);

            assertThat(result).isNotNull();
            assertThat(result.companyStoreId()).isEqualTo(newStoreId);
            assertThat(result.userId()).isEqualTo("user789");
            assertThat(result.enabled()).isFalse();
            verify(shiftRepository).save(any(Shift.class));
        }

        @Test
        @DisplayName("should throw ShiftNotFoundException when shift not found")
        void updateShift_NotFound_ThrowsException() {
            var nonExistentId = UUID.randomUUID();
            when(shiftRepository.findById(nonExistentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> shiftService.updateShift(nonExistentId, testShiftRequest))
                    .isInstanceOf(ShiftNotFoundException.class)
                    .hasMessageContaining("Shift not found with id");

            verify(shiftRepository, never()).save(any(Shift.class));
        }
    }

    // ─────────────────────────────────────────────
    // deleteShift
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("deleteShift")
    class DeleteShiftTests {

        @Test
        @DisplayName("should soft-delete shift by setting enabled to false")
        void deleteShift_Success() {
            when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(testShift));
            when(shiftRepository.save(any(Shift.class))).thenAnswer(inv -> inv.getArgument(0));

            shiftService.deleteShift(shiftId);

            verify(shiftRepository).findById(shiftId);
            verify(shiftRepository).save(any(Shift.class));
        }

        @Test
        @DisplayName("should throw ShiftNotFoundException when shift not found")
        void deleteShift_NotFound_ThrowsException() {
            var nonExistentId = UUID.randomUUID();
            when(shiftRepository.findById(nonExistentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> shiftService.deleteShift(nonExistentId))
                    .isInstanceOf(ShiftNotFoundException.class)
                    .hasMessageContaining("Shift not found with id");

            verify(shiftRepository, never()).save(any(Shift.class));
        }
    }

    // ─────────────────────────────────────────────
    // enableShift
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("enableShift")
    class EnableShiftTests {

        @Test
        @DisplayName("should re-enable a disabled shift")
        void enableShift_Success() {
            var disabledShift = Shift.builder()
                    .id(shiftId)
                    .companyStoreId(companyStoreId)
                    .userId("user123")
                    .openedAt(LocalDateTime.now().minusHours(3))
                    .status("CERRADO")
                    .enabled(false)
                    .build();

            when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(disabledShift));
            when(shiftRepository.save(any(Shift.class))).thenAnswer(inv -> inv.getArgument(0));

            ShiftResponse result = shiftService.enableShift(shiftId);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(shiftId);
            assertThat(result.enabled()).isTrue();
            verify(shiftRepository).save(any(Shift.class));
        }
    }

    // ─────────────────────────────────────────────
    // openShift
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("openShift")
    class OpenShiftTests {

        @Test
        @DisplayName("should open a new shift when no open shift exists for the store")
        void openShift_Success() {
            var storeId = companyStoreId;
            when(shiftRepository.findOpenShiftByStoreId(storeId)).thenReturn(Optional.empty());
            when(shiftRepository.save(any(Shift.class))).thenReturn(testShift);

            ShiftResponse result = shiftService.openShift(storeId, "user123");

            assertThat(result).isNotNull();
            assertThat(result.companyStoreId()).isEqualTo(storeId);
            assertThat(result.userId()).isEqualTo("user123");
            assertThat(result.status()).isEqualTo("ABIERTO");
            assertThat(result.enabled()).isTrue();
            verify(shiftRepository).findOpenShiftByStoreId(storeId);
            verify(shiftRepository).save(any(Shift.class));
        }

        @Test
        @DisplayName("should throw ShiftAlreadyOpenException when store already has an open shift")
        void openShift_DuplicateOpen_ThrowsException() {
            var storeId = companyStoreId;
            var existingOpenShift = Shift.builder()
                    .id(UUID.randomUUID())
                    .companyStoreId(storeId)
                    .status("ABIERTO")
                    .enabled(true)
                    .build();

            when(shiftRepository.findOpenShiftByStoreId(storeId)).thenReturn(Optional.of(existingOpenShift));

            assertThatThrownBy(() -> shiftService.openShift(storeId, "user123"))
                    .isInstanceOf(ShiftAlreadyOpenException.class)
                    .hasMessageContaining("An open shift already exists for store");

            verify(shiftRepository, never()).save(any(Shift.class));
        }
    }

    // ─────────────────────────────────────────────
    // closeShift
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("closeShift")
    class CloseShiftTests {

        @Test
        @DisplayName("should close an open shift by setting closedAt and status to CERRADO")
        void closeShift_Success() {
            when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(testShift));
            when(shiftRepository.save(any(Shift.class))).thenAnswer(inv -> inv.getArgument(0));

            ShiftResponse result = shiftService.closeShift(shiftId);

            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo("CERRADO");
            assertThat(result.closedAt()).isNotNull();
            verify(shiftRepository).save(any(Shift.class));
        }

        @Test
        @DisplayName("should throw ShiftNotFoundException when shift not found")
        void closeShift_NotFound_ThrowsException() {
            var nonExistentId = UUID.randomUUID();
            when(shiftRepository.findById(nonExistentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> shiftService.closeShift(nonExistentId))
                    .isInstanceOf(ShiftNotFoundException.class)
                    .hasMessageContaining("Shift not found with id");
        }

        @Test
        @DisplayName("should throw IllegalStateException when shift is not ABIERTO")
        void closeShift_NotOpen_ThrowsIllegalStateException() {
            var closedShift = Shift.builder()
                    .id(shiftId)
                    .companyStoreId(companyStoreId)
                    .userId("user123")
                    .openedAt(LocalDateTime.now().minusHours(5))
                    .closedAt(LocalDateTime.now().minusHours(1))
                    .status("CERRADO")
                    .enabled(true)
                    .build();

            when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(closedShift));

            assertThatThrownBy(() -> shiftService.closeShift(shiftId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Shift is not open");

            verify(shiftRepository, never()).save(any(Shift.class));
        }
    }

    // ─────────────────────────────────────────────
    // getOpenShifts
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("getOpenShifts")
    class GetOpenShiftsTests {

        @Test
        @DisplayName("should return list of all open shifts")
        void getOpenShifts_ReturnsList() {
            var openShift2 = Shift.builder()
                    .id(UUID.randomUUID())
                    .companyStoreId(UUID.randomUUID())
                    .userId("user456")
                    .openedAt(LocalDateTime.now())
                    .status("ABIERTO")
                    .enabled(true)
                    .build();

            when(shiftRepository.findAllOpenShifts()).thenReturn(List.of(testShift, openShift2));

            List<ShiftResponse> result = shiftService.getOpenShifts();

            assertThat(result).isNotNull();
            assertThat(result).hasSize(2);
            assertThat(result.get(0).status()).isEqualTo("ABIERTO");
            assertThat(result.get(1).status()).isEqualTo("ABIERTO");
            verify(shiftRepository).findAllOpenShifts();
        }

        @Test
        @DisplayName("should return empty list when no open shifts exist")
        void getOpenShifts_Empty() {
            when(shiftRepository.findAllOpenShifts()).thenReturn(List.of());

            List<ShiftResponse> result = shiftService.getOpenShifts();

            assertThat(result).isNotNull();
            assertThat(result).isEmpty();
        }
    }
}
