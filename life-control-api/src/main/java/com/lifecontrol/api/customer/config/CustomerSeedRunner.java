package com.lifecontrol.api.customer.config;

import com.lifecontrol.api.customer.model.Customer;
import com.lifecontrol.api.customer.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CustomerSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CustomerSeedRunner.class);

    /**
     * Fixed UUID for the default "Cliente General" customer.
     * Using id=1 convention: {@code 00000000-0000-0000-0000-000000000001}
     */
    private static final UUID CLIENTE_GENERAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String CLIENTE_GENERAL_NAME = "Cliente General";
    private static final String CLIENTE_GENERAL_CHANNEL = "TODOS";

    private final CustomerRepository customerRepository;

    public CustomerSeedRunner(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (customerRepository.existsById(CLIENTE_GENERAL_ID)) {
            log.debug("Cliente General already exists, skipping seed");
            return;
        }

        try {
            var customer = Customer.builder()
                    .id(CLIENTE_GENERAL_ID)
                    .name(CLIENTE_GENERAL_NAME)
                    .salesChannel(CLIENTE_GENERAL_CHANNEL)
                    .enabled(true)
                    .build();

            customerRepository.saveAndFlush(customer);
            log.info("Seeded Cliente General with id={}", CLIENTE_GENERAL_ID);
        } catch (Exception e) {
            log.warn("Cliente General already exists (race condition or pre-existing), skipping seed: {}", e.getMessage());
        }
    }
}
