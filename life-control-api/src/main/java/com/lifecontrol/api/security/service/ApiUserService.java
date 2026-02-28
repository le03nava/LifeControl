package com.lifecontrol.api.security.service;

import com.lifecontrol.api.security.dto.ApiUserRequest;
import com.lifecontrol.api.security.dto.ApiUserResponse;
import com.lifecontrol.api.security.dto.ApiUserUpdateRequest;
import com.lifecontrol.api.security.exception.ApiUserNotFoundException;
import com.lifecontrol.api.security.exception.DuplicateResourceException;
import com.lifecontrol.api.security.model.ApiUser;
import com.lifecontrol.api.security.repository.ApiUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ApiUserService {

    private final ApiUserRepository apiUserRepository;

    public ApiUserResponse createUser(ApiUserRequest request) {
        log.info("Creating new user with username: {}", request.getUsername());

        if (apiUserRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateResourceException("Username already exists: " + request.getUsername());
        }

        if (apiUserRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email already exists: " + request.getEmail());
        }

        ApiUser user = ApiUser.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(request.getPassword())
                .name(request.getName())
                .lastname(request.getLastname())
                .phone(request.getPhone())
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
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
                .collect(Collectors.toList());
    }

    public ApiUserResponse updateUser(UUID id, ApiUserUpdateRequest request) {
        log.info("Updating user with id: {}", id);
        ApiUser user = apiUserRepository.findById(id)
                .orElseThrow(() -> new ApiUserNotFoundException("User not found with id: " + id));

        if (request.getName() != null) {
            user.setName(request.getName());
        }
        if (request.getLastname() != null) {
            user.setLastname(request.getLastname());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getEnabled() != null) {
            user.setEnabled(request.getEnabled());
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
        return ApiUserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .name(user.getName())
                .lastname(user.getLastname())
                .phone(user.getPhone())
                .enabled(user.getEnabled())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
