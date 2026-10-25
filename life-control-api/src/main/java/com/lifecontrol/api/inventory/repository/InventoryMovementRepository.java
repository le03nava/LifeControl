package com.lifecontrol.api.inventory.repository;

import com.lifecontrol.api.inventory.model.InventoryMovement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Append-only access to the inventory ledger.
 *
 * <p>This repository intentionally stays a plain {@link JpaRepository}: it declares no update and
 * no delete query of its own, because {@code inventory_movements} is the append-only ledger of the
 * inventory model. The only write path a receipt uses is {@link #save}, which inserts one row; no
 * code in the module mutates or removes a movement afterwards. (Test teardown uses the inherited
 * {@code deleteAll} to clean up fixtures — production code never does.)</p>
 */
@Repository
public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, UUID> {

    /**
     * Every ledger row written for one source reference, used by the sale reversal to read what a
     * sale took from each location instead of re-running the allocation (W3-D6). It is a read-only
     * query: the append-only contract and the surface assertion in
     * {@code InventoryMovementRepositorySurfaceTest} stay intact.
     */
    List<InventoryMovement> findByReferenceTypeAndReferenceId(String referenceType, UUID referenceId);

    /**
     * Whether the ledger holds any movement for one product variant, used by the measure-unit guard of
     * decision D3.
     *
     * <p>Once a movement exists, the quantity it recorded was expressed in whatever unit the variant
     * carried at that moment, so the unit can no longer be swapped for another: the ledger is a fact
     * log and rewriting its meaning is not a correction, it is a silent reinterpretation. It is a
     * read-only derived count, so the append-only contract and the surface assertion in
     * {@code InventoryMovementRepositorySurfaceTest} stay intact.</p>
     */
    boolean existsByProductVariantId(UUID productVariantId);
}
