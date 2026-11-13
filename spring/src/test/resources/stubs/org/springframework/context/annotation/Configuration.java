package org.springframework.context.annotation;

@org.springframework.stereotype.Component
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface Configuration { String value() default ""; boolean proxyBeanMethods() default true; }
