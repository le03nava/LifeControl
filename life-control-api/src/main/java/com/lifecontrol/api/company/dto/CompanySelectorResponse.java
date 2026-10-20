package com.lifecontrol.api.company.dto;

import java.util.UUID;

/**
 * Read model of a company as a cascade selector needs it: an id, the key and a label.
 *
 * <p>It exists so the ~12 screens that fill their first combo with a company do not have to read
 * {@code GET /api/companies} to get it. That endpoint is the administration contract — it carries
 * {@code rfc}, {@code emailDomain}, the timestamps and the address — and it is gated on
 * {@code lc-admin}/{@code lc-company}/{@code lc-company-read}, so every narrower role, including all
 * four read-only pairs of the lower levels, could not populate its own cascade at all.</p>
 *
 * <p>The authorization of the endpoint that returns this type is the {@code company_id} <b>claim</b>,
 * not a role: the selector lists exactly the companies the caller already belongs to, which is the
 * same filter {@code CompanyService#getAllCompanies} applies to a non-admin caller. No {@code lc-*}
 * role is invented for it — a role here would be a grant an operator can forget, and its failure mode
 * (an empty dropdown) is indistinguishable from "there is no data".</p>
 *
 * <p>{@code companyKey} is carried because the consumer's search term <b>is</b> the key in some
 * screens; {@code companyName} is the label. Nothing else travels, deliberately: no fiscal data, no
 * corporate email domain, no enabled state. A caller that needs those reads the company itself, under
 * the role that admits it.</p>
 */
public record CompanySelectorResponse(UUID id, String companyKey, String companyName) {}
