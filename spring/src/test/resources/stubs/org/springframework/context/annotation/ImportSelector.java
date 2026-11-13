package org.springframework.context.annotation;

public interface ImportSelector { String[] selectImports(Object metadata); }
