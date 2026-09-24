package com.taskforge.common.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// @Around, not @Before/@After: timing needs a single measurement spanning
// both sides of the call, which only @Around's "wrap the whole invocation"
// shape can express - @Before and @After only ever see one side each.
@Aspect
@Component
public class ExecutionTimeAspect {

	private static final Logger log = LoggerFactory.getLogger(ExecutionTimeAspect.class);

	@Around("@annotation(com.taskforge.common.aop.LogExecutionTime)")
	public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
		long start = System.nanoTime();
		try {
			return joinPoint.proceed();
		}
		finally {
			long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
			log.info("{} executed in {}ms", joinPoint.getSignature().toShortString(), elapsedMillis);
		}
	}

}
