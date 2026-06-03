package com.lifecontrol.api.paymentmethod.repository;

import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, UUID> {

    Optional<PaymentMethod> findByPaymentMethodNameIgnoreCase(String paymentMethodName);

    boolean existsByPaymentMethodNameIgnoreCase(String paymentMethodName);

    List<PaymentMethod> findAllByOrderByPaymentMethodNameAsc();
}
