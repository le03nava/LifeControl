package com.lifecontrol.api.security.controller;

import com.lifecontrol.api.security.dto.ApiUserRequest;
import com.lifecontrol.api.security.dto.ApiUserResponse;
import com.lifecontrol.api.security.dto.ApiUserUpdateRequest;
import com.lifecontrol.api.security.service.ApiUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "User Management", description = "API for managing users")
public class ApiUserController {

    private final ApiUserService apiUserService;

    @PostMapping
    @Operation(summary = "Create a new user", description = "Creates a new user with the provided details")
    public ResponseEntity<ApiUserResponse> createUser(@Valid @RequestBody ApiUserRequest request) {
        ApiUserResponse response = apiUserService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "Get all users", description = "Retrieves a list of all users")
    public ResponseEntity<List<ApiUserResponse>> getAllUsers() {
        List<ApiUserResponse> users = apiUserService.getAllUsers();
        return ResponseEntity.ok(users);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get user by ID", description = "Retrieves a user by their UUID")
    public ResponseEntity<ApiUserResponse> getUserById(
            @Parameter(description = "User UUID") @PathVariable UUID id) {
        ApiUserResponse user = apiUserService.getUserById(id);
        return ResponseEntity.ok(user);
    }

    @GetMapping("/username/{username}")
    @Operation(summary = "Get user by username", description = "Retrieves a user by their username")
    public ResponseEntity<ApiUserResponse> getUserByUsername(
            @Parameter(description = "Username") @PathVariable String username) {
        ApiUserResponse user = apiUserService.getUserByUsername(username);
        return ResponseEntity.ok(user);
    }

    @GetMapping("/email/{email}")
    @Operation(summary = "Get user by email", description = "Retrieves a user by their email")
    public ResponseEntity<ApiUserResponse> getUserByEmail(
            @Parameter(description = "Email") @PathVariable String email) {
        ApiUserResponse user = apiUserService.getUserByEmail(email);
        return ResponseEntity.ok(user);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update user", description = "Updates an existing user's details")
    public ResponseEntity<ApiUserResponse> updateUser(
            @Parameter(description = "User UUID") @PathVariable UUID id,
            @Valid @RequestBody ApiUserUpdateRequest request) {
        ApiUserResponse user = apiUserService.updateUser(id, request);
        return ResponseEntity.ok(user);
    }

    @PatchMapping("/{id}/password")
    @Operation(summary = "Change user password", description = "Changes the password of an existing user")
    public ResponseEntity<Map<String, String>> changePassword(
            @Parameter(description = "User UUID") @PathVariable UUID id,
            @RequestBody Map<String, String> passwordRequest) {
        String newPassword = passwordRequest.get("password");
        if (newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password is required"));
        }
        apiUserService.changePassword(id, newPassword);
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete user", description = "Deletes a user by their UUID")
    public ResponseEntity<Void> deleteUser(
            @Parameter(description = "User UUID") @PathVariable UUID id) {
        apiUserService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}
