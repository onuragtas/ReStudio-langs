package org.springframework.boot.context.properties;

@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface ConfigurationPropertiesScan { String[] value() default {}; String[] basePackages() default {}; }
