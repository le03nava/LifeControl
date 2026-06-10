package com.lifecontrol.api.profile;

import com.lifecontrol.api.profile.dto.ProfileResponse;
import com.lifecontrol.api.profile.dto.ProfileUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service profile endpoint. Authenticated users can read and update
 * their own basic info (name, email via Keycloak) and location preferences
 * (country → company → region → zone → store via {@code user_preferences}).
 */
@RestController
@RequestMapping("/api/profile")
@Tag(name = "User Profile", description = "Self-service profile management for authenticated users")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    @Operation(summary = "Get user profile",
               description = "Returns the authenticated user's basic info and location preferences")
    public ResponseEntity<ProfileResponse> getProfile() {
        return ResponseEntity.ok(profileService.getProfile());
    }

    @PutMapping
    @Operation(summary = "Update user profile",
               description = "Updates basic info in Keycloak and/or location preferences in user_preferences")
    public ResponseEntity<ProfileResponse> updateProfile(@Valid @RequestBody ProfileUpdateRequest request) {
        return ResponseEntity.ok(profileService.updateProfile(request));
    }
}
