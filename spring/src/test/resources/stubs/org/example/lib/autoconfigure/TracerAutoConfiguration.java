package org.example.lib.autoconfigure;

@org.springframework.boot.autoconfigure.AutoConfiguration
@org.springframework.boot.autoconfigure.condition.ConditionalOnClass(name = "org.example.absent.Agent")
public class TracerAutoConfiguration {
  @org.springframework.context.annotation.Bean
  public org.example.lib.Tracer tracer() { return new org.example.lib.Tracer(); }
}
