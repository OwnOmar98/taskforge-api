package com.taskforge.common.aop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.taskforge.common.aop.support.AopTestService;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Proves the AOP self-invocation limitation directly: calling an annotated
// method from outside the bean goes through the Spring proxy and fires the
// aspect; calling the same method via "this." from inside the bean is a
// plain Java call on the raw instance and never reaches the proxy at all.
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AopAdviceTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Autowired
	private AopTestService aopTestService;

	private ListAppender<ILoggingEvent> executionTimeAppender;
	private ListAppender<ILoggingEvent> invocationAppender;

	@BeforeEach
	void attachAppenders() {
		executionTimeAppender = attach(ExecutionTimeAspect.class);
		invocationAppender = attach(LogInvocationAspect.class);
	}

	@AfterEach
	void detachAppenders() {
		detach(ExecutionTimeAspect.class, executionTimeAppender);
		detach(LogInvocationAspect.class, invocationAppender);
	}

	@Test
	void aroundAdviceFiresOnAnExternalCall() {
		aopTestService.timedMethod();

		assertTrue(executionTimeAppender.list.stream()
				.anyMatch(event -> event.getFormattedMessage().contains("executed in")));
	}

	@Test
	void aroundAdviceDoesNotFireOnSelfInvocation() {
		aopTestService.selfInvokeTimedMethod();

		assertTrue(executionTimeAppender.list.isEmpty());
	}

	@Test
	void beforeAndAfterAdviceFireOnAnExternalCall() {
		aopTestService.loggedMethod();

		assertTrue(invocationAppender.list.stream()
				.anyMatch(event -> event.getFormattedMessage().startsWith("Invoking")));
		assertTrue(invocationAppender.list.stream()
				.anyMatch(event -> event.getFormattedMessage().startsWith("Completed")));
	}

	@Test
	void beforeAndAfterAdviceDoNotFireOnSelfInvocation() {
		aopTestService.selfInvokeLoggedMethod();

		assertTrue(invocationAppender.list.isEmpty());
	}

	private ListAppender<ILoggingEvent> attach(Class<?> loggerClass) {
		Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detach(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
		logger.detachAppender(appender);
		appender.stop();
	}

}
