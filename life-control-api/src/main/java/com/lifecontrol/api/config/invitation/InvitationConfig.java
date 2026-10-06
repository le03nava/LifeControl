package com.lifecontrol.api.config.invitation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables binding of {@link InvitationProperties}.
 *
 * <p>Mirrors {@code config.security.ApplicationClientConfig}: a {@code @Configuration} class placed
 * beside its properties record, registering it with {@code @EnableConfigurationProperties}. The
 * repository registers every properties record this way rather than through component scanning.</p>
 */
@Configuration
@EnableConfigurationProperties(InvitationProperties.class)
public class InvitationConfig {}
