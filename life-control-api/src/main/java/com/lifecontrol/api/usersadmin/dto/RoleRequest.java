package com.lifecontrol.api.usersadmin.dto;

import java.util.List;

public record RoleRequest(
    String name,
    String description,
    Boolean composite,
    List<String> childRoles
) {}
