package com.lifecontrol.api.inventory.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/**
 * 404 when a store has never been configured with inventory settings.
 *
 * <p>This is a deliberate read contract, not an error condition: the Angular store inventory
 * -settings screen treats 404 as "not configured yet" and shows the configuration form.</p>
 */
public class StoreInventorySettingsNotFoundException extends ResourceNotFoundException {

    public StoreInventorySettingsNotFoundException(UUID companyStoreId) {
        super("Store inventory settings not found for store id: " + companyStoreId);
    }
}
