package org.springframework.boot.autoconfigure;

@AutoConfigurationPackage @org.springframework.context.annotation.Import(AutoConfigurationImportSelector.class)
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface EnableAutoConfiguration { Class<?>[] exclude() default {}; String[] excludeName() default {}; }
