package org.springframework.web.bind.annotation;

@org.springframework.stereotype.Controller
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface RestController { String value() default ""; }
