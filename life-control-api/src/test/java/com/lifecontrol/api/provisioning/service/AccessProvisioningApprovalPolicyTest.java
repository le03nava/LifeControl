package com.lifecontrol.api.provisioning.service;

import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.APPROVAL_PENDING;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.common.security.ScopeLevel;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for {@link AccessProvisioningApprovalPolicy}: O2's rule over the store-scoped
 * auto-apply set, plus the pin that keeps the set reading {@link ScopeLevel#STORE} today so a change
 * to the scope registry fails a test instead of silently moving the gate.
 */
@DisplayName("AccessProvisioningApprovalPolicy Tests")
class AccessProvisioningApprovalPolicyTest {

    private static final List<String> STORE_ROLE_NAMES = List.of(
            "lc-company-store",
            "lc-company-store-read",
            "lc-receiving",
            "lc-sales",
            "lc-scheduling",
            "lc-scheduling-read");

    private final AccessProvisioningApprovalPolicy policy = new AccessProvisioningApprovalPolicy();

    @Test
    @DisplayName("an empty touched set is PENDING: nothing risky is being touched")
    void emptySetIsPending() {
        assertThat(policy.initialStatusFor(Set.of())).isEqualTo(PENDING);
    }

    @Test
    @DisplayName("every store-scoped role name together is PENDING")
    void allAutoApplyRolesArePending() {
        assertThat(policy.initialStatusFor(Set.copyOf(STORE_ROLE_NAMES))).isEqualTo(PENDING);
    }

    @Test
    @DisplayName("each store-scoped role plus a non-auto-apply role is APPROVAL_PENDING")
    void autoApplyRolePlusAnotherIsApprovalPending() {
        for (var storeRole : STORE_ROLE_NAMES) {
            assertThat(policy.initialStatusFor(Set.of(storeRole, "lc-employee")))
                    .as("touching %s next to lc-employee", storeRole)
                    .isEqualTo(APPROVAL_PENDING);
        }
    }

    @Test
    @DisplayName("a single non-auto-apply role is APPROVAL_PENDING")
    void singleNonAutoApplyRoleIsApprovalPending() {
        assertThat(policy.initialStatusFor(Set.of("lc-company"))).isEqualTo(APPROVAL_PENDING);
    }

    @Test
    @DisplayName("the auto-apply set is exactly the six names ScopeLevel.STORE answers today")
    void autoApplySetIsPinnedToStoreScope() {
        assertThat(ScopeLevel.STORE.roleNames())
                .as("a change here must fail this test instead of silently moving the gate")
                .containsExactlyInAnyOrderElementsOf(STORE_ROLE_NAMES);
        for (var storeRole : STORE_ROLE_NAMES) {
            assertThat(policy.initialStatusFor(Set.of(storeRole)))
                    .as("%s applies itself", storeRole)
                    .isEqualTo(PENDING);
        }
    }
}
