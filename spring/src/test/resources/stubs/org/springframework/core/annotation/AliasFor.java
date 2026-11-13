package org.springframework.core.annotation;

@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface AliasFor { String value() default ""; String attribute() default ""; Class<? extends java.lang.annotation.Annotation> annotation() default java.lang.annotation.Annotation.class; }
