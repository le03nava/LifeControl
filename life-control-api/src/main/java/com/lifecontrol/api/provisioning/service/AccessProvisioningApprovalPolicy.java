package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.common.security.ScopeLevel;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The <b>approval rule</b> of the employee-access gate (decision O2): a task whose whole role diff
 * stays inside the store-scoped auto-apply set is created
 * {@link AccessProvisioningTaskStatus#PENDING}, while any diff that touches a role outside that set
 * is created {@link AccessProvisioningTaskStatus#APPROVAL_PENDING}.
 *
 * <p>The auto-apply set is {@link ScopeLevel#STORE}'s own {@link ScopeLevel#roleNames()} — the roles
 * that are meaningless without a store assignment and are the operational set — derived <b>once</b>
 * into {@link #AUTO_APPLY_ROLE_NAMES} rather than re-read per call or copied as a hand-written
 * literal list. Deriving it from the scope registry is deliberately the opposite of a list: a list
 * would rot, and the registry is the authority the rule points at.</p>
 *
 * <p><b>The direction of a role does not matter.</b> The caller passes the role <em>names</em> the
 * diff touches, whether they are being granted or revoked: a role outside the auto-apply set that is
 * being <em>removed</em> still needs a second pair of eyes, because removing a company-scoped role is
 * as consequential as granting it. An <b>empty</b> touched set is {@code PENDING}: nothing outside
 * the auto-apply set is being touched.</p>
 *
 * <p>It has no collaborators, so a unit test drives it as a plain object.</p>
 */
@Component
public class AccessProvisioningApprovalPolicy {

    /**
     * The roles whose grants and revocations the flow applies itself, derived once from
     * {@link ScopeLevel#STORE}'s registry. Every other role — the company-scoped catalogs, the HR
     * pair, anything broader — requires approval.
     */
    private static final Set<String> AUTO_APPLY_ROLE_NAMES = Set.copyOf(ScopeLevel.STORE.roleNames());

    /**
     * The initial status for a task whose diff touches {@code touchedRoleNames}.
     *
     * @param touchedRoleNames the role names the diff touches, grants and revocations together; an
     *     empty set means nothing outside the auto-apply set is touched
     * @return {@code PENDING} when every touched name is in the auto-apply set,
     *     {@code APPROVAL_PENDING} otherwise
     */
    public AccessProvisioningTaskStatus initialStatusFor(Set<String> touchedRoleNames) {
        return AUTO_APPLY_ROLE_NAMES.containsAll(touchedRoleNames)
                ? AccessProvisioningTaskStatus.PENDING
                : AccessProvisioningTaskStatus.APPROVAL_PENDING;
    }
}
