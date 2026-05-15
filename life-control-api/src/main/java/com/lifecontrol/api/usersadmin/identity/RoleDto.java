package com.lifecontrol.api.usersadmin.identity;

public record RoleDto(
    String name,
    String description,
    Boolean composite,
    RoleScope scope,
    String clientId
) {}
