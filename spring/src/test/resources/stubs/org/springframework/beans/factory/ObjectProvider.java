package org.springframework.beans.factory;

public interface ObjectProvider<T> extends ObjectFactory<T> { T getIfAvailable(); }
