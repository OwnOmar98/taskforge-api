package com.taskforge.common.aop.support;

import org.springframework.stereotype.Component;

import com.taskforge.common.aop.LogExecutionTime;
import com.taskforge.common.aop.LogInvocation;

@Component
public class AopTestService {

	@LogExecutionTime
	public void timedMethod() {
	}

	@LogInvocation
	public void loggedMethod() {
	}

	// Calling an annotated method via "this." from inside the same bean never
	// goes through the Spring proxy that carries the aspect - it's a plain
	// Java method call on the raw instance, so neither aspect fires.
	public void selfInvokeTimedMethod() {
		this.timedMethod();
	}

	public void selfInvokeLoggedMethod() {
		this.loggedMethod();
	}

}
