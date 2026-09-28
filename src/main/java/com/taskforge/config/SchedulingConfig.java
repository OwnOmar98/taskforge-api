package com.taskforge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

// The multi-instance problem - two app instances both running this job would
// both try to send the digest at the same time - is deliberately not solved
// here. The database's unique constraint (see OverdueTaskDigestJob) already
// makes a double-send harmless even so: whichever instance's INSERT loses
// the race just gets ON CONFLICT DO NOTHING. A real fix (a distributed lock,
// e.g. ShedLock) is worth naming as a known gap, not worth building for an
// app that only ever runs as one instance.
@Configuration
@EnableScheduling
public class SchedulingConfig {

	// Defined explicitly because Boot's own auto-configured scheduler backs
	// off whenever any ScheduledExecutorService bean exists - and
	// AsyncConfig's webhookResilienceScheduler is one. Without this, every
	// @Scheduled method silently falls back to that single webhook-resilience
	// thread, where the SSE heartbeat's blocking writes to slow clients could
	// delay webhook retry and timeout callbacks.
	@Bean
	public ThreadPoolTaskScheduler taskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(2);
		scheduler.setThreadNamePrefix("scheduling-");
		scheduler.setWaitForTasksToCompleteOnShutdown(true);
		scheduler.setAwaitTerminationSeconds(20);
		return scheduler;
	}

}
