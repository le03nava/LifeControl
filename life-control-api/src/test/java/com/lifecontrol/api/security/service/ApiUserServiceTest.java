package com.lifecontrol.api.security.service;

import com.lifecontrol.api.security.dto.ApiUserRequest;
import com.lifecontrol.api.security.dto.ApiUserResponse;
import com.lifecontrol.api.security.dto.ApiUserUpdateRequest;
import com.lifecontrol.api.security.exception.ApiUserNotFoundException;
import com.lifecontrol.api.security.exception.DuplicateResourceException;
import com.lifecontrol.api.security.model.ApiUser;
import com.lifecontrol.api.security.repository.ApiUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiUserService Tests")
class ApiUserServiceTest {

    @Mock
    private ApiUserRepository apiUserRepository;

    @InjectMocks
    private ApiUserService apiUserService;

    private ApiUser testUser;
    private ApiUserRequest testUserRequest;
    private ApiUserUpdateRequest testUpdateRequest;
    private UUID testUserId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        
        testUser = ApiUser.builder()
                .id(testUserId)
                .username("testuser")
                .email("test@example.com")
                .password("password123")
                .name("Test")
                .lastname("User")
                .phone("+1234567890")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        testUserRequest = ApiUserRequest.builder()
                .username("testuser")
                .email("test@example.com")
                .password("password123")
                .name("Test")
                .lastname("User")
                .phone("+1234567890")
                .enabled(true)
                .build();

