package org.restudio.cases;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** An application with known autowiring problems. Each class tells the result that IntelliJ IDEA Ultimate gives. */
@SpringBootApplication
public class CasesApplication {
  public static void main(String[] args) {
    SpringApplication.run(CasesApplication.class, args);
  }
}
