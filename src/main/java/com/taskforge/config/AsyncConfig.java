package com.taskforge.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Dedicated, bounded pools - not the default SimpleAsyncTaskExecutor, which
// spawns a brand new unbounded thread per @Async call with no queue or limit
// at all.
@Configuration
@EnableAsync
public class AsyncConfig {

	// Runs OutboxEventRelay's post-commit nudges. One thread is enough: a pass
	// publishes a whole batch, and nudges coalesce while one is pending.
	@Bean(name = "eventRelayExecutor")
	public Executor eventRelayExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(1);
		executor.setThreadNamePrefix("event-relay-");
		configureGracefulShutdown(executor);
		executor.initialize();
		return executor;
	}

	// Webhook delivery makes real network calls to third-party endpoints we
	// don't control, which can be slow or hang, so it gets a pool of its own.
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

	// WebhookSender composes Retry/CircuitBreaker/TimeLimiter programmatically
	// (per-organization instance names, which the declarative @Retry/
	// @CircuitBreaker/@TimeLimiter annotations can't express - their `name` is
	// a compile-time constant) - their async decorators need a scheduler to
	// run retry backoff and timeout callbacks on, distinct from webhookExecutor
	// which runs the actual HTTP call itself. Single-threaded: these callbacks
	// are lightweight scheduling work, not the call, so they don't need a pool.
	@Bean(name = "webhookResilienceScheduler")
	public ScheduledExecutorService webhookResilienceScheduler() {
		return Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "webhook-resilience");
			thread.setDaemon(true);
			return thread;
		});
	}

	// server.shutdown=graceful only covers the web server itself (stop
	// accepting new requests, let in-flight ones finish) - these
	// manually-defined executors need the same instruction explicitly, or a
	// shutdown could still cut off an in-flight relay pass or webhook send
	// mid-task rather than letting it finish first.
	private void configureGracefulShutdown(ThreadPoolTaskExecutor executor) {
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(20);
	}

}
