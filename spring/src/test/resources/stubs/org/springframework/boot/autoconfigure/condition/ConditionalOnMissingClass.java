package org.springframework.boot.autoconfigure.condition;

@org.springframework.context.annotation.Conditional(Object.class)
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface ConditionalOnMissingClass { String[] value() default {}; }
