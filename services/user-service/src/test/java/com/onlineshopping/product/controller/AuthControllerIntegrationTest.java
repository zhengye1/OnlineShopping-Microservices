package com.onlineshopping.product.controller;

import com.onlineshopping.product.AbstractIntegrationTest;
import com.onlineshopping.product.dto.AuthResponse;
import com.onlineshopping.product.dto.RegisterRequest;
import com.onlineshopping.product.repository.OutboxEventRepository;
import com.onlineshopping.product.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class AuthControllerIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    UserRepository userRepo;

    @Autowired
    OutboxEventRepository outboxRepo;

    @AfterEach
    void cleanup() {
        userRepo.deleteAll();   // isolate tests across methods (shared static container)
        outboxRepo.deleteAll();
    }

    // ✅ WORKED EXAMPLE
    @Test
    void register_validRequest_returns201WithJwt() throws Exception {
        RegisterRequest req = new RegisterRequest("alice@test.com", "hunter2!23");

        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.email").value("alice@test.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.expiresInSeconds").value(300));
    }

    @Test
    void register_duplicateEmail_returns409CleanEnvelope() throws Exception {
        RegisterRequest req = new RegisterRequest("alice@test.com", "hunter2!23");
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.message").value("Email already registered"));

    }

    @Test
    void register_invalidPayload_returns400WithErrors() throws Exception {
        RegisterRequest req = new RegisterRequest("not-email", "x");
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isEmpty())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("email", "password")));
    }

    @Test
    void getUsersMe_validToken_returns200() throws Exception {
        RegisterRequest req = new RegisterRequest("alice@test.com", "hunter2!23");
        String body = mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        AuthResponse auth = objectMapper.readValue(body, AuthResponse.class);
        String token = auth.token();
        mockMvc.perform(get("/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@test.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    void getUsersMe_noToken_returns401() throws Exception {
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized());
    }
}
