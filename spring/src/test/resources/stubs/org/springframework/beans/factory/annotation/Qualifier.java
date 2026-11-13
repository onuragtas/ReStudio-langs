package org.springframework.beans.factory.annotation;

@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface Qualifier { String value() default ""; }
