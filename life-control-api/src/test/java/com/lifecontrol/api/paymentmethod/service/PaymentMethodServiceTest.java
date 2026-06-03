package com.lifecontrol.api.paymentmethod.service;

import com.lifecontrol.api.paymentmethod.dto.PaymentMethodRequest;
import com.lifecontrol.api.paymentmethod.dto.PaymentMethodResponse;
import com.lifecontrol.api.paymentmethod.exception.DuplicatePaymentMethodException;
import com.lifecontrol.api.paymentmethod.exception.PaymentMethodNotFoundException;
import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
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
@DisplayName("PaymentMethodService Tests")
class PaymentMethodServiceTest {

    @Mock
    private PaymentMethodRepository paymentMethodRepository;

    @InjectMocks
    private PaymentMethodService paymentMethodService;

    private PaymentMethod testPaymentMethod;
    private PaymentMethodRequest testPaymentMethodRequest;
    private UUID testPaymentMethodId;

    @BeforeEach
    void setUp() {
        testPaymentMethodId = UUID.randomUUID();

        testPaymentMethod = PaymentMethod.builder()
                .id(testPaymentMethodId)
                .paymentMethodName("Efectivo")
                .paymentMethodShortName("EFECTIVO")
                .enabled(true)
                .build();

        testPaymentMethodRequest = new PaymentMethodRequest("Efectivo", "EFECTIVO", true);
    }

    @Nested
    @DisplayName("getAllPaymentMethods")
    class GetAllPaymentMethodsTests {

