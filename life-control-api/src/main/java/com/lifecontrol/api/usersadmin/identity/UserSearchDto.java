package com.lifecontrol.api.usersadmin.identity;

public record UserSearchDto(
    String id,
    String username,
    String email,
    Boolean enabled
) {}
