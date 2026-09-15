package com.lifecontrol.api.activity.repository;

import com.lifecontrol.api.activity.model.ActivityEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ActivityEventRepository extends JpaRepository<ActivityEvent, UUID> {

    /**
     * Looks up an event by its unique name (e.g. {@code CREATE}, {@code DELETE}).
     *
     * @param name the event name
     * @return an {@link Optional} containing the event if found
     */
    Optional<ActivityEvent> findByName(String name);
}
