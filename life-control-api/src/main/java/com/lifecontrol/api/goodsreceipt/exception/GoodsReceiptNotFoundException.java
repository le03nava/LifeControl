package com.lifecontrol.api.goodsreceipt.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/** A goods receipt looked up by id does not exist (or the caller cannot reach its store). */
public class GoodsReceiptNotFoundException extends ResourceNotFoundException {

    public GoodsReceiptNotFoundException(UUID id) {
        super("Goods receipt not found with id: " + id);
    }
}
