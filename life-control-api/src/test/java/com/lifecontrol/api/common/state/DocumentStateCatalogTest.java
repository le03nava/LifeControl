package com.lifecontrol.api.common.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.purchaseorder.model.PurchaseOrderDetailState;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderState;
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
 * <p><b>Four refusals, and each one is asserted by its message rather than by its type alone</b>: a row of
 * another family, a row with no code, a code the enum does not declare, and an id that names no row at all.
 * The first three answer {@code 400} — the caller sent a status the family cannot become — and the last is
 * the {@code 404} the catalog never had a row for. Every document family shares them; the cases below use
 * two families on purpose, so a class that only ever worked for the family it was extracted from would fail
 * here.</p>
 *
 * <p>The repository is mocked with a row, not a database: what this class owns is the mapping and the
 * refusal, and both are decided after the one indexed read. The read itself is a derived query over
 * {@code statuses}, exercised end to end by the integration suites that resolve a status.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentStateCatalog")
class DocumentStateCatalogTest {

    private static final String SALES_ORDER = "SALES_ORDER";

    private static final String SALES_ORDER_ITEM = "SALES_ORDER_ITEM";

    private static final String PURCHASE_ORDER = "PURCHASE_ORDER";

    private static final String PURCHASE_ORDER_DETAIL = "PURCHASE_ORDER_DETAIL";

    @Mock
    private StatusRepository statusRepository;

    private DocumentStateCatalog catalog;

    private UUID statusId;

    @BeforeEach
    void setUp() {
        catalog = new DocumentStateCatalog(statusRepository);
        statusId = UUID.randomUUID();
    }

    @Test
    @DisplayName("resolves a sales order's code into its enum, whatever the label says")
    void resolvesTheSalesOrdersCodeIntoItsEnum() {
        whenCatalogRow("Draft", "DRAFT", SALES_ORDER);

        assertThat(catalog.requireState(StatusSubject.SALES_ORDER, statusId, SalesOrderState.class))
                .as("the code is the constant, so a renamed label changes nothing about the state")
                .isEqualTo(SalesOrderState.DRAFT);
    }

    @Test
    @DisplayName("resolves a sales-order line's code into the line's enum")
    void resolvesTheSalesOrderLinesCodeIntoItsEnum() {
        whenCatalogRow("Added", "ADDED", SALES_ORDER_ITEM);

        assertThat(catalog.requireState(StatusSubject.SALES_ORDER_ITEM, statusId, SalesOrderItemState.class))
                .isEqualTo(SalesOrderItemState.ADDED);
    }

    @Test
    @DisplayName("resolves a purchase order's code into its own enum: the class serves every family")
    void resolvesThePurchaseOrdersCodeIntoItsEnum() {
        whenCatalogRow("In Transit", "IN_TRANSIT", PURCHASE_ORDER);

        assertThat(catalog.requireState(StatusSubject.PURCHASE_ORDER, statusId, PurchaseOrderState.class))
                .as("a class that only worked for the family it was extracted from would fail here")
                .isEqualTo(PurchaseOrderState.IN_TRANSIT);
    }

    @Test
    @DisplayName("resolves a purchase-order line's code into its own enum, which is not the sales item set")
    void resolvesThePurchaseOrderDetailsCodeIntoItsEnum() {
        whenCatalogRow("Partial Received", "PARTIAL_RECEIVED", PURCHASE_ORDER_DETAIL);

        assertThat(catalog.requireState(StatusSubject.PURCHASE_ORDER_DETAIL, statusId, PurchaseOrderDetailState.class))
                .as("the detail machine has its own tail, and the family argument is what keeps the two apart")
                .isEqualTo(PurchaseOrderDetailState.PARTIAL_RECEIVED);
    }

    @Test
    @DisplayName("a renamed label still resolves: the code is what the machine reads")
    void aRenamedLabelStillResolves() {
        whenCatalogRow("Cancelada", "CANCELLED", SALES_ORDER);

        assertThat(catalog.requireState(StatusSubject.SALES_ORDER, statusId, SalesOrderState.class))
                .as("ADR-0004 decision 7: renaming a catalog row is translation and cannot change a behavior")
                .isEqualTo(SalesOrderState.CANCELLED);
    }

    @Test
    @DisplayName("refuses a row of another family, and names both families")
    void refusesARowOfAnotherFamily() {
        whenCatalogRow("Pending", "PENDING", PURCHASE_ORDER_DETAIL);

        assertThatThrownBy(() -> catalog.requireState(StatusSubject.PURCHASE_ORDER, statusId, PurchaseOrderState.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to type PURCHASE_ORDER")
                .hasMessageContaining(PURCHASE_ORDER_DETAIL)
                .hasMessageContaining(statusId.toString());
    }

    @Test
    @DisplayName("refuses a row with no code, naming the catalog row and its id")
    void refusesARowWithNoCode() {
        whenCatalogRow("Registrado", null, PURCHASE_ORDER);

        assertThatThrownBy(() -> catalog.requireState(StatusSubject.PURCHASE_ORDER, statusId, PurchaseOrderState.class))
                .as("an out-of-scope family or a row created at run time has no constant to move to")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Registrado")
                .hasMessageContaining(statusId.toString())
                .hasMessageContaining("carries no code");
    }

    @Test
    @DisplayName("refuses a code the enum does not declare, naming the row, the id and the code")
    void refusesACodeTheEnumDoesNotDeclare() {
        whenCatalogRow("Shipped", "SHIPPED", PURCHASE_ORDER);

        assertThatThrownBy(() -> catalog.requireState(StatusSubject.PURCHASE_ORDER, statusId, PurchaseOrderState.class))
                .as("the database's CHECK and the enum are coupled; a catalog row that spells neither must not "
                        + "be turned into a state by guesswork")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Shipped")
                .hasMessageContaining(statusId.toString())
                .hasMessageContaining("SHIPPED")
                .hasMessageContaining("PurchaseOrderState");
    }

    @Test
    @DisplayName("refuses an id that names no catalog row")
    void refusesAnIdThatNamesNoRow() {
        when(statusRepository.findWithStatusTypeById(statusId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> catalog.requireState(StatusSubject.PURCHASE_ORDER, statusId, PurchaseOrderState.class))
                .as("the row the caller named does not exist: that is a 404 and not a 400")
                .isInstanceOf(StatusNotFoundException.class)
                .hasMessageContaining(statusId.toString());
    }

    @Test
    @DisplayName("refuses a missing id before reading the catalog")
    void refusesAMissingId() {
        assertThatThrownBy(() -> catalog.requireState(StatusSubject.PURCHASE_ORDER, null, PurchaseOrderState.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PURCHASE_ORDER);
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
