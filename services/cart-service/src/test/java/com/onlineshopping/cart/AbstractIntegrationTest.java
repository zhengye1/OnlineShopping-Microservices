package com.onlineshopping.cart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.onlineshopping.cart.repository.CartItemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Base class for cart-service integration tests.
 *
 * <p>Provides three layers of test infrastructure shared across all integration
 * test classes in this module:
 *
 * <ol>
 *   <li><b>Real MySQL 8.4 container</b> — production-grade DB engine via
 *       Testcontainers. Static field = single container shared across the JVM
 *       (≈30s startup cost amortized across all test classes). Spring Boot
 *       3.1+ {@code @ServiceConnection} auto-wires the dynamic JDBC URL,
 *       username, and password — no {@code @DynamicPropertySource} boilerplate
 *       for datasource needed.
 *
 *   <li><b>WireMock for downstream HTTP stubbing</b> — one shared instance
 *       on a dynamic port shadows both product-service and inventory-service.
 *       Stubs distinguish by URL path ({@code /products/{id}} vs
 *       {@code /inventory/{id}}). {@link #resetWireMock()} clears stubs between
 *       tests for isolation.
 *
 *   <li><b>Cart-row cleanup between tests</b> — {@link #cleanupCart()} wipes
 *       {@code cart_items} so tests don't see each other's residue. The cart
 *       entity has no auto-rollback (composite PK with versioned upsert) so
 *       explicit cleanup is required.
 * </ol>
 *
 * <p>Subclasses get for free:
 * <ul>
 *   <li>Real Spring Boot context with embedded Tomcat (RANDOM_PORT)
 *   <li>{@link MockMvc} for HTTP-level testing without network roundtrip
 *   <li>{@link ObjectMapper} for request/response JSON marshalling
 *   <li>{@link #wireMock} stub builder + verification handle
 *   <li>{@code application-test.yml} profile activated
 * </ul>
 *
 * <p><b>JWT authentication strategy:</b> we use {@code @MockitoBean JwtDecoder}
 * over the simpler {@code SecurityMockMvcRequestPostProcessors.jwt()} because
 * we need a <em>real {@code Authorization} header</em> on the HTTP request —
 * {@code FeignAuthForwardInterceptor} reads it from {@code RequestContextHolder}
 * to forward to downstream services. The post-processor only writes to
 * {@code SecurityContext}, leaving the request header null, which breaks the
 * auth-forwarding verification path.
 *
 * <p>Tests send {@code Authorization: Bearer test-token-{userId}} and the
 * mocked decoder returns a synthesized {@code Jwt} with that userId as
 * {@code sub} claim. {@link #mockJwtFor(long)} is the helper. Full Spring
 * Security filter chain runs (BearerTokenAuthenticationFilter +
 * JwtAuthenticationConverter), so role-based AuthZ is also testable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    // Singleton container — started once per JVM via static initializer,
    // shared across ALL test classes that extend AbstractIntegrationTest.
    //
    // Why not @Container + @Testcontainers? Those wire the container's
    // lifecycle to the test class — when class A finishes, the container
    // stops, leaving class B to crash on JDBC connect. Since two integration
    // test classes share this static field, we need JVM-scoped lifecycle.
    @ServiceConnection
    static final MySQLContainer<?> MYSQL;

    static {
        MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("cart_service_test")
                .withUsername("test")
                .withPassword("test");
        MYSQL.start();
        Runtime.getRuntime().addShutdownHook(new Thread(MYSQL::stop));
    }

    protected static final WireMockServer wireMock = new WireMockServer(
            WireMockConfiguration.wireMockConfig().dynamicPort()
    );

    @BeforeAll
    static void startWireMock() {
        // Idempotent — multiple integration test classes share this static
        // WireMock instance. Without this guard, the second class's @BeforeAll
        // would fail because the JVM-shared server is already running.
        if (!wireMock.isRunning()) {
            wireMock.start();
        }
    }

    // No @AfterAll stop — JVM exit cleans up the WireMock server. Stopping in
    // @AfterAll breaks any subsequent test class that wants to use the same
    // static instance.

    @AfterEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    /**
     * Both Feign clients point to the shared WireMock — stubs distinguish by
     * URL path. {@code Supplier<String>} form delays evaluation until after
     * {@link #startWireMock()} runs.
     */
    @DynamicPropertySource
    static void registerFeignBaseUrls(DynamicPropertyRegistry registry) {
        registry.add("app.product-service.base-url", wireMock::baseUrl);
        registry.add("app.inventory-service.base-url", wireMock::baseUrl);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private CartItemRepository cartItemRepository;

    /**
     * Mock the JWT decoder — Spring Security filter chain calls this when
     * BearerTokenAuthenticationFilter sees an {@code Authorization: Bearer ...}
     * header. We synthesize a Jwt whose {@code sub} claim encodes the userId
     * embedded in the token value, so tests can authenticate as different
     * users without a real keypair.
     */
    @MockitoBean
    protected JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanupCart() {
        cartItemRepository.deleteAll();
    }

    /**
     * Build a {@code Bearer test-token-{userId}} header value and configure
     * the mock decoder to decode that token into a Jwt whose {@code sub}
     * claim is the userId. Returns the header value ready for
     * {@code mockMvc.perform(...).header("Authorization", ...)}.
     */
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

    /** Convenience overload — match any incoming bearer token to the given userId. */
    protected void mockJwtForAnyToken(long userId) {
        Jwt jwt = Jwt.withTokenValue("any")
                .header("alg", "RS256")
                .claim("sub", String.valueOf(userId))
                .claim("role", "USER")
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(jwt);
    }
}
