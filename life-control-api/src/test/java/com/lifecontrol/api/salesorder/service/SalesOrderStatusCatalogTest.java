package com.lifecontrol.api.salesorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.state.DocumentStateCatalog;
import com.lifecontrol.api.salesorder.model.SalesOrderItemState;
import com.lifecontrol.api.salesorder.model.SalesOrderState;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The sales module's two names for the shared resolution (ADR-0004 decision 7).
 *
 * <p><b>This class is a delegate and the test says so.</b> The resolution itself — the four refusals and the
 * 400-versus-404 split — moved to {@link DocumentStateCatalog} when the purchase module needed the same four
 * answers, and {@code DocumentStateCatalogTest} is where each refusal is asserted by its message. What is
 * left here is the pair of names the sales service and the sales controller are written against, so this
 * test pins exactly that: the right family and the right enum reach the shared class, and a refusal from it
 * arrives unchanged instead of being swallowed by the delegation.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SalesOrderStatusCatalog")
class SalesOrderStatusCatalogTest {

    @Mock
    private StatusRepository statusRepository;

    private SalesOrderStatusCatalog catalog;

    private UUID statusId;

    @BeforeEach
    void setUp() {
        // The real shared catalog over a mocked read: a mocked delegate would prove the call was made and
        // nothing about what the module gets back.
        catalog = new SalesOrderStatusCatalog(new DocumentStateCatalog(statusRepository));
        statusId = UUID.randomUUID();
    }

    @Test
    @DisplayName("resolves the order's code into the order's enum")
    void resolvesTheOrdersCodeIntoTheEnum() {
        whenCatalogRow("Draft", "DRAFT", "SALES_ORDER");

        assertThat(catalog.requireOrderState(statusId)).isEqualTo(SalesOrderState.DRAFT);
    }

    @Test
    @DisplayName("resolves a line's code into the line's enum, which is not the order's")
    void resolvesTheLinesCodeIntoTheEnum() {
        whenCatalogRow("Added", "ADDED", "SALES_ORDER_ITEM");

        assertThat(catalog.requireItemState(statusId))
                .as("the family is part of the question: the same code belongs to two enums in this module")
                .isEqualTo(SalesOrderItemState.ADDED);
    }

    @Test
    @DisplayName("passes a refusal through unchanged instead of answering a state of its own")
    void passesARefusalThrough() {
        whenCatalogRow("Shipped", "SHIPPED", "SALES_ORDER");

        assertThatThrownBy(() -> catalog.requireOrderState(statusId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHIPPED")
                .hasMessageContaining("SalesOrderState");
    }

    private void whenCatalogRow(String name, String code, String typeName) {
        var status = Status.builder()
                .id(statusId)
                .statusName(name)
                .code(code)
                .statusType(StatusType.builder()
                        .id(UUID.randomUUID())
                        .statusTypeName(typeName)
                        .enabled(true)
                        .build())
                .enabled(true)
                .build();
        when(statusRepository.findWithStatusTypeById(statusId)).thenReturn(Optional.of(status));
    }
}