        @Test
        @DisplayName("should return sorted list of payment methods")
        void getAllPaymentMethods_ReturnsList() {
            when(paymentMethodRepository.findAllByOrderByPaymentMethodNameAsc())
                    .thenReturn(List.of(testPaymentMethod));

            var result = paymentMethodService.getAllPaymentMethods();

            assertThat(result).isNotNull();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).paymentMethodName()).isEqualTo("Efectivo");
            verify(paymentMethodRepository).findAllByOrderByPaymentMethodNameAsc();
        }

        @Test
        @DisplayName("should return empty list when no payment methods exist")
        void getAllPaymentMethods_EmptyList_ReturnsEmpty() {
            when(paymentMethodRepository.findAllByOrderByPaymentMethodNameAsc())
                    .thenReturn(List.of());

            var result = paymentMethodService.getAllPaymentMethods();

            assertThat(result).isEmpty();
            verify(paymentMethodRepository).findAllByOrderByPaymentMethodNameAsc();
        }
    }

    @Nested
    @DisplayName("getPaymentMethodById")
    class GetPaymentMethodByIdTests {

        @Test
        @DisplayName("should return payment method when exists")
        void getPaymentMethodById_Success() {
            when(paymentMethodRepository.findById(testPaymentMethodId))
                    .thenReturn(Optional.of(testPaymentMethod));

            var result = paymentMethodService.getPaymentMethodById(testPaymentMethodId);

            assertThat(result).isNotNull();
            assertThat(result.paymentMethodName()).isEqualTo("Efectivo");
            assertThat(result.enabled()).isTrue();
        }

        @Test
        @DisplayName("should throw PaymentMethodNotFoundException when not exists")
        void getPaymentMethodById_NotFound_ThrowsException() {
            when(paymentMethodRepository.findById(testPaymentMethodId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentMethodService.getPaymentMethodById(testPaymentMethodId))
                    .isInstanceOf(PaymentMethodNotFoundException.class)
                    .hasMessageContaining("Payment method not found with id");
        }
    }

    @Nested
    @DisplayName("createPaymentMethod")
    class CreatePaymentMethodTests {

        @Test
        @DisplayName("should create payment method successfully")
        void createPaymentMethod_Success() {
            when(paymentMethodRepository.existsByPaymentMethodNameIgnoreCase("Efectivo"))
                    .thenReturn(false);
            when(paymentMethodRepository.save(any(PaymentMethod.class)))
                    .thenAnswer(inv -> {
                        PaymentMethod pm = inv.getArgument(0);
                        return PaymentMethod.builder()
                                .id(testPaymentMethodId)
                                .paymentMethodName(pm.getPaymentMethodName())
                                .paymentMethodShortName(pm.getPaymentMethodShortName())
                                .enabled(true)
                                .build();
                    });

            var result = paymentMethodService.createPaymentMethod(testPaymentMethodRequest);

            assertThat(result).isNotNull();
            assertThat(result.paymentMethodName()).isEqualTo("Efectivo");
            assertThat(result.enabled()).isTrue();
            verify(paymentMethodRepository).save(any(PaymentMethod.class));
        }

        @Test
        @DisplayName("should throw DuplicatePaymentMethodException when name exists")
        void createPaymentMethod_DuplicateName_ThrowsException() {
            when(paymentMethodRepository.existsByPaymentMethodNameIgnoreCase("Efectivo"))
                    .thenReturn(true);

            assertThatThrownBy(() -> paymentMethodService.createPaymentMethod(testPaymentMethodRequest))
                    .isInstanceOf(DuplicatePaymentMethodException.class)
                    .hasMessageContaining("Payment method with name 'Efectivo' already exists");
            verify(paymentMethodRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updatePaymentMethod")
    class UpdatePaymentMethodTests {

        @Test
        @DisplayName("should update payment method successfully")
        void updatePaymentMethod_Success() {
            var updateRequest = new PaymentMethodRequest("Transferencia", "TRANSFERENCIA", true);
            when(paymentMethodRepository.findById(testPaymentMethodId))
                    .thenReturn(Optional.of(testPaymentMethod));
            when(paymentMethodRepository.findByPaymentMethodNameIgnoreCase("Transferencia"))
                    .thenReturn(Optional.empty());
            when(paymentMethodRepository.save(any(PaymentMethod.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            var result = paymentMethodService.updatePaymentMethod(testPaymentMethodId, updateRequest);

            assertThat(result).isNotNull();
            assertThat(result.paymentMethodName()).isEqualTo("Transferencia");
            verify(paymentMethodRepository).save(any(PaymentMethod.class));
        }

        @Test
        @DisplayName("should throw PaymentMethodNotFoundException when ID not exists")
        void updatePaymentMethod_NotFound_ThrowsException() {
            when(paymentMethodRepository.findById(testPaymentMethodId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentMethodService.updatePaymentMethod(testPaymentMethodId, testPaymentMethodRequest))
                    .isInstanceOf(PaymentMethodNotFoundException.class)
                    .hasMessageContaining("Payment method not found with id");
        }

        @Test
        @DisplayName("should throw DuplicatePaymentMethodException when new name conflicts with another")
        void updatePaymentMethod_DuplicateName_ThrowsException() {
            UUID otherId = UUID.randomUUID();
            var other = PaymentMethod.builder()
                    .id(otherId)
                    .paymentMethodName("Transferencia")
                    .paymentMethodShortName("TRANSFERENCIA")
                    .enabled(true)
                    .build();
            when(paymentMethodRepository.findById(testPaymentMethodId))
                    .thenReturn(Optional.of(testPaymentMethod));
            when(paymentMethodRepository.findByPaymentMethodNameIgnoreCase("Transferencia"))
                    .thenReturn(Optional.of(other));

            assertThatThrownBy(() -> paymentMethodService.updatePaymentMethod(
                    testPaymentMethodId, new PaymentMethodRequest("Transferencia", "TRANSFERENCIA", true)))
                    .isInstanceOf(DuplicatePaymentMethodException.class)
                    .hasMessageContaining("Payment method with name 'Transferencia' already exists");
        }
    }

    @Nested
    @DisplayName("deletePaymentMethod")
    class DeletePaymentMethodTests {

        @Test
        @DisplayName("should delete payment method when exists")
        void deletePaymentMethod_Success() {
            when(paymentMethodRepository.findById(testPaymentMethodId))
                    .thenReturn(Optional.of(testPaymentMethod));

            paymentMethodService.deletePaymentMethod(testPaymentMethodId);

            verify(paymentMethodRepository).findById(testPaymentMethodId);
            verify(paymentMethodRepository).delete(testPaymentMethod);
        }

        @Test
        @DisplayName("should throw PaymentMethodNotFoundException when not exists")
        void deletePaymentMethod_NotFound_ThrowsException() {
            when(paymentMethodRepository.findById(testPaymentMethodId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentMethodService.deletePaymentMethod(testPaymentMethodId))
                    .isInstanceOf(PaymentMethodNotFoundException.class)
                    .hasMessageContaining("Payment method not found with id");
            verify(paymentMethodRepository, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("setPaymentMethodEnabled")
    class SetPaymentMethodEnabledTests {

        @Test
        @DisplayName("should enable a disabled payment method")
        void setPaymentMethodEnabled_Enable_Success() {
            testPaymentMethod.setEnabled(false);
            when(paymentMethodRepository.findById(testPaymentMethodId))
                    .thenReturn(Optional.of(testPaymentMethod));
            when(paymentMethodRepository.save(any(PaymentMethod.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            var result = paymentMethodService.setPaymentMethodEnabled(testPaymentMethodId, true);

            assertThat(result).isNotNull();
            assertThat(result.enabled()).isTrue();
            verify(paymentMethodRepository).save(any(PaymentMethod.class));
        }

        @Test
        @DisplayName("should disable an enabled payment method")
        void setPaymentMethodEnabled_Disable_Success() {
            testPaymentMethod.setEnabled(true);
            when(paymentMethodRepository.findById(testPaymentMethodId))
                    .thenReturn(Optional.of(testPaymentMethod));
            when(paymentMethodRepository.save(any(PaymentMethod.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            var result = paymentMethodService.setPaymentMethodEnabled(testPaymentMethodId, false);

            assertThat(result).isNotNull();
            assertThat(result.enabled()).isFalse();
            verify(paymentMethodRepository).save(any(PaymentMethod.class));
        }

        @Test
        @DisplayName("should throw PaymentMethodNotFoundException when not exists")
        void setPaymentMethodEnabled_NotFound_ThrowsException() {
            when(paymentMethodRepository.findById(testPaymentMethodId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentMethodService.setPaymentMethodEnabled(testPaymentMethodId, true))
                    .isInstanceOf(PaymentMethodNotFoundException.class)
                    .hasMessageContaining("Payment method not found with id");
            verify(paymentMethodRepository, never()).save(any());
        }
    }
}
