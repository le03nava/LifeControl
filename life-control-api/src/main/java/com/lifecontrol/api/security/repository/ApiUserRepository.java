package com.lifecontrol.api.security.repository;

import com.lifecontrol.api.security.model.ApiUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiUserRepository extends JpaRepository<ApiUser, UUID> {

    Optional<ApiUser> findByUsername(String username);

    Optional<ApiUser> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
