package com.lifecontrol.api.config;

import com.lifecontrol.api.common.tenant.TenantScopeJpaTransactionManager;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Replaces the auto-configured JPA transaction manager with the one that installs ADR-0005's scope.
 *
 * <p><b>Why a bean definition and not a wrapper.</b> {@code spring-boot-jpa}'s
 * {@code JpaBaseConfiguration.transactionManager(...)} is declared
 * {@code @ConditionalOnMissingBean(TransactionManager.class)}, so this bean — registered by user
 * configuration, which is always processed before auto-configuration — suppresses it and becomes
 * <b>the</b> transaction manager of the application. Every {@code @Transactional} method, every
 * {@code TransactionTemplate} and every {@code JpaTransactionManager}-aware test therefore goes through
 * the boundary hook; a decorator around the auto-configured manager would leave the plain
 * {@code JpaTransactionManager} injectable, and the scope would depend on who injected what.</p>
 *
 * <p><b>The name is {@code transactionManager} on purpose</b>: it is the name the auto-configured bean
 * had, so anything resolving the manager by name keeps working, and Boot's single-candidate injection
 * points ({@code TransactionTemplate}, {@code @Transactional}) keep finding exactly one.</p>
 *
 * <p><b>The customizers are applied.</b> The replaced bean is the one Boot would have built, minus the
 * hook, so the same {@code TransactionManagerCustomizers} are applied to it: dropping them would silently
 * disable whatever a later {@code TransactionManagerCustomizer} contributes (Boot's own execution-listener
 * customizer among them), which is the kind of regression nobody attributes to a tenant-scope change.</p>
 */
@Configuration(proxyBeanMethods = false)
public class TenantScopeTransactionConfig {

    @Bean
    public PlatformTransactionManager transactionManager(
            EntityManagerFactory entityManagerFactory,
            DataSource dataSource,
            ObjectProvider<TransactionManagerCustomizers> customizers) {
        var transactionManager = new TenantScopeJpaTransactionManager(entityManagerFactory, dataSource);
        customizers.ifAvailable(customizer -> customizer.customize(transactionManager));
        return transactionManager;
    }
}
