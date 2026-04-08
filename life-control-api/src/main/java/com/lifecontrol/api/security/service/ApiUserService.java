package com.lifecontrol.api.security.service;

import com.lifecontrol.api.security.dto.ApiUserRequest;
import com.lifecontrol.api.security.dto.ApiUserResponse;
import com.lifecontrol.api.security.dto.ApiUserUpdateRequest;
import com.lifecontrol.api.security.exception.ApiUserNotFoundException;
import com.lifecontrol.api.security.exception.DuplicateResourceException;
import com.lifecontrol.api.security.model.ApiUser;
import com.lifecontrol.api.security.repository.ApiUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ApiUserService {

    private static final Logger log = LoggerFactory.getLogger(ApiUserService.class);

    private final ApiUserRepository apiUserRepository;

    public ApiUserService(ApiUserRepository apiUserRepository) {
        this.apiUserRepository = apiUserRepository;
    }

    public ApiUserResponse createUser(ApiUserRequest request) {
        log.info("Creating new user with username: {}", request.username());

        if (apiUserRepository.existsByUsername(request.username())) {
            throw new DuplicateResourceException("Username already exists: " + request.username());
        }

        if (apiUserRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email already exists: " + request.email());
        }

        ApiUser user = ApiUser.builder()
                .username(request.username())
                .email(request.email())
                .password(request.password())
                .name(request.name())
                .lastname(request.lastname())
                .phone(request.phone())
                .enabled(request.enabled() != null ? request.enabled() : true)
                .build();

        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        ApiUser savedUser = apiUserRepository.save(user);
        log.info("User created successfully with id: {}", savedUser.getId());

        return mapToResponse(savedUser);
    }

    @Transactional(readOnly = true)
    public ApiUserResponse getUserById(UUID id) {
        log.debug("Fetching user by id: {}", id);
        ApiUser user = apiUserRepository.findById(id)
                .orElseThrow(() -> new ApiUserNotFoundException("User not found with id: " + id));
        return mapToResponse(user);
    }

    @Transactional(readOnly = true)
    public ApiUserResponse getUserByUsername(String username) {
        log.debug("Fetching user by username: {}", username);
        ApiUser user = apiUserRepository.findByUsername(username)
                .orElseThrow(() -> new ApiUserNotFoundException("User not found with username: " + username));
        return mapToResponse(user);
    }

    @Transactional(readOnly = true)
    public ApiUserResponse getUserByEmail(String email) {
        log.debug("Fetching user by email: {}", email);
        ApiUser user = apiUserRepository.findByEmail(email)
                .orElseThrow(() -> new ApiUserNotFoundException("User not found with email: " + email));
        return mapToResponse(user);
    }

    @Transactional(readOnly = true)
    public List<ApiUserResponse> getAllUsers() {
        log.debug("Fetching all users");
        return apiUserRepository.findAll().stream()
                .map(this::mapToResponse)
                .toList();
    }

    public ApiUserResponse updateUser(UUID id, ApiUserUpdateRequest request) {
        log.info("Updating user with id: {}", id);
        ApiUser user = apiUserRepository.findById(id)
                .orElseThrow(() -> new ApiUserNotFoundException("User not found with id: " + id));

        if (request.name() != null) {
            user.setName(request.name());
        }
        if (request.lastname() != null) {
            user.setLastname(request.lastname());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone());
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }

        user.setUpdatedAt(LocalDateTime.now());
        ApiUser updatedUser = apiUserRepository.save(user);
        log.info("User updated successfully with id: {}", updatedUser.getId());

        return mapToResponse(updatedUser);
    }

    public void changePassword(UUID id, String newPassword) {
        log.info("Changing password for user with id: {}", id);
        ApiUser user = apiUserRepository.findById(id)
                .orElseThrow(() -> new ApiUserNotFoundException("User not found with id: " + id));

        user.setPassword(newPassword);
        user.setUpdatedAt(LocalDateTime.now());
        apiUserRepository.save(user);

        log.info("Password changed successfully for user with id: {}", id);
    }

    public void deleteUser(UUID id) {
        log.info("Deleting user with id: {}", id);
        if (!apiUserRepository.existsById(id)) {
            throw new ApiUserNotFoundException("User not found with id: " + id);
        }
        apiUserRepository.deleteById(id);
        log.info("User deleted successfully with id: {}", id);
    }

    private ApiUserResponse mapToResponse(ApiUser user) {
        return new ApiUserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getName(),
                user.getLastname(),
                user.getPhone(),
                user.getEnabled(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}