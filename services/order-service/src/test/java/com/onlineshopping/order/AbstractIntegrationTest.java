package com.onlineshopping.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineshopping.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static org.mockito.Mockito.when;

/**
 * L9 order-service integration test base — singleton MySQL + Kafka shared
 * across the JVM (same pattern as cart-service L7.5 + L8).
 *
 * <p>Why both containers static + JVM-shutdown-hook lifecycle: per-class
 * lifecycle binds container teardown to test-class completion, which breaks
 * for multi-class test runs. The pattern was originally surfaced as a war
 * story in L8 (see lesson-08 doc). Reused here.
 *
 * <p>{@code @MockitoBean JwtDecoder} replaces real JWT verification — tests
 * send a real {@code Authorization: Bearer test-token-{userId}} header and
 * the mocked decoder synthesizes a Jwt with the userId as the subject. Full
 * Spring Security filter chain runs so any auth-related regression is caught.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final MySQLContainer<?> MYSQL;

    @ServiceConnection
    static final ConfluentKafkaContainer KAFKA;

    static {
        MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("order_service_test")
                .withUsername("test")
                .withPassword("test");
        MYSQL.start();
        Runtime.getRuntime().addShutdownHook(new Thread(MYSQL::stop));

        KAFKA = new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));
        KAFKA.start();
        Runtime.getRuntime().addShutdownHook(new Thread(KAFKA::stop));
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected OrderRepository orderRepo;

    @Autowired
    protected KafkaTemplate<String, Object> kafkaTemplate;

    @MockitoBean
    protected JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanupOrders() {
        orderRepo.deleteAll();
    }

    protected String mockJwtFor(long userId) {
        String token = "test-token-" + userId;
        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .header("kid", "test-key")
                .claim("sub", String.valueOf(userId))
                .claim("role", "USER")
                .build();
        when(jwtDecoder.decode(token)).thenReturn(jwt);
        return "Bearer " + token;
    }
}
