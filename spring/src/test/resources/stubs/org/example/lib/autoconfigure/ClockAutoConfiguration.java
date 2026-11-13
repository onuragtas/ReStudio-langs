package org.example.lib.autoconfigure;

@org.springframework.boot.autoconfigure.AutoConfiguration
public class ClockAutoConfiguration {
  @org.springframework.context.annotation.Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
  public org.example.lib.Clock clock() { return new org.example.lib.Clock(); }
}
