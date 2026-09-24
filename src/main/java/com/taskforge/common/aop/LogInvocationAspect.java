package com.taskforge.common.aop;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// @Before/@After, not @Around: each advice method only ever sees its own
// side of the call and can't see or influence the return value - unlike
// ExecutionTimeAspect's @Around, which needs both sides in one method to
// measure a single elapsed duration.
@Aspect
@Component
public class LogInvocationAspect {

	private static final Logger log = LoggerFactory.getLogger(LogInvocationAspect.class);

	@Before("@annotation(com.taskforge.common.aop.LogInvocation)")
	public void logBefore(JoinPoint joinPoint) {
		log.info("Invoking {}", joinPoint.getSignature().toShortString());
	}

	@After("@annotation(com.taskforge.common.aop.LogInvocation)")
	public void logAfter(JoinPoint joinPoint) {
		log.info("Completed {}", joinPoint.getSignature().toShortString());
	}

}
