package com.lifecontrol.api.usersadmin.dto;

import com.lifecontrol.api.usersadmin.identity.RoleScope;

public record RoleResponse(
    String name,
    String description,
    Boolean composite,
    RoleScope scope,
    String clientId
) {}
