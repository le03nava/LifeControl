package com.lifecontrol.api.usersadmin.dto;

import com.lifecontrol.api.usersadmin.identity.RoleScope;

public record ChildRoleRequest(
    String childRole,
    RoleScope scope,
    String clientId
) {}
