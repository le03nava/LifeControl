package com.lifecontrol.api.paymentmethod.service;

import com.lifecontrol.api.paymentmethod.dto.PaymentMethodRequest;
import com.lifecontrol.api.paymentmethod.dto.PaymentMethodResponse;
import com.lifecontrol.api.paymentmethod.exception.DuplicatePaymentMethodException;
import com.lifecontrol.api.paymentmethod.exception.PaymentMethodNotFoundException;
import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PaymentMethodService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentMethodService.class);

    private final PaymentMethodRepository repository;

    public PaymentMethodService(PaymentMethodRepository repository) {
        this.repository = repository;
    }

    @Cacheable(value = "paymentMethods")
    @Transactional(readOnly = true)
    public List<PaymentMethodResponse> getAllPaymentMethods() {
        return repository.findAllByOrderByPaymentMethodNameAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Cacheable(value = "paymentMethods", key = "#id")
    @Transactional(readOnly = true)
    public PaymentMethodResponse getPaymentMethodById(UUID id) {
        return repository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new PaymentMethodNotFoundException(id));
    }

    @CacheEvict(value = "paymentMethods", allEntries = true)
    @Transactional
    public PaymentMethodResponse createPaymentMethod(PaymentMethodRequest request) {
        logger.info("Creating payment method with name: {}", request.paymentMethodName());

        if (repository.existsByPaymentMethodNameIgnoreCase(request.paymentMethodName())) {
            throw new DuplicatePaymentMethodException(request.paymentMethodName());
        }

        var paymentMethod = toEntity(request);
        var saved = repository.save(paymentMethod);
        logger.info("Payment method created successfully with id: {}", saved.getId());

        return toResponse(saved);
    }

    @CacheEvict(value = "paymentMethods", allEntries = true)
    @Transactional
    public PaymentMethodResponse updatePaymentMethod(UUID id, PaymentMethodRequest request) {
        logger.info("Updating payment method with id: {}", id);

        var paymentMethod = repository.findById(id)
                .orElseThrow(() -> new PaymentMethodNotFoundException(id));

        // Check duplicate name excluding current entity
        var existing = repository.findByPaymentMethodNameIgnoreCase(request.paymentMethodName());
        if (existing.isPresent() && !existing.get().getId().equals(id)) {
            throw new DuplicatePaymentMethodException(request.paymentMethodName());
        }

        paymentMethod.setPaymentMethodName(request.paymentMethodName());
        paymentMethod.setPaymentMethodShortName(request.paymentMethodShortName());
        paymentMethod.setEnabled(request.enabled());

        var updated = repository.save(paymentMethod);
        logger.info("Payment method updated successfully with id: {}", updated.getId());

        return toResponse(updated);
    }

    @CacheEvict(value = "paymentMethods", allEntries = true)
    @Transactional
    public void deletePaymentMethod(UUID id) {
        logger.info("Deleting payment method with id: {}", id);

        var paymentMethod = repository.findById(id)
                .orElseThrow(() -> new PaymentMethodNotFoundException(id));

        repository.delete(paymentMethod);
        logger.info("Payment method deleted: id={}, name={}", id, paymentMethod.getPaymentMethodName());
    }

    @CacheEvict(value = "paymentMethods", allEntries = true)
    @Transactional
    public PaymentMethodResponse setPaymentMethodEnabled(UUID id, boolean enabled) {
        logger.info("Setting payment method enabled={} for id: {}", enabled, id);

        var paymentMethod = repository.findById(id)
                .orElseThrow(() -> new PaymentMethodNotFoundException(id));

        paymentMethod.setEnabled(enabled);
        var saved = repository.save(paymentMethod);

        return toResponse(saved);
    }

    private PaymentMethod toEntity(PaymentMethodRequest request) {
        return PaymentMethod.builder()
                .paymentMethodName(request.paymentMethodName())
                .paymentMethodShortName(request.paymentMethodShortName())
                .enabled(request.enabled())
                .build();
    }

    private PaymentMethodResponse toResponse(PaymentMethod paymentMethod) {
        return new PaymentMethodResponse(
                paymentMethod.getId(),
                paymentMethod.getPaymentMethodName(),
                paymentMethod.getPaymentMethodShortName(),
                paymentMethod.getEnabled(),
                paymentMethod.getCreatedAt(),
                paymentMethod.getUpdatedAt()
        );
    }
}
