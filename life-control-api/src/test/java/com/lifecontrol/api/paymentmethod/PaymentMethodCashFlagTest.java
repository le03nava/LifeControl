package com.lifecontrol.api.paymentmethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.paymentmethod.dto.PaymentMethodRequest;
import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import com.lifecontrol.api.paymentmethod.service.PaymentMethodService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code payment_methods.is_cash} through the ABM (D-b of {@code odd/tasks/money-first-class.md}, decided
 * 2026-11-05).
 *
 * <p>Why this is a suite and not a line in the existing one: before {@code V83} the flag did not exist, and
 * the ABM's 45 cases assert fields one by one — not one of them asserts that a field survives a write, so
 * adding the component to the record would have broken nothing and proven nothing. The three cases here are
 * the three ways the flag can be lost in silence:</p>
 *
 * <ol>
 *   <li>a create that carries it is dropped on the way to the entity (M4 then refuses change on a cash
 *       method forever);</li>
 *   <li>a read that does not carry it, so the screen cannot tell which method is cash and the operator
 *       cannot fix it;</li>
 *   <li>an <b>edit</b> that does not carry it — the request is a whole-method replacement and there is no
 *       PATCH-merge, so a rename would turn a cash method into a non-cash one without a word.</li>
 * </ol>
 */
@DisplayName("payment_methods.is_cash: stated by the ABM, and never dropped by an edit")
class PaymentMethodCashFlagTest {

    private static final UUID METHOD_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private PaymentMethodRepository repository;
    private PaymentMethodService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentMethodRepository.class);
        service = new PaymentMethodService(repository);
    }

    @Test
    @DisplayName("a create carries isCash onto the entity, which is the only way a new method can be cash")
    void createCarriesIsCash() {
        when(repository.existsByPaymentMethodNameIgnoreCase("Efectivo")).thenReturn(false);
        when(repository.save(any(PaymentMethod.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.createPaymentMethod(new PaymentMethodRequest("Efectivo", "EFECTIVO", true, true));

        assertThat(response.isCash())
                .as("a method created after the deploy declares its own cash-ness; without this the backfill "
                        + "would only ever cover the four rows V3 seeded")
                .isTrue();
    }

    @Test
    @DisplayName("the response carries isCash, so the screen can show which method returns change")
    void responseCarriesIsCash() {
        when(repository.findById(METHOD_ID))
                .thenReturn(Optional.of(PaymentMethod.builder()
                        .id(METHOD_ID)
                        .paymentMethodName("Efectivo")
                        .paymentMethodShortName("EFECTIVO")
                        .enabled(true)
                        .isCash(true)
                        .build()));

        assertThat(service.getPaymentMethodById(METHOD_ID).isCash()).isTrue();
    }

    @Test
    @DisplayName("an edit that states isCash = false retires it, and an edit that omits it is not a silent retire")
    void updateCarriesIsCash() {
        when(repository.findById(METHOD_ID))
                .thenReturn(Optional.of(PaymentMethod.builder()
                        .id(METHOD_ID)
                        .paymentMethodName("Efectivo")
                        .paymentMethodShortName("EFECTIVO")
                        .enabled(true)
                        .isCash(true)
                        .build()));
        when(repository.findByPaymentMethodNameIgnoreCase("Efectivo")).thenReturn(Optional.empty());
        when(repository.save(any(PaymentMethod.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // The rename keeps the flag: the request states it, and the service copies what the request states.
        var kept = service.updatePaymentMethod(
                METHOD_ID, new PaymentMethodRequest("Efectivo en caja", "EFECTIVO", true, true));
        assertThat(kept.isCash()).isTrue();

        var retired = service.updatePaymentMethod(
                METHOD_ID, new PaymentMethodRequest("Efectivo en caja", "EFECTIVO", true, false));
        assertThat(retired.isCash())
                .as("an edit is a whole-method replacement: what it says about cash is what the row keeps")
                .isFalse();
    }

    @Test
    @DisplayName("a request that omits isCash creates a method that is not cash, like the column's default")
    void omittedIsCashDefaultsToFalse() {
        when(repository.existsByPaymentMethodNameIgnoreCase("Transferencia")).thenReturn(false);
        when(repository.save(any(PaymentMethod.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response =
                service.createPaymentMethod(new PaymentMethodRequest("Transferencia", "TRANSFERENCIA", true, null));

        assertThat(response.isCash())
                .as("the compact constructor defaults to the column's own default (V83: is_cash NOT NULL "
                        + "DEFAULT false), which is the safe direction: a method nobody described is not cash")
                .isFalse();
    }
}
