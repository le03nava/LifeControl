package com.lifecontrol.api.config.invitation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the account <b>invitation</b> — the redirect the person returns to
 * and how long the emailed link stays valid.
 *
 * <p>Prefix: {@code app.invitation}. The application client id is deliberately <b>not</b> here: it is
 * already bound by {@code config.security.ApplicationClientProperties} ({@code keycloak.app.client-id},
 * record T30), and the invitation reads that binding rather than duplicating it.</p>
 *
 * <p>Supplied by {@code application.properties} as
 * {@code app.invitation.redirect-uri=${WEB_APP_URL:http://localhost:4200}} and
 * {@code app.invitation.lifespan-seconds=${INVITATION_LIFESPAN_SECONDS:43200}}. The redirect is
 * <b>mandatory</b> material at the identity provider (record T21): a call without it leaves the person
 * who just set their password with no link back into the application.</p>
 *
 * @param redirectUri the public origin the invitation flow returns to (the app's own origin, not
 *     Keycloak's — see record T26)
 * @param lifespanSeconds how long the emailed action link stays valid, in seconds
 */
@ConfigurationProperties(prefix = "app.invitation")
public record InvitationProperties(String redirectUri, Integer lifespanSeconds) {}
