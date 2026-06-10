package com.lifecontrol.api.customer.service;

import com.lifecontrol.api.customer.dto.CustomerRequest;
import com.lifecontrol.api.customer.dto.CustomerResponse;
import com.lifecontrol.api.customer.exception.CustomerNotFoundException;
import com.lifecontrol.api.customer.model.Customer;
import com.lifecontrol.api.customer.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Service
public class CustomerService {

    private static final Logger logger = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Transactional(readOnly = true)
    public Page<CustomerResponse> getAllCustomers(Pageable pageable, String search) {
        Page<Customer> customers;

        if (StringUtils.hasText(search)) {
            customers = customerRepository.findBySearchTerm(search.trim(), pageable);
        } else {
            customers = customerRepository.findByEnabledTrueOrderByCreatedAtDesc(pageable);
        }

        return customers.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public CustomerResponse getCustomerById(UUID id) {
        var customer = customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));
        return toResponse(customer);
    }

    @Transactional
    public CustomerResponse createCustomer(CustomerRequest request) {
        logger.info("Creating customer: name={}, salesChannel={}", request.name(), request.salesChannel());

        var customer = Customer.builder()
                .name(request.name())
                .email(request.email())
                .phone(request.phone())
                .rfc(request.rfc())
                .salesChannel(request.salesChannel())
                .enabled(request.enabled())
                .build();

        var saved = customerRepository.save(customer);
        logger.info("Customer created: id={}, name={}", saved.getId(), saved.getName());

        return toResponse(saved);
    }

    @Transactional
    public CustomerResponse updateCustomer(UUID id, CustomerRequest request) {
        logger.info("Updating customer: id={}", id);

        var customer = customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));

        customer.setName(request.name());
        customer.setEmail(request.email());
        customer.setPhone(request.phone());
        customer.setRfc(request.rfc());
        customer.setSalesChannel(request.salesChannel());
        customer.setEnabled(request.enabled());

        var updated = customerRepository.save(customer);
        logger.info("Customer updated: id={}", id);

        return toResponse(updated);
    }

    @Transactional
    public void deleteCustomer(UUID id) {
        logger.info("Soft-deleting customer: id={}", id);

        var customer = customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));

        customer.setEnabled(false);
        customerRepository.save(customer);
        logger.info("Customer soft-deleted: id={}", id);
    }

    @Transactional
    public CustomerResponse enableCustomer(UUID id) {
        logger.info("Re-enabling customer: id={}", id);

        var customer = customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));

        customer.setEnabled(true);
        var saved = customerRepository.save(customer);

        return toResponse(saved);
    }

    // ─── Response Mapper ─────────────────────────────────────────────────

    private CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getRfc(),
                customer.getSalesChannel(),
                customer.getEnabled(),
                customer.getCreatedAt(),
                customer.getUpdatedAt()
        );
    }
}
