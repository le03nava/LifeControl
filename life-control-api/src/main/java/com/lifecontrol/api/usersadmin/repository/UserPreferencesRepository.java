package com.lifecontrol.api.usersadmin.repository;

import com.lifecontrol.api.usersadmin.model.UserPreferences;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserPreferencesRepository extends JpaRepository<UserPreferences, UUID> {

    Optional<UserPreferences> findByKeycloakUserId(String keycloakUserId);
}
