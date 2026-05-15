package com.lifecontrol.api.usersadmin.dto;

import java.util.List;

public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long total
) {}
