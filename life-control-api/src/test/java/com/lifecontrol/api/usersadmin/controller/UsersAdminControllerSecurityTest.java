package com.lifecontrol.api.usersadmin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.usersadmin.dto.CreateUserRequest;
import com.lifecontrol.api.usersadmin.dto.CreateUserResponse;
import com.lifecontrol.api.usersadmin.service.UsersAdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UsersAdminController.class)
@DisplayName("UsersAdminController Security — filter-chain authorization")
class UsersAdminControllerSecurityTest {

    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/api/users-admin/**").hasAuthority("ROLE_admin")
                            .requestMatchers("/api/**").authenticated()
                            .anyRequest().permitAll())
                    .httpBasic(basic -> {})
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session ->
                            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UsersAdminService service;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    @MockitoBean
    private Keycloak keycloak;

    private CreateUserRequest buildRequest() {
        return new CreateUserRequest("jdoe", "jdoe@example.com", "John", "Doe", true);
    }

    @Nested
    @DisplayName("POST /api/users-admin/users")
    class CreateUserSecurity {

        @Test
        @WithMockUser(roles = {"admin"})
        @DisplayName("returns 201 for user with ROLE_admin")
        void adminCanCreateUser() throws Exception {
            when(service.createUser(any())).thenReturn(new CreateUserResponse("kc-id-123"));

            mockMvc.perform(post("/api/users-admin/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user without ROLE_admin")
        void nonAdminCannotCreateUser() throws Exception {
            mockMvc.perform(post("/api/users-admin/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for authenticated user with no roles")
        void noRolesUserCannotCreateUser() throws Exception {
            mockMvc.perform(post("/api/users-admin/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }
}
