package com.lifecontrol.api.salesorder.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record UpdateSalesOrderStatusRequest(

    @NotNull(message = "statusId is required")
    UUID statusId

) {}
