package com.lifecontrol.api.activity.repository;

import com.lifecontrol.api.activity.model.ActivityProcess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActivityProcessRepository extends JpaRepository<ActivityProcess, UUID> {

    /**
     * Looks up a process by its unique name (e.g. {@code COMPANY}, {@code ORDER}).
     *
     * @param name the process name
     * @return an {@link Optional} containing the process if found
     */
    Optional<ActivityProcess> findByName(String name);
}
