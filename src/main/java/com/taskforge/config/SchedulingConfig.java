package com.taskforge.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

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

}
