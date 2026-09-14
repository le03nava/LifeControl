package com.lifecontrol.api.company.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Company Exception Tests")
class CompanyExceptionTest {

    @Test
    @DisplayName("CompanyNotFoundException should extend RuntimeException and contain UUID in message")
    void companyNotFoundException_Message() {
        UUID id = UUID.randomUUID();
        var exception = new CompanyNotFoundException(id);
        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.getMessage()).isEqualTo("Company not found with id: " + id);
    }

    @Test
    @DisplayName("CompanyNotFoundException should produce different messages for different UUIDs")
    void companyNotFoundException_DifferentIds() {
        UUID id1 = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID id2 = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

        var ex1 = new CompanyNotFoundException(id1);
        var ex2 = new CompanyNotFoundException(id2);

        assertThat(ex1.getMessage()).isNotEqualTo(ex2.getMessage());
        assertThat(ex1.getMessage()).contains(id1.toString());
        assertThat(ex2.getMessage()).contains(id2.toString());
    }

    @Test
    @DisplayName("CompanyCountryNotFoundException should extend RuntimeException and contain UUID in message")
    void companyCountryNotFoundException_Message() {
        UUID id = UUID.randomUUID();
        var exception = new CompanyCountryNotFoundException(id);
        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.getMessage()).isEqualTo("Company-country relation not found with id: " + id);
    }

    @Test
    @DisplayName("CompanyCountryNotFoundException should produce different messages for different UUIDs")
    void companyCountryNotFoundException_DifferentIds() {
        UUID id1 = UUID.fromString("550e8400-e29b-41d4-a716-446655440010");
        UUID id2 = UUID.fromString("550e8400-e29b-41d4-a716-446655440011");

        var ex1 = new CompanyCountryNotFoundException(id1);
        var ex2 = new CompanyCountryNotFoundException(id2);

        assertThat(ex1.getMessage()).isNotEqualTo(ex2.getMessage());
        assertThat(ex1.getMessage()).contains(id1.toString());
        assertThat(ex2.getMessage()).contains(id2.toString());
    }

    @Test
    @DisplayName("DuplicateCompanyCountryException should extend RuntimeException and compose country code")
    void duplicateCompanyCountryException_Message() {
        var exception = new DuplicateCompanyCountryException("MX");
        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.getMessage()).isEqualTo("The company already has a relationship with country: MX");
    }

    @Test
    @DisplayName("DuplicateCompanyCountryException should produce different messages for different country codes")
    void duplicateCompanyCountryException_DifferentCountryCodes() {
        var ex1 = new DuplicateCompanyCountryException("MX");
        var ex2 = new DuplicateCompanyCountryException("CO");

        assertThat(ex1.getMessage()).isNotEqualTo(ex2.getMessage());
        assertThat(ex1.getMessage()).contains("MX");
        assertThat(ex2.getMessage()).contains("CO");
    }

    @Test
    @DisplayName("CompanyRegionNotFoundException should have correct message")
    void companyRegionNotFoundException_Message() {
        UUID id = UUID.randomUUID();
        var exception = new CompanyRegionNotFoundException("Company region not found with id: " + id);
        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.getMessage()).isEqualTo("Company region not found with id: " + id);
    }

    @Test
    @DisplayName("CompanyZoneNotFoundException should have correct message")
    void companyZoneNotFoundException_Message() {
        UUID id = UUID.randomUUID();
        var exception = new CompanyZoneNotFoundException("Company zone not found with id: " + id);
        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.getMessage()).isEqualTo("Company zone not found with id: " + id);
    }

    @Test
    @DisplayName("DuplicateCompanyException should extend RuntimeException and preserve message")
    void duplicateCompanyException_Message() {
        var exception = new DuplicateCompanyException("Company tax id ABC-001 already exists");
        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.getMessage()).isEqualTo("Company tax id ABC-001 already exists");
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

    @Test
    @DisplayName("DuplicateCompanyZoneException should extend RuntimeException and preserve message")
    void duplicateCompanyZoneException_Message() {
        var exception = new DuplicateCompanyZoneException(
                "Company zone with code 'NORTE-Z1' already exists for this region");
        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.getMessage())
                .isEqualTo("Company zone with code 'NORTE-Z1' already exists for this region");
    }
}
