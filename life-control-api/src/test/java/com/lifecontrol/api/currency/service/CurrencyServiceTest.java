package com.lifecontrol.api.currency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.currency.dto.CurrencyRequest;
import com.lifecontrol.api.currency.dto.CurrencyResponse;
import com.lifecontrol.api.currency.exception.CurrencyNotFoundException;
import com.lifecontrol.api.currency.exception.DuplicateCurrencyException;
import com.lifecontrol.api.currency.model.Currency;
import com.lifecontrol.api.currency.repository.CurrencyRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The write half of the catalog: what each verb stores, and what each refusal leaves behind.
 *
 * <p><b>Why the refusals are asserted here and not only at the HTTP boundary.</b> The controller test
 * proves the <b>status</b> a refusal becomes, but its service is a mock, so it cannot prove that the row
 * was left alone — "409" and "409 after an overwrite" are the same status. Every refusal below therefore
 * also asserts the state a caller would still see: the entity's columns and whether {@code save},
 * {@code delete} or {@code flush} was reached at all. That is the observable half a status code cannot
 * carry.</p>
 *
 * <p><b>Why there is no database.</b> The catalog's two database-level refusals — a duplicate primary key
 * and a delete five foreign keys still point at — are {@code Hibernate} and {@code PostgreSQL} behaviour,
 * not this class's, and reproducing them needs a real server (a generated H2 schema has no inbound
 * foreign key to {@code currency} at all, so the delete would succeed there and prove nothing). What is
 * this class's responsibility is that the refusal is raised as the type the {@code GlobalExceptionHandler}
 * already maps, which is exactly what the {@code DataIntegrityViolationException} case pins.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CurrencyService write path")
class CurrencyServiceTest {

    @Mock
    private CurrencyRepository currencyRepository;

    @InjectMocks
    private CurrencyService currencyService;

    private static CurrencyRequest request(String code, int minorUnits, String symbol) {
        return new CurrencyRequest(code, (short) minorUnits, symbol, null);
    }

    // ─── POST semantics ───────────────────────────────────────────

    @Nested
    @DisplayName("createCurrency")
    class CreateCurrencyTests {

        @Test
        @DisplayName("stores every column the request states and returns the stored row")
        void createCurrency_StoresTheStatedColumns() {
            when(currencyRepository.save(any(Currency.class))).thenAnswer(invocation -> invocation.getArgument(0));

            var response = currencyService.createCurrency(request("USD", 2, "US$"));

            assertThat(response).isEqualTo(new CurrencyResponse("USD", (short) 2, "US$", true));

            var captor = ArgumentCaptor.forClass(Currency.class);
            verify(currencyRepository).save(captor.capture());
            assertThat(captor.getValue().getCode()).isEqualTo("USD");
            assertThat(captor.getValue().getMinorUnits()).isEqualTo((short) 2);
            assertThat(captor.getValue().getSymbol()).isEqualTo("US$");
            assertThat(captor.getValue().getEnabled()).isTrue();
        }

        @Test
        @DisplayName("a code the catalog already carries is refused and nothing is written")
        void createCurrency_WithAnExistingCode_RefusesAndWritesNothing() {
            when(currencyRepository.existsById("MXN")).thenReturn(true);

            assertThatThrownBy(() -> currencyService.createCurrency(request("MXN", 2, "$")))
                    .isInstanceOf(DuplicateCurrencyException.class)
                    .hasMessageContaining("MXN");

            verify(currencyRepository, never()).save(any(Currency.class));
        }
    }

    // ─── PUT semantics ────────────────────────────────────────────

    @Nested
    @DisplayName("updateCurrency")
    class UpdateCurrencyTests {

        @Test
        @DisplayName("replaces the three mutable columns of the stored row")
        void updateCurrency_ReplacesTheMutableColumns() {
            var stored = new Currency("MXN", (short) 2, "$", true);
            when(currencyRepository.findById("MXN")).thenReturn(Optional.of(stored));
            when(currencyRepository.save(any(Currency.class))).thenAnswer(invocation -> invocation.getArgument(0));

            var response = currencyService.updateCurrency("MXN", new CurrencyRequest("MXN", (short) 0, "$mxn", false));

            assertThat(response).isEqualTo(new CurrencyResponse("MXN", (short) 0, "$mxn", false));
            assertThat(stored.getMinorUnits()).isEqualTo((short) 0);
            assertThat(stored.getSymbol()).isEqualTo("$mxn");
            assertThat(stored.getEnabled()).isFalse();
        }

        @Test
        @DisplayName("a code the catalog does not carry is a 404 and nothing is written")
        void updateCurrency_WithAnUnknownCode_Answers404AndWritesNothing() {
            when(currencyRepository.findById("ZZZ")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> currencyService.updateCurrency("ZZZ", request("ZZZ", 2, "Z")))
                    .isInstanceOf(CurrencyNotFoundException.class)
                    .hasMessageContaining("ZZZ");

            verify(currencyRepository, never()).save(any(Currency.class));
        }

