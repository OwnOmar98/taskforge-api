package com.taskforge.common.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// A pointcut marker, not a config carrier: ExecutionTimeAspect's @Around
// advice matches on this annotation's presence alone. Deliberately not a
// package/method-name pointcut - those break silently on a rename or a move,
// this one doesn't.
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogExecutionTime {

}
