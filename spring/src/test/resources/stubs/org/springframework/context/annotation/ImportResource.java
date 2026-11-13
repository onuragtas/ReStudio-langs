package org.springframework.context.annotation;

@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface ImportResource { String[] value() default {}; }
