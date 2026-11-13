package org.springframework.context.annotation;

@Conditional(Object.class)
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface Profile { String[] value(); }
