package com.onlineshopping.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for full-stack integration tests.
 *
 * <p>Spins up a real MySQL 8.4 container per test JVM (shared across all
 * test classes in this module via the {@code static} field), then lets
 * Spring Boot's {@code @ServiceConnection} (3.1+) inject the dynamic
 * JDBC URL / credentials into the Spring context. Flyway V1+V2 migrate
 * the throwaway DB on startup, Hibernate validates entity ↔ table mapping.
 *
 * <p>Subclasses get for free:
 * <ul>
 *   <li>Real Spring Boot context with embedded Tomcat (RANDOM_PORT)
 *   <li>{@link MockMvc} for HTTP-level testing without network roundtrip
 *   <li>{@link ObjectMapper} for request/response JSON marshalling
 *   <li>{@code application-test.yml} profile activated
 * </ul>
 *
 * <p>Each test method should use a UNIQUE email to avoid cross-test
 * pollution (no auto-rollback — production-realistic). Alternatively
 * mark the method {@code @Transactional} for rollback isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("user_service_test")
            .withUsername("test")
            .withPassword("test");

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;
}
