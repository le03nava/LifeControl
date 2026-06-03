package com.lifecontrol.api.paymentmethod.config;

import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class PaymentMethodInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PaymentMethodInitializer.class);

    private record SeedMethod(String name, String shortName) {}

    private static final List<SeedMethod> SEED_METHODS = List.of(
        new SeedMethod("Efectivo", "EFECTIVO"),
        new SeedMethod("Tarjeta Crédito", "TC"),
        new SeedMethod("Tarjeta Débito", "TD"),
        new SeedMethod("Transferencia", "TRANSFERENCIA")
    );

    private final PaymentMethodRepository repository;

    public PaymentMethodInitializer(PaymentMethodRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (var seed : SEED_METHODS) {
            if (repository.findByPaymentMethodNameIgnoreCase(seed.name()).isEmpty()) {
                var paymentMethod = PaymentMethod.builder()
                        .paymentMethodName(seed.name())
                        .paymentMethodShortName(seed.shortName())
                        .enabled(true)
                        .build();
                repository.save(paymentMethod);
                log.debug("Seeded payment method: {}", seed.name());
            }
        }
    }
}
