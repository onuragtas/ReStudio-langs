package org.restudio.petclinic.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class WelcomeController {
  private final ClinicProperties properties;
  private final ObjectMapper mapper;
  private final JdbcTemplate jdbc;
  private final DataSource dataSource;
  private final Environment environment;

  @Value("${server.port:8080}")
  private int port;

  WelcomeController(ClinicProperties properties, ObjectMapper mapper, JdbcTemplate jdbc, DataSource dataSource,
                    Environment environment) {
    this.properties = properties;
    this.mapper = mapper;
    this.jdbc = jdbc;
    this.dataSource = dataSource;
    this.environment = environment;
  }

  @GetMapping("/")
  String welcome() throws Exception {
    return mapper.writeValueAsString(properties.getWelcomeText() + " on port " + port);
  }
}
