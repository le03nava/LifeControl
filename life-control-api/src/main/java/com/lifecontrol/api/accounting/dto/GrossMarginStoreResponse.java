package com.lifecontrol.api.accounting.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The gross margin of one store in one currency, summed over the documents of the range.
 *
 * <p><b>An unknown cost propagates to the whole bucket (S15).</b> A store whose documents include one with no
 * posted cost reports {@code cost} and {@code margin} as {@code null} and states how many documents caused it,
 * instead of a total that silently treats the missing cost as zero. A bucket total is a claim about every
 * document in it, so it can only be stated when every one of them is known.</p>
 *
 * <p>{@code documents} is the count behind the bucket, so a reader can tell a store with one large sale from a
 * store with fifty small ones without a second query.</p>
 */
public record GrossMarginStoreResponse(
        UUID storeId,
        String storeName,
        String currency,
        BigDecimal revenue,
        BigDecimal cost,
        BigDecimal margin,
        int documents,
        int unknownCostDocuments) {}
