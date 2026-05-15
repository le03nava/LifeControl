package com.lifecontrol.api.usersadmin.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record RoleRequest(
    @NotBlank(message = "Role name is required")
    String name,
    String description,
    Boolean composite,
    List<String> childRoles
) {}
