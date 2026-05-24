package com.lifecontrol.api.company.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CompanyCreatedEvent Tests")
class CompanyCreatedEventTest {

    @Test
    @DisplayName("should extend ApplicationEvent")
    void shouldExtendApplicationEvent() {
        var event = new CompanyCreatedEvent(new Object(), UUID.randomUUID(), "1", "Test Corp");
        assertThat(event).isInstanceOf(ApplicationEvent.class);
    }

    @Test
    @DisplayName("should store all fields via constructor")
    void shouldStoreAllFields() {
        var source = new Object();
        var id = UUID.randomUUID();
        var companyKey = "KEY-001";
        var companyName = "Acme Corp";

        var event = new CompanyCreatedEvent(source, id, companyKey, companyName);

        assertThat(event.getSource()).isSameAs(source);
        assertThat(event.getId()).isEqualTo(id);
        assertThat(event.getCompanyKey()).isEqualTo(companyKey);
        assertThat(event.getCompanyName()).isEqualTo(companyName);
    }


}
