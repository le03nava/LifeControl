package com.lifecontrol.api.inventory.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/**
 * 404 when a location addressed as a store's receiving or sales location does not belong to that
 * store.
 *
 * <p>Mirrors {@code com.lifecontrol.api.product.exception.ProductVariantNotFoundException}: a thin
 * {@link ResourceNotFoundException} alias, so {@code GlobalExceptionHandler} resolves it by
 * inheritance as 404. A child referencing an unknown parent answers 404 in this codebase (precedent
 * {@code StoreZoneIntegrationTest.createZone_UnknownAreaReturns404}), and this is the same case: the
 * location exists but is not a child of the addressed store.</p>
 */
public class StoreLocationNotInStoreException extends ResourceNotFoundException {

    public StoreLocationNotInStoreException(UUID id) {
        super("Store location not found with id: " + id + " in the requested store");
    }
}
