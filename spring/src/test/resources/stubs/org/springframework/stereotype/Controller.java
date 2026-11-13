package org.springframework.stereotype;

@Component
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface Controller { String value() default ""; }
