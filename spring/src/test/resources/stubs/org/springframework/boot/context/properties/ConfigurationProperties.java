package org.springframework.boot.context.properties;

@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface ConfigurationProperties { String value() default ""; String prefix() default ""; }
