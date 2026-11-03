package com.lifecontrol.api.salesorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.salesorder.model.SalesOrderItemState;
import com.lifecontrol.api.salesorder.model.SalesOrderState;
import com.lifecontrol.api.status.exception.StatusNotFoundException;
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
 * The one place a request's {@code status_id} becomes a state (ADR-0004 decision 7): the catalog stopped
 * being the authority and kept its rows as labels, so the only thing the machine may read is
 * {@link Status#getCode()}.
 *
 * <p>The four refusals are the point of the class, and each one is asserted by its message rather than by its
 * type alone: a row of another family, a row with no code, a code the enum does not declare, and an id that
 * names no row at all. The first three answer {@code 400} — the caller sent a status the family cannot
 * become — and the last is the {@code 404} the catalog never had a row for.</p>
 *
 * <p>The repository is mocked with a row, not a database: what this class owns is the mapping and the
 * refusal, and both are decided after the one indexed read. The read itself is a derived query over
 * {@code statuses}, exercised end to end by {@code SalesOrderIntegrationTest} and by every head-booting
 * suite that resolves a status.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SalesOrderStatusCatalog")
class SalesOrderStatusCatalogTest {

    private static final String SALES_ORDER = "SALES_ORDER";

    private static final String SALES_ORDER_ITEM = "SALES_ORDER_ITEM";

    @Mock
    private StatusRepository statusRepository;

    private SalesOrderStatusCatalog catalog;

    private UUID statusId;

    @BeforeEach
    void setUp() {
        catalog = new SalesOrderStatusCatalog(statusRepository);
        statusId = UUID.randomUUID();
    }

    @Test
    @DisplayName("resolves the order's code into the enum, whatever the label says")
    void resolvesTheOrdersCodeIntoTheEnum() {
        whenCatalogRow("Draft", "DRAFT", SALES_ORDER);

        assertThat(catalog.requireOrderState(statusId))
                .as("the code is the constant, so a renamed label changes nothing about the state")
                .isEqualTo(SalesOrderState.DRAFT);
    }

    @Test
    @DisplayName("resolves a line's code into the line's enum")
    void resolvesTheLinesCodeIntoTheEnum() {
        whenCatalogRow("Added", "ADDED", SALES_ORDER_ITEM);

        assertThat(catalog.requireItemState(statusId)).isEqualTo(SalesOrderItemState.ADDED);
    }

    @Test
    @DisplayName("a renamed label still resolves: the code is what the machine reads")
    void aRenamedLabelStillResolves() {
        whenCatalogRow("Cancelada", "CANCELLED", SALES_ORDER);

        assertThat(catalog.requireOrderState(statusId))
                .as("ADR-0004 decision 7: renaming a catalog row is translation and cannot change a behavior")
                .isEqualTo(SalesOrderState.CANCELLED);
    }

    @Test
    @DisplayName("refuses a row of another family, and names both families")
    void refusesARowOfAnotherFamily() {
        whenCatalogRow("Pending", "PENDING", SALES_ORDER_ITEM);

        assertThatThrownBy(() -> catalog.requireOrderState(statusId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to type SALES_ORDER")
                .hasMessageContaining(SALES_ORDER_ITEM)
                .hasMessageContaining(statusId.toString());
    }

    @Test
    @DisplayName("refuses a row with no code, naming the catalog row and its id")
    void refusesARowWithNoCode() {
        whenCatalogRow("Registrado", null, SALES_ORDER);

        assertThatThrownBy(() -> catalog.requireOrderState(statusId))
                .as("an out-of-scope family or a row created at run time has no constant to move to")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Registrado")
                .hasMessageContaining(statusId.toString())
                .hasMessageContaining("carries no code");
    }

    @Test
    @DisplayName("refuses a code the enum does not declare, naming the row, the id and the code")
    void refusesACodeTheEnumDoesNotDeclare() {
        whenCatalogRow("Shipped", "SHIPPED", SALES_ORDER);

        assertThatThrownBy(() -> catalog.requireOrderState(statusId))
                .as("the database's CHECK and the enum are coupled; a catalog row that spells neither must not "
                        + "be turned into a state by guesswork")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Shipped")
                .hasMessageContaining(statusId.toString())
                .hasMessageContaining("SHIPPED")
                .hasMessageContaining("SalesOrderState");
    }

    @Test
    @DisplayName("refuses an id that names no catalog row")
    void refusesAnIdThatNamesNoRow() {
        when(statusRepository.findWithStatusTypeById(statusId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> catalog.requireOrderState(statusId))
                .isInstanceOf(StatusNotFoundException.class)
                .hasMessageContaining(statusId.toString());
    }

    @Test
    @DisplayName("refuses a missing id before reading the catalog")
    void refusesAMissingId() {
        assertThatThrownBy(() -> catalog.requireOrderState(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(SALES_ORDER);
    }

    // ─── the row the one read answers with ─────────────────────────────────────────────────────────

    /** One catalog row with its family, which is what {@code findWithStatusTypeById} returns. */
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
