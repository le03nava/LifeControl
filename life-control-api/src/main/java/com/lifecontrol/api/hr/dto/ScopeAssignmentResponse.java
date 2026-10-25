package com.lifecontrol.api.hr.dto;

import com.lifecontrol.api.common.security.ScopeLevel;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read model of one row of an employee's per-level scope-assignment history, plus the derived chain
 * it will put in the token.
 *
 * <p>It carries what the employee detail's assignment section renders without a second read: the
 * assignment id, <b>which level</b> it assigns ({@link ScopeLevel#COUNTRY},
 * {@link ScopeLevel#REGION}, {@link ScopeLevel#ZONE} or {@link ScopeLevel#STORE}, derived from which
 * node column is set and never stored), the four nullable node ids, the node's name, the validity
 * range and the soft-delete flag. The level is the {@code ScopeLevel} value itself rather than a new
 * enum, so the response names the same level the claim is declared under.</p>
 *
 * <p>There is no {@code COMPANY} level in the response because there is no company row: the company
 * is the employee's own fact and the section renders it as the root of the hierarchy.</p>
 *
 * <p>{@code validTo} is the <b>last day covered</b> (inclusive), the same convention as the close
 * request and {@code employees.termination_date}, and {@code null} for an open-ended assignment —
 * which is <b>not</b> the same fact as "current". The column stores the exclusive bound (the first day
 * NOT covered), so the service subtracts one day at the DTO boundary and the value round-trips
 * symmetrically with {@link CloseScopeAssignmentRequest}. {@code enabled} is exposed because the read
 * returns disabled rows too: the history is the whole record.</p>
 *
 * <p>{@link DerivedScopeChain} is the ancestor chain of the assigned node, which is what the token
 * will carry and what the operator should see before trusting it: ids <b>and</b> names, because "the
 * operator should see what they are granting" is useless as bare UUIDs. Each level is nullable: a
 * country row resolves company and country and leaves region and zone null, a region row adds its
 * region, a zone row adds all three. The names come from the entities already loaded to walk the
 * chain, and a component that is not reachable is omitted and logged, never fetched with a second
 * query. The chain is the same four levels for every row — a store row carries the zone, region and
 * country of its store — because that is what the token will carry. Unlike the derivation that feeds the claims, this display mapping resolves the chain
 * <b>without</b> any enabled filter: the operator must see the tree as it is.</p>
 */
public record ScopeAssignmentResponse(
        UUID id,
        ScopeLevel level,
        UUID companyCountryId,
        UUID companyRegionId,
        UUID companyZoneId,
        UUID companyStoreId,
        String nodeName,
        LocalDate validFrom,
        // The last day covered (inclusive), converted from the column's exclusive bound. Null means
        // open-ended.
        LocalDate validTo,
        Boolean enabled,
        DerivedScopeChain derived) {

    /** The ancestor chain of the assigned node, as the token will carry it. */
    public record DerivedScopeChain(
            UUID companyId,
            String companyName,
            UUID companyCountryId,
            String companyCountryName,
            UUID companyRegionId,
            String companyRegionName,
            UUID companyZoneId,
            String companyZoneName) {}
}
