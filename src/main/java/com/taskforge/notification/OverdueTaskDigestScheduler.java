package com.taskforge.notification;

import java.time.LocalDate;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

// A separate bean so the call reaches runForDate through its @Transactional
// proxy. lockAtLeastFor outlasts the job (~1s) so an instance whose trigger
// fires a few seconds late still finds the lock held.
@Component
public class OverdueTaskDigestScheduler {

	private final OverdueTaskDigestJob job;

	public OverdueTaskDigestScheduler(OverdueTaskDigestJob job) {
		this.job = job;
	}

	@Scheduled(cron = "0 0 6 * * *")
	@SchedulerLock(name = "overdue-task-digest", lockAtLeastFor = "PT5M", lockAtMostFor = "PT30M")
	public void run() {
		job.runForDate(LocalDate.now());
	}

}
