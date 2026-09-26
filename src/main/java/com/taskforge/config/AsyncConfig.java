package com.taskforge.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// A dedicated, bounded pool - not the default SimpleAsyncTaskExecutor, which
// spawns a brand new unbounded thread per @Async call with no queue or limit
// at all. Notification creation is deliberately best-effort (see
// NotificationEventListener), but "best-effort" still shouldn't mean
// "unbounded thread creation under load."
@Configuration
@EnableAsync
public class AsyncConfig {

	@Bean(name = "notificationExecutor")
	public Executor notificationExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(10);
		executor.setQueueCapacity(500);
		executor.setThreadNamePrefix("notification-");
		configureGracefulShutdown(executor);
		executor.initialize();
		return executor;
	}

	// Kept separate from notificationExecutor: webhook delivery makes real
	// network calls to third-party endpoints we don't control, which can be
	// slow or hang, and shouldn't be able to starve the fast, purely-local
	// notification-creation work of threads.
	@Bean(name = "webhookExecutor")
	public Executor webhookExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(10);
		executor.setQueueCapacity(500);
		executor.setThreadNamePrefix("webhook-");
		configureGracefulShutdown(executor);
		executor.initialize();
		return executor;
	}

	// server.shutdown=graceful only covers the web server itself (stop
	// accepting new requests, let in-flight ones finish) - these
	// manually-defined executors need the same instruction explicitly, or a
	// shutdown could still cut off an in-flight notification/webhook send
	// mid-task rather than letting it finish first.
	private void configureGracefulShutdown(ThreadPoolTaskExecutor executor) {
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(20);
	}

}
