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
		executor.initialize();
		return executor;
	}

}