        @Test
        @DisplayName("a body that renames the row is refused and the row is left exactly as it was")
        void updateCurrency_WithABodyThatRenamesTheRow_RefusesAndLeavesTheRowUntouched() {
            var stored = new Currency("MXN", (short) 2, "$", true);
            when(currencyRepository.findById("MXN")).thenReturn(Optional.of(stored));

            assertThatThrownBy(() ->
                            currencyService.updateCurrency("MXN", new CurrencyRequest("USD", (short) 0, "US$", false)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("MXN")
                    .hasMessageContaining("USD");

            assertThat(stored.getCode()).isEqualTo("MXN");
            assertThat(stored.getMinorUnits()).isEqualTo((short) 2);
            assertThat(stored.getSymbol()).isEqualTo("$");
            assertThat(stored.getEnabled()).isTrue();
            verify(currencyRepository, never()).save(any(Currency.class));
        }
    }

    // ─── DELETE semantics ─────────────────────────────────────────

    @Nested
    @DisplayName("deleteCurrency")
    class DeleteCurrencyTests {

        @Test
        @DisplayName("removes the row and flushes the statement that the foreign keys judge")
        void deleteCurrency_RemovesTheRowAndFlushes() {
            var stored = new Currency("MXN", (short) 2, "$", true);
            when(currencyRepository.findById("MXN")).thenReturn(Optional.of(stored));

            currencyService.deleteCurrency("MXN");

            verify(currencyRepository).delete(stored);
            verify(currencyRepository).flush();
        }

        @Test
        @DisplayName("the flush is what surfaces the FK refusal inside the call, not a later commit")
        void deleteCurrency_SurfacesTheIntegrityViolationAtTheFlush() {
            var stored = new Currency("MXN", (short) 2, "$", true);
            when(currencyRepository.findById("MXN")).thenReturn(Optional.of(stored));
            doThrow(new DataIntegrityViolationException("update or delete on table \"currency\" violates foreign "
                            + "key constraint \"companies_currency_fkey\""))
                    .when(currencyRepository)
                    .flush();

            assertThatThrownBy(() -> currencyService.deleteCurrency("MXN"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // The delete is what the flush judges, and the flush is where the refusal arrives: if the
            // statement were left to commit time, this call would return normally and the 409 would depend
            // on a transaction boundary no endpoint observes.
            verify(currencyRepository).delete(stored);
            verify(currencyRepository).flush();
        }

        @Test
        @DisplayName("a code the catalog does not carry is a 404 and nothing is deleted")
        void deleteCurrency_WithAnUnknownCode_Answers404AndDeletesNothing() {
            when(currencyRepository.findById("ZZZ")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> currencyService.deleteCurrency("ZZZ"))
                    .isInstanceOf(CurrencyNotFoundException.class)
                    .hasMessageContaining("ZZZ");

            verify(currencyRepository, never()).delete(any(Currency.class));
        }

        @Test
        @DisplayName("a code a foreign key still points at raises the refusal the 409 is built on")
        void deleteCurrency_WhenAForeignKeyStillPointsAtTheCode_PropagatesTheIntegrityViolation() {
            var stored = new Currency("MXN", (short) 2, "$", true);
            when(currencyRepository.findById("MXN")).thenReturn(Optional.of(stored));
            doThrow(new DataIntegrityViolationException(
                            "update or delete on table \"currency\" violates foreign key constraint "
                                    + "\"companies_currency_fkey\" on table \"companies\""))
                    .when(currencyRepository)
                    .delete(stored);

            assertThatThrownBy(() -> currencyService.deleteCurrency("MXN"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // A refused delete retires nothing: the row is exactly as the caller found it.
            assertThat(stored.getCode()).isEqualTo("MXN");
            assertThat(stored.getEnabled()).isTrue();
            verify(currencyRepository, never()).flush();
        }
    }

    // ─── PATCH /{code}/enable semantics ───────────────────────────

    @Nested
    @DisplayName("setCurrencyEnabled")
    class SetCurrencyEnabledTests {

        @Test
        @DisplayName("retires the code and keeps the row")
        void setCurrencyEnabled_RetiresTheCodeAndKeepsTheRow() {
            var stored = new Currency("MXN", (short) 2, "$", true);
            when(currencyRepository.findById("MXN")).thenReturn(Optional.of(stored));
            when(currencyRepository.save(any(Currency.class))).thenAnswer(invocation -> invocation.getArgument(0));

            var response = currencyService.setCurrencyEnabled("MXN", false);

            assertThat(response).isEqualTo(new CurrencyResponse("MXN", (short) 2, "$", false));
            assertThat(stored.getEnabled()).isFalse();
            verify(currencyRepository).save(stored);
        }

        @Test
        @DisplayName("brings a retired code back without touching the other columns")
        void setCurrencyEnabled_BringsARetiredCodeBack() {
            var stored = new Currency("MXN", (short) 2, "$", false);
            when(currencyRepository.findById("MXN")).thenReturn(Optional.of(stored));
            when(currencyRepository.save(any(Currency.class))).thenAnswer(invocation -> invocation.getArgument(0));

            var response = currencyService.setCurrencyEnabled("MXN", true);

            assertThat(response).isEqualTo(new CurrencyResponse("MXN", (short) 2, "$", true));
            assertThat(stored.getMinorUnits()).isEqualTo((short) 2);
            assertThat(stored.getSymbol()).isEqualTo("$");
        }

        @Test
        @DisplayName("a code the catalog does not carry is a 404 and nothing is written")
        void setCurrencyEnabled_WithAnUnknownCode_Answers404() {
            when(currencyRepository.findById("ZZZ")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> currencyService.setCurrencyEnabled("ZZZ", false))
                    .isInstanceOf(CurrencyNotFoundException.class)
                    .hasMessageContaining("ZZZ");

            verify(currencyRepository, never()).save(any(Currency.class));
        }
    }
}
