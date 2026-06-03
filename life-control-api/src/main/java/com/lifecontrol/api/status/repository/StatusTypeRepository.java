package com.lifecontrol.api.status.repository;

import com.lifecontrol.api.status.model.StatusType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StatusTypeRepository extends JpaRepository<StatusType, UUID> {

    Optional<StatusType> findByStatusTypeNameIgnoreCase(String statusTypeName);

    Page<StatusType> findByEnabledTrue(Pageable pageable);

    Page<StatusType> findByEnabledTrueAndStatusTypeNameContainingIgnoreCase(String search, Pageable pageable);

    boolean existsByStatusTypeNameIgnoreCase(String statusTypeName);
}
