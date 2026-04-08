package com.lifecontrol.api.security.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.security.dto.ApiUserRequest;
import com.lifecontrol.api.security.dto.ApiUserResponse;
import com.lifecontrol.api.security.dto.ApiUserUpdateRequest;
import com.lifecontrol.api.security.exception.ApiUserNotFoundException;
import com.lifecontrol.api.security.exception.DuplicateResourceException;
import com.lifecontrol.api.security.service.ApiUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiUserController Tests")
class ApiUserControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private ApiUserService apiUserService;

    @InjectMocks
    private ApiUserController apiUserController;

    private ApiUserResponse testUserResponse;
    private ApiUserRequest testUserRequest;
    private ApiUserUpdateRequest testUpdateRequest;
    private UUID testUserId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(apiUserController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        testUserId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        testUserResponse = new ApiUserResponse(
                testUserId,
                "testuser",
                "test@example.com",
                "Test",
                "User",
                "+1234567890",
                true,
                now,
                now
        );

        testUserRequest = new ApiUserRequest(
                "testuser",
                "test@example.com",
                "password123",
                "Test",
                "User",
                "+1234567890",
                true
        );

        testUpdateRequest = new ApiUserUpdateRequest(
                "UpdatedName",
                "UpdatedLastname",
                "+9876543210",
                false
        );
    }

    @Nested
    @DisplayName("POST /api/users")
    class CreateUserTests {

        @Test
        @DisplayName("createUser - should return 201 Created on success")
        void createUser_Success() throws Exception {
            // Arrange
            when(apiUserService.createUser(any(ApiUserRequest.class))).thenReturn(testUserResponse);

            // Act & Assert
            mockMvc.perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testUserRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(testUserId.toString()))
                    .andExpect(jsonPath("$.username").value("testuser"))
                    .andExpect(jsonPath("$.email").value("test@example.com"));
        }

        @Test
        @DisplayName("createUser - should return 400 Bad Request for invalid input")
        void createUser_InvalidInput_BadRequest() throws Exception {
            // Arrange
            ApiUserRequest invalidRequest = new ApiUserRequest(
                    "ab", // Too short
                    "invalid-email", // Invalid email
                    "123", // Too short
                    null,
                    null,
                    null,
                    null
            );

            // Act & Assert
            mockMvc.perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("createUser - should return 409 Conflict for duplicate username")
        void createUser_DuplicateUsername_Conflict() throws Exception {
            // Arrange
            when(apiUserService.createUser(any(ApiUserRequest.class)))
                    .thenThrow(new DuplicateResourceException("Username already exists: testuser"));

            // Act & Assert
            mockMvc.perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testUserRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Username already exists: testuser"));
        }
    }

    @Nested
    @DisplayName("GET /api/users")
    class GetAllUsersTests {

        @Test
        @DisplayName("getAllUsers - should return 200 OK with list of users")
        void getAllUsers_Success() throws Exception {
            // Arrange
            when(apiUserService.getAllUsers()).thenReturn(List.of(testUserResponse));

            // Act & Assert
            mockMvc.perform(get("/api/users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(testUserId.toString()))
                    .andExpect(jsonPath("$[0].username").value("testuser"));
        }

        @Test
        @DisplayName("getAllUsers - should return empty list when no users")
        void getAllUsers_EmptyList() throws Exception {
            // Arrange
            when(apiUserService.getAllUsers()).thenReturn(List.of());

            // Act & Assert
            mockMvc.perform(get("/api/users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }
    }

    @Nested
    @DisplayName("GET /api/users/{id}")
    class GetUserByIdTests {

        @Test
        @DisplayName("getUserById - should return 200 OK with user")
        void getUserById_Success() throws Exception {
            // Arrange
            when(apiUserService.getUserById(testUserId)).thenReturn(testUserResponse);

            // Act & Assert
            mockMvc.perform(get("/api/users/{id}", testUserId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testUserId.toString()))
                    .andExpect(jsonPath("$.username").value("testuser"));
        }

        @Test
        @DisplayName("getUserById - should return 404 Not Found when user not exists")
        void getUserById_NotFound() throws Exception {
            // Arrange
            when(apiUserService.getUserById(testUserId))
                    .thenThrow(new ApiUserNotFoundException("User not found with id: " + testUserId));

            // Act & Assert
            mockMvc.perform(get("/api/users/{id}", testUserId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("User not found with id: " + testUserId));
        }
    }

    @Nested
    @DisplayName("GET /api/users/username/{username}")
    class GetUserByUsernameTests {

        @Test
        @DisplayName("getUserByUsername - should return 200 OK with user")
        void getUserByUsername_Success() throws Exception {
            // Arrange
            String username = "testuser";
            when(apiUserService.getUserByUsername(username)).thenReturn(testUserResponse);

            // Act & Assert
            mockMvc.perform(get("/api/users/username/{username}", username))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value(username));
        }

        @Test
        @DisplayName("getUserByUsername - should return 404 Not Found when user not exists")
        void getUserByUsername_NotFound() throws Exception {
            // Arrange
            String username = "nonexistent";
            when(apiUserService.getUserByUsername(username))
                    .thenThrow(new ApiUserNotFoundException("User not found with username: " + username));

            // Act & Assert
            mockMvc.perform(get("/api/users/username/{username}", username))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("User not found with username: " + username));
        }
    }

    @Nested
    @DisplayName("GET /api/users/email/{email}")
    class GetUserByEmailTests {

        @Test
        @DisplayName("getUserByEmail - should return 200 OK with user")
        void getUserByEmail_Success() throws Exception {
            // Arrange
            String email = "test@example.com";
            when(apiUserService.getUserByEmail(email)).thenReturn(testUserResponse);

            // Act & Assert
            mockMvc.perform(get("/api/users/email/{email}", email))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(email));
        }

        @Test
        @DisplayName("getUserByEmail - should return 404 Not Found when user not exists")
        void getUserByEmail_NotFound() throws Exception {
            // Arrange
            String email = "nonexistent@example.com";
            when(apiUserService.getUserByEmail(email))
                    .thenThrow(new ApiUserNotFoundException("User not found with email: " + email));

            // Act & Assert
            mockMvc.perform(get("/api/users/email/{email}", email))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("User not found with email: " + email));
        }
    }

    @Nested
    @DisplayName("PUT /api/users/{id}")
    class UpdateUserTests {

        @Test
        @DisplayName("updateUser - should return 200 OK with updated user")
        void updateUser_Success() throws Exception {
            // Arrange
            when(apiUserService.updateUser(eq(testUserId), any(ApiUserUpdateRequest.class)))
                    .thenReturn(testUserResponse);

            // Act & Assert
            mockMvc.perform(put("/api/users/{id}", testUserId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testUpdateRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testUserId.toString()));
        }

        @Test
        @DisplayName("updateUser - should return 404 Not Found when user not exists")
        void updateUser_NotFound() throws Exception {
            // Arrange
            when(apiUserService.updateUser(eq(testUserId), any(ApiUserUpdateRequest.class)))
                    .thenThrow(new ApiUserNotFoundException("User not found with id: " + testUserId));

            // Act & Assert
            mockMvc.perform(put("/api/users/{id}", testUserId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testUpdateRequest)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH /api/users/{id}/password")
    class ChangePasswordTests {

        @Test
        @DisplayName("changePassword - should return 200 OK on success")
        void changePassword_Success() throws Exception {
            // Arrange
            doNothing().when(apiUserService).changePassword(testUserId, "newPassword123");

            Map<String, String> passwordRequest = Map.of("password", "newPassword123");

            // Act & Assert
            mockMvc.perform(patch("/api/users/{id}/password", testUserId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(passwordRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Password changed successfully"));
        }

        @Test
        @DisplayName("changePassword - should return 400 Bad Request when password is missing")
        void changePassword_MissingPassword_BadRequest() throws Exception {
            // Arrange
            Map<String, String> passwordRequest = Map.of("password", "");

            // Act & Assert
            mockMvc.perform(patch("/api/users/{id}/password", testUserId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(passwordRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Password is required"));
        }

        @Test
        @DisplayName("changePassword - should return 404 Not Found when user not exists")
        void changePassword_NotFound() throws Exception {
            // Arrange
            doThrow(new ApiUserNotFoundException("User not found with id: " + testUserId))
                    .when(apiUserService).changePassword(testUserId, "newPassword123");

            Map<String, String> passwordRequest = Map.of("password", "newPassword123");

            // Act & Assert
            mockMvc.perform(patch("/api/users/{id}/password", testUserId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(passwordRequest)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("DELETE /api/users/{id}")
    class DeleteUserTests {

        @Test
        @DisplayName("deleteUser - should return 204 No Content on success")
        void deleteUser_Success() throws Exception {
            // Arrange
            doNothing().when(apiUserService).deleteUser(testUserId);

            // Act & Assert
            mockMvc.perform(delete("/api/users/{id}", testUserId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("deleteUser - should return 404 Not Found when user not exists")
        void deleteUser_NotFound() throws Exception {
            // Arrange
            doThrow(new ApiUserNotFoundException("User not found with id: " + testUserId))
                    .when(apiUserService).deleteUser(testUserId);

            // Act & Assert
            mockMvc.perform(delete("/api/users/{id}", testUserId))
                    .andExpect(status().isNotFound());
        }
    }
}