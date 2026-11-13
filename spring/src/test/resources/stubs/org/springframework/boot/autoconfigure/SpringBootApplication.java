package org.springframework.boot.autoconfigure;

@org.springframework.boot.SpringBootConfiguration @EnableAutoConfiguration @org.springframework.context.annotation.ComponentScan(excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(classes = Object.class))
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface SpringBootApplication { @org.springframework.core.annotation.AliasFor(annotation = EnableAutoConfiguration.class) Class<?>[] exclude() default {}; @org.springframework.core.annotation.AliasFor(annotation = org.springframework.context.annotation.ComponentScan.class, attribute = "basePackages") String[] scanBasePackages() default {}; @org.springframework.core.annotation.AliasFor(annotation = org.springframework.context.annotation.ComponentScan.class, attribute = "basePackageClasses") Class<?>[] scanBasePackageClasses() default {}; }
