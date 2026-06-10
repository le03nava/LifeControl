package com.lifecontrol.api.usersadmin.controller;

import com.lifecontrol.api.usersadmin.dto.AttributeValueRequest;
import com.lifecontrol.api.usersadmin.dto.CreateUserRequest;
import com.lifecontrol.api.usersadmin.dto.CreateUserResponse;
import com.lifecontrol.api.usersadmin.dto.PageResponse;
import com.lifecontrol.api.usersadmin.dto.UserAssignmentRequest;
import com.lifecontrol.api.usersadmin.identity.RoleDto;
import com.lifecontrol.api.usersadmin.identity.RoleScope;
import com.lifecontrol.api.usersadmin.identity.UserSearchDto;
import com.lifecontrol.api.usersadmin.service.UsersAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users-admin/users")
@Tag(name = "Users Admin - Users", description = "API for managing identity provider users")
public class UsersAdminController {

    private final UsersAdminService service;

    public UsersAdminController(UsersAdminService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------
    // User Creation
    // ---------------------------------------------------------------

    @PostMapping
    @Operation(summary = "Create user", description = "Creates a Keycloak user and initializes user preferences")
    @ApiResponse(responseCode = "201", description = "User created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
    @ApiResponse(responseCode = "409", description = "User already exists")
    @ApiResponse(responseCode = "503", description = "Identity provider unavailable")
    public ResponseEntity<CreateUserResponse> createUser(
            @Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createUser(request));
    }

    // ---------------------------------------------------------------
    // User Search
    // ---------------------------------------------------------------

    @GetMapping
    @Operation(summary = "Search users", description = "Searches users by username, email, or name")
    public ResponseEntity<PageResponse<UserSearchDto>> searchUsers(
            @Parameter(description = "Search query") @RequestParam(required = false, defaultValue = "") String search,
            @Parameter(description = "Page number (0-based)") @RequestParam(required = false, defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(required = false, defaultValue = "20") int size) {
        return ResponseEntity.ok(service.searchUsers(search, page, size));
    }

    // ---------------------------------------------------------------
    // User Role Listing
    // ---------------------------------------------------------------

    @GetMapping("/{id}/roles")
    @Operation(summary = "Get user roles", description = "Returns all roles assigned to a user")
    public ResponseEntity<List<RoleDto>> getUserRoles(
            @Parameter(description = "User ID") @PathVariable String id,
            @Parameter(description = "Filter by client ID") @RequestParam(required = false) String clientId) {
        if (clientId != null && !clientId.isBlank()) {
            return ResponseEntity.ok(service.getUserRoles(id, clientId));
        }
        return ResponseEntity.ok(service.getUserRoles(id));
    }

    // ---------------------------------------------------------------
    // Role Assignment
    // ---------------------------------------------------------------

    @PostMapping("/{id}/roles/realm")
    @Operation(summary = "Assign realm role", description = "Assigns a realm role to a user")
    public ResponseEntity<Void> assignRealmRole(
            @Parameter(description = "User ID") @PathVariable String id,
            @Valid @RequestBody UserAssignmentRequest request) {
        service.assignRoleToUser(id, request.roleName(), RoleScope.REALM, null);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/{id}/roles/client/{clientId}")
    @Operation(summary = "Assign client role", description = "Assigns a client role to a user")
    public ResponseEntity<Void> assignClientRole(
            @Parameter(description = "User ID") @PathVariable String id,
            @Parameter(description = "Client ID") @PathVariable String clientId,
            @Valid @RequestBody UserAssignmentRequest request) {
        service.assignRoleToUser(id, request.roleName(), RoleScope.CLIENT, clientId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{id}/roles/realm/{roleName}")
    @Operation(summary = "Remove realm role", description = "Removes a realm role from a user")
    public ResponseEntity<Void> removeRealmRole(
            @Parameter(description = "User ID") @PathVariable String id,
            @Parameter(description = "Role name") @PathVariable String roleName) {
        service.removeRoleFromUser(id, roleName, RoleScope.REALM, null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/roles/client/{clientId}/{roleName}")
    @Operation(summary = "Remove client role", description = "Removes a client role from a user")
    public ResponseEntity<Void> removeClientRole(
            @Parameter(description = "User ID") @PathVariable String id,
            @Parameter(description = "Client ID") @PathVariable String clientId,
            @Parameter(description = "Role name") @PathVariable String roleName) {
        service.removeRoleFromUser(id, roleName, RoleScope.CLIENT, clientId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------
    // User Attributes
    // ---------------------------------------------------------------

    @GetMapping("/{id}/attributes")
    @Operation(summary = "Get user attributes", description = "Returns all custom attributes for a user")
    public ResponseEntity<Map<String, List<String>>> getUserAttributes(
            @Parameter(description = "User ID") @PathVariable String id) {
        return ResponseEntity.ok(service.getUserAttributes(id));
    }

    @PutMapping("/{id}/attributes/{key}")
    @Operation(summary = "Update user attribute", description = "Updates or creates a custom attribute for a user")
    public ResponseEntity<Void> updateUserAttribute(
            @Parameter(description = "User ID") @PathVariable String id,
            @Parameter(description = "Attribute key") @PathVariable String key,
            @Valid @RequestBody AttributeValueRequest request) {
        service.updateUserAttribute(id, key, request.values());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/attributes/{key}")
    @Operation(summary = "Delete user attribute", description = "Deletes a custom attribute from a user")
    public ResponseEntity<Void> deleteUserAttribute(
            @Parameter(description = "User ID") @PathVariable String id,
            @Parameter(description = "Attribute key") @PathVariable String key) {
        service.deleteUserAttribute(id, key);
        return ResponseEntity.noContent().build();
    }
}
