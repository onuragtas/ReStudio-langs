package org.restudio.petclinic.system;

import org.springframework.boot.autoconfigure.cache.CacheManagerCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableCaching
class CacheConfiguration {
  @Bean
  CacheManagerCustomizer<ConcurrentMapCacheManager> petclinicCacheCustomizer(ClinicProperties properties) {
    return manager -> manager.setAllowNullValues(properties.isAllowNullValues());
  }
}
