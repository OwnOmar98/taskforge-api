package com.taskforge.common.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Purely to contrast advice types against ExecutionTimeAspect's @Around:
// this one is handled by a pair of @Before/@After advice methods instead of
// a single method wrapping the call.
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogInvocation {

}
