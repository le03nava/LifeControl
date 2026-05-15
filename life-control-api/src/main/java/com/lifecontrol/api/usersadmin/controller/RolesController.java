package com.lifecontrol.api.usersadmin.controller;

import com.lifecontrol.api.usersadmin.dto.ChildRoleRequest;
import com.lifecontrol.api.usersadmin.dto.RoleRequest;
import com.lifecontrol.api.usersadmin.identity.RoleDto;
import com.lifecontrol.api.usersadmin.identity.RoleScope;
import com.lifecontrol.api.usersadmin.service.UsersAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

@RestController
@RequestMapping("/api/users-admin/roles")
@Tag(name = "Users Admin - Roles", description = "API for managing identity provider roles")
public class RolesController {

    private final UsersAdminService service;

    public RolesController(UsersAdminService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------
    // Realm Roles
    // ---------------------------------------------------------------

    @GetMapping("/realm")
    @Operation(summary = "List realm roles", description = "Returns all realm-level roles")
    public ResponseEntity<List<RoleDto>> listRealmRoles() {
        return ResponseEntity.ok(service.listRealmRoles());
    }

    @PostMapping("/realm")
    @Operation(summary = "Create realm role", description = "Creates a new realm-level role")
    public ResponseEntity<RoleDto> createRealmRole(@Valid @RequestBody RoleRequest request) {
        var created = service.createRealmRole(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/realm/{name}")
    @Operation(summary = "Get realm role", description = "Returns a single realm role by name")
    public ResponseEntity<RoleDto> getRealmRole(
            @Parameter(description = "Role name") @PathVariable String name) {
        return ResponseEntity.ok(service.getRealmRole(name));
    }

    @PutMapping("/realm/{name}")
    @Operation(summary = "Update realm role", description = "Updates an existing realm role")
    public ResponseEntity<RoleDto> updateRealmRole(
            @Parameter(description = "Role name") @PathVariable String name,
            @Valid @RequestBody RoleRequest request) {
        return ResponseEntity.ok(service.updateRealmRole(name, request));
    }

    @DeleteMapping("/realm/{name}")
    @Operation(summary = "Delete realm role", description = "Deletes a realm role by name")
    public ResponseEntity<Void> deleteRealmRole(
            @Parameter(description = "Role name") @PathVariable String name) {
        service.deleteRealmRole(name);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------
    // Client Roles
    // ---------------------------------------------------------------

    @GetMapping("/client/{clientId}")
    @Operation(summary = "List client roles", description = "Returns all roles for a specific client")
    public ResponseEntity<List<RoleDto>> listClientRoles(
            @Parameter(description = "Client ID") @PathVariable String clientId) {
        return ResponseEntity.ok(service.listClientRoles(clientId));
    }

    @PostMapping("/client/{clientId}")
    @Operation(summary = "Create client role", description = "Creates a new role for a specific client")
    public ResponseEntity<RoleDto> createClientRole(
            @Parameter(description = "Client ID") @PathVariable String clientId,
            @Valid @RequestBody RoleRequest request) {
        var created = service.createClientRole(clientId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/client/{clientId}/{roleName}")
    @Operation(summary = "Delete client role", description = "Deletes a client-level role by client ID and role name")
    public ResponseEntity<Void> deleteClientRole(
            @Parameter(description = "Client ID") @PathVariable String clientId,
            @Parameter(description = "Role name") @PathVariable String roleName) {
        service.deleteClientRole(clientId, roleName);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------
    // Composite Children
    // ---------------------------------------------------------------

    @PostMapping("/realm/{name}/children")
    @Operation(summary = "Add child role to composite", description = "Adds a child role to a composite realm role")
    public ResponseEntity<Void> addChildRole(
            @Parameter(description = "Parent realm role name") @PathVariable String name,
            @Valid @RequestBody ChildRoleRequest request) {
        service.addChildRole(name, request.childRole(), request.scope(), request.clientId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/realm/{name}/children/{childRole}")
    @Operation(summary = "Remove child role from composite", description = "Removes a child role from a composite realm role")
    public ResponseEntity<Void> removeChildRole(
            @Parameter(description = "Parent realm role name") @PathVariable String name,
            @Parameter(description = "Child role name") @PathVariable String childRole,
            @Parameter(description = "Role scope (REALM or CLIENT)") @RequestParam RoleScope scope,
            @Parameter(description = "Client ID (required when scope is CLIENT)") @RequestParam(required = false) String clientId) {
        service.removeChildRole(name, childRole, scope, clientId);
        return ResponseEntity.noContent().build();
    }
}
