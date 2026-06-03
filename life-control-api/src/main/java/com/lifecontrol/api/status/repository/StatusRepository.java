package com.lifecontrol.api.status.repository;

import com.lifecontrol.api.status.model.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StatusRepository extends JpaRepository<Status, UUID> {

    List<Status> findByStatusTypeId(UUID statusTypeId);

    Optional<Status> findByIdAndStatusTypeId(UUID id, UUID statusTypeId);

    boolean existsByStatusNameIgnoreCaseAndStatusTypeId(String statusName, UUID statusTypeId);
}
