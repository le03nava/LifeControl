package com.lifecontrol.api.usersadmin.dto;

public record UserSearchResponse(
    String id,
    String username,
    String email,
    Boolean enabled
) {}
