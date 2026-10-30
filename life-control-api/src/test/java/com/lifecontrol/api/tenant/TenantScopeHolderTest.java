package com.lifecontrol.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.common.tenant.TenantScope;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The holder's own contract: what it carries, who ends it, and the one failure mode a {@code ThreadLocal}
 * has — a value surviving on a thread that is handed to the next operation.
 *
 * <p>This suite is plain Java on purpose. Everything else in {@code com.lifecontrol.api.tenant} needs
 * PostgreSQL to mean anything (Row-Level Security only exists there), but the holder is the piece whose
 * bugs are <b>not</b> database bugs: a scope that is never cleared does not fail a query, it silently
 * serves one tenant's rows to the next caller on that thread. That is what these cases measure.</p>
 */
@DisplayName("TenantScope holder")
class TenantScopeHolderTest {

    private static final UUID COMPANY_A = UUID.fromString("0a000000-0000-4000-8000-00000000000a");
    private static final UUID COMPANY_B = UUID.fromString("0b000000-0000-4000-8000-00000000000b");

    @AfterEach
    void clearTheThread() {
        // The holder is static and thread-confined: a test that leaves it set would hand the next test on
        // this thread a tenant it never stated, which is the very defect the suite is about.
        TenantScope.clear();
    }

    @Test
    @DisplayName("a thread that stated nothing carries no scope")
    void aThreadWithNoScopeIsEmpty() {
        assertThat(TenantScope.current()).isEmpty();
    }

    @Test
    @DisplayName("set states both facts and clear ends them")
    void setAndClearCarryBothFacts() {
        TenantScope.set(COMPANY_A, "kc-subject-a");

        assertThat(TenantScope.current())
                .as("one fact per setting ADR-0005 installs: the tenant and the subject")
                .hasValueSatisfying(scope -> {
                    assertThat(scope.companyId()).isEqualTo(COMPANY_A);
                    assertThat(scope.subject()).isEqualTo("kc-subject-a");
                });

        TenantScope.clear();

        assertThat(TenantScope.current()).isEmpty();
    }

    @Test
    @DisplayName("with states the company for the work and clears it afterwards")
    void withStatesTheCompanyForTheWorkOnly() {
        var observed = TenantScope.with(COMPANY_A, () -> TenantScope.current().orElseThrow());

        assertThat(observed.companyId())
                .as("the work runs inside the scope it was given")
                .isEqualTo(COMPANY_A);
        assertThat(observed.subject())
                .as("a platform thread has no caller, so it states no subject")
                .isNull();
        assertThat(TenantScope.current())
                .as("and the scope dies with the work, so a reused thread starts empty")
                .isEmpty();
    }

    @Test
    @DisplayName("with clears the scope even when the work throws")
    void withClearsTheScopeWhenTheWorkThrows() {
        assertThatThrownBy(() -> TenantScope.with(COMPANY_A, () -> {
                    throw new IllegalStateException("the work failed");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(TenantScope.current())
                .as("a failing task must not leave its tenant on the worker's thread")
                .isEmpty();
    }

    @Test
    @DisplayName("a nested with restores the outer scope instead of erasing it")
    void aNestedWithRestoresTheOuterScope() {
        TenantScope.set(COMPANY_A, "kc-subject-a");

        var inside = TenantScope.with(COMPANY_B, () -> TenantScope.current().orElseThrow());

        assertThat(inside.companyId()).isEqualTo(COMPANY_B);
        assertThat(TenantScope.current())
                .as("the inner call narrows the scope for its own work; the request's scope is still the "
                        + "one the rest of that request runs in")
                .hasValueSatisfying(scope -> {
                    assertThat(scope.companyId()).isEqualTo(COMPANY_A);
                    assertThat(scope.subject()).isEqualTo("kc-subject-a");
                });
    }

    @Test
    @DisplayName("withSubject states the subject for the work and clears it afterwards")
    void withSubjectStatesTheSubjectForTheWorkOnly() {
        var observed = TenantScope.withSubject(
                "kc-subject-new", () -> TenantScope.current().orElseThrow());

        assertThat(observed.subject())
                .as("the insert that runs inside states the subject it writes, which is the whole point of "
                        + "the helper: the subject did not exist when the boundary was entered")
                .isEqualTo("kc-subject-new");
        assertThat(observed.companyId())
                .as("a subject row belongs to a person, not to a tenant, so no company is stated")
                .isNull();
        assertThat(TenantScope.current()).as("and the scope dies with the work").isEmpty();
    }

    @Test
    @DisplayName("with(null, ...) runs the work without a company, and fails closed that way")
    void withANullCompanyInstallsNoCompanyScope() {
        var observed = new AtomicReference<TenantScope.Scope>();
        TenantScope.with(null, () -> {
            observed.set(TenantScope.current().orElseThrow());
            return null;
        });

        assertThat(observed.get().companyId())
                .as("a tenant that could not be determined is not widened to a working scope: the "
                        + "transaction then installs nothing and the policy filters every row")
                .isNull();
    }
}
