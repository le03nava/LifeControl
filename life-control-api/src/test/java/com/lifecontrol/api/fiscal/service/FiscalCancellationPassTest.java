package com.lifecontrol.api.fiscal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort;
import com.lifecontrol.api.fiscal.dto.FiscalCancellationReconciliation;
import com.lifecontrol.api.fiscal.model.FiscalDocument;
import com.lifecontrol.api.fiscal.repository.FiscalCancellationTenantResolver;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The pass's own control flow, without a database: that it states a tenant before it reads, that it works every
 * tenant it was given, and that it survives one tenant failing — {@code S1}, {@code S3} and the containment claim of
 * {@code odd/tasks/fiscal-cancellation-cadence.md}.
 *
 * <p><b>Why this is a unit suite when the pass already has an integration one.</b> The integration suite runs inside
 * one test transaction, and {@code TenantScope} is installed at a transaction <b>boundary</b>: nested calls join the
 * open transaction, so the scope the pass states never reaches the connection there and deleting the wrapper would
 * leave every case green. That is not a detail — it is the difference between a pass that finds its work in
 * production and one that reports an idle system — so the wiring is pinned <b>here</b>, where the assertion is about
 * what the pass does (state the tenant, then read inside it) instead of about what PostgreSQL then does with it. What
 * the database does at the boundary is the transaction manager's contract, and the tenant suites already cover it.</p>
 *
 * <p><b>What this suite does not claim.</b> It says nothing about row-level security, the authority's answers or the
 * settlement — those are the integration suite's. It asserts the seam between them.</p>
 */
class FiscalCancellationPassTest {

    private final FiscalCancellationTenantResolver resolver = mock(FiscalCancellationTenantResolver.class);

    private final FiscalDocumentRepository repository = mock(FiscalDocumentRepository.class);

    private final FiscalCancellationReconciliationService reconciliationService =
            mock(FiscalCancellationReconciliationService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<FiscalCancellationReconciliationPort> port = mock(ObjectProvider.class);

    private final FiscalCancellationPass pass =
            new FiscalCancellationPass(resolver, repository, reconciliationService, port);

    @AfterEach
    void clearTheScope() {
        TenantScope.clear();
    }

    @Test
    @DisplayName("S1/S3: every tenant is stated before its candidates are read, and the thread carries no scope after")
    void statesTheTenantBeforeItReads() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(resolver.findCompaniesWithPendingCancellations()).thenReturn(List.of(first, second));
        when(port.getIfAvailable()).thenReturn(mock(FiscalCancellationReconciliationPort.class));

        List<UUID> scopesObservedWhileReading = new ArrayList<>();
        when(repository.findByCompanyIdAndStatusAndCancellationSettledAtIsNull(any(), eq("CANCELLATION_PENDING")))
                .thenAnswer(invocation -> {
                    scopesObservedWhileReading.add(TenantScope.current()
                            .map(TenantScope.Scope::companyId)
                            .orElse(null));
                    return List.of();
                });

        pass.runPass();

        assertThat(scopesObservedWhileReading)
                .as("the read happens inside the tenant's scope, one tenant at a time")
                .containsExactly(first, second);
        assertThat(TenantScope.current())
                .as("whoever states the scope clears it, so a reused thread starts empty")
                .isEmpty();
    }

    @Test
    @DisplayName("S1: a tenant that cannot be worked is abandoned without stopping the others")
    void keepsGoingWhenOneTenantFails() {
        UUID broken = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(resolver.findCompaniesWithPendingCancellations()).thenReturn(List.of(broken, healthy));
        when(port.getIfAvailable()).thenReturn(mock(FiscalCancellationReconciliationPort.class));

        FiscalDocument candidate = mock(FiscalDocument.class);
        UUID pointed = UUID.randomUUID();
        UUID cancellationId = UUID.randomUUID();
        when(candidate.getId()).thenReturn(cancellationId);
        when(candidate.getCancelsFiscalDocumentId()).thenReturn(pointed);

        when(repository.findByCompanyIdAndStatusAndCancellationSettledAtIsNull(eq(broken), any()))
                .thenThrow(new IllegalStateException("the candidate read failed"));
        when(repository.findByCompanyIdAndStatusAndCancellationSettledAtIsNull(eq(healthy), any()))
                .thenReturn(List.of(candidate));
        when(reconciliationService.reconcileAsSystem(healthy, pointed))
                .thenReturn(new FiscalCancellationReconciliation(pointed, "CANCELLED", "200", "accepted", true));

        var report = pass.runPass();

        assertThat(report.companies()).isEqualTo(2);
        assertThat(report.abandonedTenants()).isEqualTo(1);
        assertThat(report.documents()).isEqualTo(1);
        assertThat(report.settled()).isEqualTo(1);
    }

    @Test
    @DisplayName("S4/S5: an answer that settles nothing is counted as waiting, not as work done")
    void countsAnUnsettledAnswerAsWaiting() {
        UUID company = UUID.randomUUID();
        when(resolver.findCompaniesWithPendingCancellations()).thenReturn(List.of(company));
        when(port.getIfAvailable()).thenReturn(mock(FiscalCancellationReconciliationPort.class));

        FiscalDocument candidate = mock(FiscalDocument.class);
        UUID pointed = UUID.randomUUID();
        when(candidate.getCancelsFiscalDocumentId()).thenReturn(pointed);
        when(repository.findByCompanyIdAndStatusAndCancellationSettledAtIsNull(eq(company), any()))
                .thenReturn(List.of(candidate));
        when(reconciliationService.reconcileAsSystem(company, pointed))
                .thenReturn(
                        new FiscalCancellationReconciliation(pointed, "CANCELLATION_PENDING", "211", "pending", false));

        var report = pass.runPass();

        assertThat(report.waiting()).isEqualTo(1);
        assertThat(report.settled()).isZero();
    }

    @Test
    @DisplayName("S6: a deployment with no reconciling port does not enumerate and does not pretend to work")
    void doesNothingWithoutAReconciler() {
        when(port.getIfAvailable()).thenReturn(null);

        var report = pass.runPass();

        assertThat(report.skipped()).isTrue();
        assertThat(report.companies()).isZero();
        verify(resolver, never()).findCompaniesWithPendingCancellations();
        verifyNoInteractions(repository);
        verifyNoInteractions(reconciliationService);
    }
}
