package org.springframework.context.annotation;

@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface ComponentScan { String[] value() default {}; String[] basePackages() default {}; Class<?>[] basePackageClasses() default {}; boolean useDefaultFilters() default true; Filter[] includeFilters() default {}; Filter[] excludeFilters() default {}; @interface Filter { Class<?>[] classes() default {}; } }
