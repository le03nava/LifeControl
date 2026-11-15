package com.lifecontrol.api.config.fiscal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The fiscal module's configuration: one value today, the zone the document's date of issue is rendered in.
 *
 * <p>Prefix: {@code app.fiscal}. Supplied by {@code application.properties} as
 * {@code app.fiscal.timezone=${FISCAL_TIMEZONE:America/Mexico_City}}, the same shape every other properties
 * record here has — a value with a default in the properties file and no silent Java-side default.</p>
 *
 * <p><b>Why one zone for the whole deployment, and what that limit is.</b> The authority's {@code Fecha} carries
 * no offset — the XSD pattern forbids one and the guide says it is the local time where the document is issued
 * — so a stamp has to render an instant in <b>a</b> zone, and the tree has no company-level timezone to render it
 * in. This is that zone, and the limit is named instead of hidden (decision {@code L12}(c) of
 * {@code odd/tasks/cfdi-fiscal-document.md}): a company operating in another zone needs its own column, and this
 * slice does not add one. The property is what makes the day come without a code change.</p>
 *
 * @param timezone the {@code ZoneId} the attempt renders the document's {@code Fecha} in; validated at startup
 *     by {@link FiscalConfig}, so a misspelled zone fails the boot instead of a stamp
 */
@ConfigurationProperties(prefix = "app.fiscal")
public record FiscalProperties(String timezone) {}
