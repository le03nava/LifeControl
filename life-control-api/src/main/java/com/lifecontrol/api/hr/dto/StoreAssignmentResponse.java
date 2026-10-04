package com.lifecontrol.api.hr.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read model of one row of an employee's store-assignment history, plus the derived scope it will
 * put in the token (decision T14).
 *
 * <p>It carries what the employee detail's <b>Tiendas asignadas</b> section renders without a second
 * read: the assignment id, the store <b>and its name</b>, the validity range and the soft-delete
 * flag. Naming the store is the departure from the id-only catalog convention the history view
 * forces: a row is shown as text, and resolving the name from the store endpoint would cost one call
 * per row.</p>
 *
 * <p>{@code validTo} is the <b>last day covered</b> (inclusive), the same convention as the close
 * request and {@code employees.termination_date}, and {@code null} for an open-ended assignment —
 * which is <b>not</b> the same fact as "current". The column stores the exclusive bound (the first
 * day NOT covered), so the service subtracts one day at the DTO boundary (decision D5) and the value
 * round-trips symmetrically with {@code CloseStoreAssignmentRequest}. {@code enabled} is exposed
 * because the read returns disabled rows too: the history is the whole record, and the client can
 * tell a soft-deleted assignment from a live one.</p>
 *
 * <p>{@link DerivedStoreScope} is the ancestor chain of the row's store, which is what the token will
 * carry and what the operator should see before trusting it: four ancestor ids <b>and</b> their
 * names, because "the operator should see what they are granting" is useless as four bare UUIDs.
 * The names come from the entities already loaded to walk the chain, and a component whose name is
 * not reachable in one hop is omitted and logged, never fetched with a second query. Unlike the
 * derivation that feeds the claims, this display mapping resolves the chain <b>without</b> T12's
 * enabled filter: the operator must see the tree as it is, including that a disabled store grants
 * nothing.</p>
 */
public record StoreAssignmentResponse(
        UUID id,
        UUID companyStoreId,
        String companyStoreName,
        LocalDate validFrom,
        // The last day covered (inclusive), converted from the column's exclusive bound (decision
        // D5). Null means open-ended.
        LocalDate validTo,
        Boolean enabled,
        DerivedStoreScope derived) {

    /** The ancestor chain of the assigned store, as the token will carry it. */
    public record DerivedStoreScope(
            UUID companyId,
            String companyName,
            UUID companyCountryId,
            String companyCountryName,
            UUID companyRegionId,
            String companyRegionName,
            UUID companyZoneId,
            String companyZoneName) {}
}