        testUpdateRequest = ApiUserUpdateRequest.builder()
                .name("UpdatedName")
                .lastname("UpdatedLastname")
                .phone("+9876543210")
                .enabled(false)
                .build();
    }

    @Nested
    @DisplayName("createUser")
    class CreateUserTests {

        @Test
        @DisplayName("createUser - should create user successfully")
        void createUser_Success() {
            // Arrange
            when(apiUserRepository.existsByUsername(testUserRequest.getUsername())).thenReturn(false);
            when(apiUserRepository.existsByEmail(testUserRequest.getEmail())).thenReturn(false);
            when(apiUserRepository.save(any(ApiUser.class))).thenReturn(testUser);

            // Act
            ApiUserResponse result = apiUserService.createUser(testUserRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getUsername()).isEqualTo(testUserRequest.getUsername());
            assertThat(result.getEmail()).isEqualTo(testUserRequest.getEmail());
            
            verify(apiUserRepository).save(any(ApiUser.class));
        }

        @Test
        @DisplayName("createUser - should throw DuplicateResourceException when username exists")
        void createUser_UsernameExists_ThrowsException() {
            // Arrange
            when(apiUserRepository.existsByUsername(testUserRequest.getUsername())).thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> apiUserService.createUser(testUserRequest))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining("Username already exists");

            verify(apiUserRepository, never()).save(any());
        }

        @Test
        @DisplayName("createUser - should throw DuplicateResourceException when email exists")
        void createUser_EmailExists_ThrowsException() {
            // Arrange
            when(apiUserRepository.existsByUsername(testUserRequest.getUsername())).thenReturn(false);
            when(apiUserRepository.existsByEmail(testUserRequest.getEmail())).thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> apiUserService.createUser(testUserRequest))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining("Email already exists");

            verify(apiUserRepository, never()).save(any());
        }

        @Test
        @DisplayName("createUser - should use default enabled value when null")
        void createUser_NullEnabled_UsesDefault() {
            // Arrange
            ApiUserRequest requestWithoutEnabled = ApiUserRequest.builder()
                    .username("newuser")
                    .email("new@example.com")
                    .password("password")
                    .name("New")
                    .enabled(null)
                    .build();

            when(apiUserRepository.existsByUsername("newuser")).thenReturn(false);
            when(apiUserRepository.existsByEmail("new@example.com")).thenReturn(false);
            
            ApiUser savedUser = ApiUser.builder()
                    .id(UUID.randomUUID())
                    .username("newuser")
                    .email("new@example.com")
                    .password("password")
                    .name("New")
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            
            when(apiUserRepository.save(any(ApiUser.class))).thenReturn(savedUser);

            // Act
            ApiUserResponse result = apiUserService.createUser(requestWithoutEnabled);

            // Assert
            assertThat(result.getEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("getUserById")
    class GetUserByIdTests {

        @Test
        @DisplayName("getUserById - should return user when exists")
        void getUserById_Success() {
            // Arrange
            when(apiUserRepository.findById(testUserId)).thenReturn(Optional.of(testUser));

            // Act
            ApiUserResponse result = apiUserService.getUserById(testUserId);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(testUserId);
            assertThat(result.getUsername()).isEqualTo(testUser.getUsername());
        }

        @Test
        @DisplayName("getUserById - should throw ApiUserNotFoundException when not exists")
        void getUserById_NotFound_ThrowsException() {
            // Arrange
            when(apiUserRepository.findById(testUserId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> apiUserService.getUserById(testUserId))
                    .isInstanceOf(ApiUserNotFoundException.class)
                    .hasMessageContaining("User not found with id");
        }
    }

    @Nested
    @DisplayName("getUserByUsername")
    class GetUserByUsernameTests {

        @Test
        @DisplayName("getUserByUsername - should return user when exists")
        void getUserByUsername_Success() {
            // Arrange
            when(apiUserRepository.findByUsername(testUser.getUsername())).thenReturn(Optional.of(testUser));

            // Act
            ApiUserResponse result = apiUserService.getUserByUsername(testUser.getUsername());

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getUsername()).isEqualTo(testUser.getUsername());
        }

        @Test
        @DisplayName("getUserByUsername - should throw ApiUserNotFoundException when not exists")
        void getUserByUsername_NotFound_ThrowsException() {
            // Arrange
            when(apiUserRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> apiUserService.getUserByUsername("nonexistent"))
                    .isInstanceOf(ApiUserNotFoundException.class)
                    .hasMessageContaining("User not found with username");
        }
    }

    @Nested
    @DisplayName("getUserByEmail")
    class GetUserByEmailTests {

        @Test
        @DisplayName("getUserByEmail - should return user when exists")
        void getUserByEmail_Success() {
            // Arrange
            when(apiUserRepository.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));

            // Act
            ApiUserResponse result = apiUserService.getUserByEmail(testUser.getEmail());

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getEmail()).isEqualTo(testUser.getEmail());
        }

        @Test
        @DisplayName("getUserByEmail - should throw ApiUserNotFoundException when not exists")
        void getUserByEmail_NotFound_ThrowsException() {
            // Arrange
            when(apiUserRepository.findByEmail("nonexistent@example.com")).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> apiUserService.getUserByEmail("nonexistent@example.com"))
                    .isInstanceOf(ApiUserNotFoundException.class)
                    .hasMessageContaining("User not found with email");
        }
    }

    @Nested
    @DisplayName("getAllUsers")
    class GetAllUsersTests {

        @Test
        @DisplayName("getAllUsers - should return list of users")
        void getAllUsers_Success() {
            // Arrange
            List<ApiUser> users = List.of(testUser);
            when(apiUserRepository.findAll()).thenReturn(users);

            // Act
            List<ApiUserResponse> result = apiUserService.getAllUsers();

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUsername()).isEqualTo(testUser.getUsername());
        }

        @Test
        @DisplayName("getAllUsers - should return empty list when no users")
        void getAllUsers_Empty() {
            // Arrange
            when(apiUserRepository.findAll()).thenReturn(List.of());

            // Act
            List<ApiUserResponse> result = apiUserService.getAllUsers();

            // Assert
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("updateUser")
    class UpdateUserTests {

        @Test
        @DisplayName("updateUser - should update user successfully")
        void updateUser_Success() {
            // Arrange
            when(apiUserRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
            when(apiUserRepository.save(any(ApiUser.class))).thenReturn(testUser);

            // Act
            ApiUserResponse result = apiUserService.updateUser(testUserId, testUpdateRequest);

            // Assert
            assertThat(result).isNotNull();
            
            ArgumentCaptor<ApiUser> userCaptor = ArgumentCaptor.forClass(ApiUser.class);
            verify(apiUserRepository).save(userCaptor.capture());
            
            ApiUser capturedUser = userCaptor.getValue();
            assertThat(capturedUser.getName()).isEqualTo(testUpdateRequest.getName());
            assertThat(capturedUser.getLastname()).isEqualTo(testUpdateRequest.getLastname());
        }

        @Test
        @DisplayName("updateUser - should throw ApiUserNotFoundException when user not exists")
        void updateUser_NotFound_ThrowsException() {
            // Arrange
            when(apiUserRepository.findById(testUserId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> apiUserService.updateUser(testUserId, testUpdateRequest))
                    .isInstanceOf(ApiUserNotFoundException.class)
                    .hasMessageContaining("User not found with id");
        }

        @Test
        @DisplayName("updateUser - should only update provided fields")
        void updateUser_PartialUpdate() {
            // Arrange
            ApiUserUpdateRequest partialUpdate = ApiUserUpdateRequest.builder()
                    .name("NewName")
                    .build();

            when(apiUserRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
            when(apiUserRepository.save(any(ApiUser.class))).thenReturn(testUser);

            // Act
            apiUserService.updateUser(testUserId, partialUpdate);

            // Assert
            ArgumentCaptor<ApiUser> userCaptor = ArgumentCaptor.forClass(ApiUser.class);
            verify(apiUserRepository).save(userCaptor.capture());
            
            ApiUser capturedUser = userCaptor.getValue();
            assertThat(capturedUser.getName()).isEqualTo("NewName");
            assertThat(capturedUser.getLastname()).isEqualTo(testUser.getLastname()); // unchanged
        }
    }

    @Nested
    @DisplayName("changePassword")
    class ChangePasswordTests {

        @Test
        @DisplayName("changePassword - should change password successfully")
        void changePassword_Success() {
            // Arrange
            String newPassword = "newPassword123";
            when(apiUserRepository.findById(testUserId)).thenReturn(Optional.of(testUser));

            // Act
            apiUserService.changePassword(testUserId, newPassword);

            // Assert
            ArgumentCaptor<ApiUser> userCaptor = ArgumentCaptor.forClass(ApiUser.class);
            verify(apiUserRepository).save(userCaptor.capture());
            
            assertThat(userCaptor.getValue().getPassword()).isEqualTo(newPassword);
        }

        @Test
        @DisplayName("changePassword - should throw ApiUserNotFoundException when user not exists")
        void changePassword_NotFound_ThrowsException() {
            // Arrange
            when(apiUserRepository.findById(testUserId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> apiUserService.changePassword(testUserId, "newpass"))
                    .isInstanceOf(ApiUserNotFoundException.class)
                    .hasMessageContaining("User not found with id");
        }
    }

    @Nested
    @DisplayName("deleteUser")
    class DeleteUserTests {

        @Test
        @DisplayName("deleteUser - should delete user successfully")
        void deleteUser_Success() {
            // Arrange
            when(apiUserRepository.existsById(testUserId)).thenReturn(true);

            // Act
            apiUserService.deleteUser(testUserId);

            // Assert
            verify(apiUserRepository).deleteById(testUserId);
        }

        @Test
        @DisplayName("deleteUser - should throw ApiUserNotFoundException when user not exists")
        void deleteUser_NotFound_ThrowsException() {
            // Arrange
            when(apiUserRepository.existsById(testUserId)).thenReturn(false);

            // Act & Assert
            assertThatThrownBy(() -> apiUserService.deleteUser(testUserId))
                    .isInstanceOf(ApiUserNotFoundException.class)
                    .hasMessageContaining("User not found with id");
        }
    }
}
