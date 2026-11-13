package org.restudio.petclinic.system;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "petclinic")
public class ClinicProperties {
  /** The text on the welcome page. */
  private String welcomeText = "Welcome";
  /** Whether the caches keep null values. */
  private boolean allowNullValues;

  public String getWelcomeText() { return welcomeText; }

  public void setWelcomeText(String welcomeText) { this.welcomeText = welcomeText; }

  public boolean isAllowNullValues() { return allowNullValues; }

  public void setAllowNullValues(boolean allowNullValues) { this.allowNullValues = allowNullValues; }
}
