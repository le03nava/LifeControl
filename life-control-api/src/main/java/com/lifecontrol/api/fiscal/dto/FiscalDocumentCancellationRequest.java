package com.lifecontrol.api.fiscal.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * One document's cancellation as a caller asks for it: the authority's motive code and, when the motive replaces the
 * document, the folio that replaces it ({@code S7} of {@code odd/tasks/cfdi-fiscal-document.md}, decision
 * {@code L20}(f) of the same record).
 *
 * <p><b>Why the motive is a {@code String} and not {@link com.lifecontrol.api.fiscal.model.FiscalCancellationMotive}.</b>
 * The wire states the authority's two-character code, and a code is not a Java constant's name — the same reason
 * {@code FiscalCancellationMotive} stores codes rather than declaring {@code String} constants. Parsing it is
 * therefore the boundary's job ({@code FiscalCancellationMotive.fromCode}, which answers empty for an unknown code so
 * the refusal can name this field), and doing it here would make a request DTO throw a domain exception while Jackson
 * is still binding it.</p>
 *
 * <p><b>Why the folio is a {@code UUID} and not a string.</b> It is the fiscal UUID of the substituting document, and
 * the row it resolves to is found by {@code fiscal_document.uuid_fiscal}, which is a {@code UUID} column. Declaring it
 * as the type the query takes is what makes a malformed value a 400 at binding instead of a value that reaches the
 * lookup as text nobody validated.</p>
 *
 * <p><b>Why both fields are optional here and the rule is not.</b> The folio is optional for the three motives that
 * replace nothing and required by motive {@code 01} — a one-way rule the authority's schema states and its
 * counterpart does not — so it is enforced once, where it can name the attribute the operator has to fix
 * ({@code CancellationRequest}, {@code L20}(f)). This record carries the request's two values and no rule.</p>
 *
 * @param motive the authority's motive code, {@code 01} to {@code 04}; required, because a cancellation that states
 *     none is a request the authority refuses
 * @param substitutingFolio the fiscal UUID of the document that replaces this one, {@code FolioSustitucion}; required
 *     for motive {@code 01} and accepted for the rest
 */
public record FiscalDocumentCancellationRequest(@NotBlank String motive, UUID substitutingFolio) {}
