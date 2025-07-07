package org.ebean.monitor;

import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import io.avaje.inject.test.TestScope;
import io.ebean.Database;
import io.ebean.test.containers.PostgresContainer;

@TestScope
@Factory
class TestConfiguration {

  @Bean
  PostgresContainer postgresContainer() {
    return PostgresContainer.builder("17")
      .dbName("ebean_insight")
      .user("ebean_insight")
      .build()
      .start();
  }

  @Bean
  Database database(PostgresContainer postgresContainer) {
    return postgresContainer.ebean().builder()
      .ddlRun(true)
//      .runMigration(true)
      .build();
  }
}
