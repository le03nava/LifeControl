package com.lifecontrol.api.product.category.catalog;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.company.event.CompanyCreatedEvent;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit verification of {@link StarterProductCategoryListener}: it delegates to the applier for the
 * event's company, and it never lets a failure escape.
 *
 * <p>The contract that matters is the second one. The listener runs on
 * {@code @TransactionalEventListener(AFTER_COMMIT)}, so the company is already committed and there is
 * nothing left to abort: a rethrown exception would surface as a broken company creation for a
 * convenience seed. The test therefore asserts an absence — no throw — rather than any logging.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Starter Product Category Listener Tests")
class StarterProductCategoryListenerTest {

    private static final UUID COMPANY_ID = UUID.fromString("3f2a1c4d-0000-4000-8000-000000000001");
    private static final String COMPANY_KEY = "STARTER-LISTENER-KEY";

    @Mock
    private StarterProductCategoryApplier applier;

    private StarterProductCategoryListener listener;

    @BeforeEach
    void setUp() {
        listener = new StarterProductCategoryListener(applier);
    }

    @Test
    @DisplayName("applies the catalog to the company the event carries, exactly once")
    void appliesTheCatalogToTheEventCompany() {
        var event = new CompanyCreatedEvent(this, COMPANY_ID, COMPANY_KEY, "Listener Test Company");

        listener.onCompanyCreated(event);

        verify(applier, times(1)).applyTo(COMPANY_ID);
    }

    @Test
    @DisplayName("swallows a failure from the applier: the company is already committed")
    void swallowsFailuresFromTheApplier() {
        var event = new CompanyCreatedEvent(this, COMPANY_ID, COMPANY_KEY, "Listener Test Company");
        when(applier.applyTo(COMPANY_ID)).thenThrow(new IllegalStateException("catalog write failed"));

        assertThatCode(() -> listener.onCompanyCreated(event))
                .as("a failed seed must never reach the publisher of CompanyCreatedEvent")
                .doesNotThrowAnyException();
    }
}
