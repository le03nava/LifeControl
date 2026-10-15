package com.lifecontrol.api.hr.dto;

import java.util.List;

/**
 * Read model of the roles a position template may grant.
 *
 * <p>It exposes the frozen allowlist of {@link com.lifecontrol.api.hr.service.GrantablePositionRoles}
 * (decisions D7 and D14) so the editor's candidate set has exactly <b>one</b> owner: the write-time
 * check and the checkboxes the operator sees cannot disagree, which a second copy of the fifteen
 * names in the frontend could not guarantee. It carries names only — every grantable role is a client
 * role of the application client, and the client id is configuration, not an attribute of a grantable
 * role — and the order is the allowlist's own sorted order, so the list is stable across requests.</p>
 */
public record PositionGrantableRolesResponse(List<String> roles) {}
