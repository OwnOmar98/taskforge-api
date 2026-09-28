package com.taskforge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.redis.testcontainers.RedisContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// A single-test class, not a method in a shared one: stopping Postgres mid-
// test permanently kills it for the rest of the class, so no other test can
// safely share this container afterward.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HealthIndicatorTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Container
	@ServiceConnection
	static RedisContainer redis = new RedisContainer("redis:7");

	@Autowired
	private MockMvc mockMvc;

	// Slow (~30s) by nature, not by an oversight: once the container stops,
	// the connection attempt hangs until pgjdbc's own connect/socket timeout
	// gives up - neither Hikari's connection-timeout (only bounds waiting on
	// an existing pool, not this direct driver.connect() call) nor a
	// data-source-properties override for pgjdbc's connectTimeout/
	// socketTimeout changed this in practice, so the real 30s wait was kept
	// rather than leaving non-functional tuning in place.
	@Test
	void readinessGoesDownWhenTheDatabaseBecomesUnreachable() throws Exception {
		mockMvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components.db.status").value("UP"));

		postgres.stop();

		// Relies entirely on Spring Boot's own auto-configured db
		// HealthIndicator - no custom Postgres check was written for this
		// (see WebhookDeliveryHealthIndicator's class comment for why).
		mockMvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value("DOWN"))
				.andExpect(jsonPath("$.components.db.status").value("DOWN"));
	}

}
