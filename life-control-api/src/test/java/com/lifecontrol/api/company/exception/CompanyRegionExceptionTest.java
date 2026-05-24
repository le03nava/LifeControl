package com.lifecontrol.api.company.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CompanyRegion Exception Tests")
class CompanyRegionExceptionTest {

    @Test
    @DisplayName("CompanyRegionNotFoundException should have correct message")
    void companyRegionNotFoundException_Message() {
        UUID id = UUID.randomUUID();
        var exception = new CompanyRegionNotFoundException("Company region not found with id: " + id);
        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.getMessage()).isEqualTo("Company region not found with id: " + id);
    }

    @Test
    @DisplayName("DuplicateCompanyRegionException should have correct message")
    void duplicateCompanyRegionException_Message() {
        var exception = new DuplicateCompanyRegionException(
                "Company region with code 'NORTE' already exists for this country");
        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.getMessage())
                .isEqualTo("Company region with code 'NORTE' already exists for this country");
    }
}
