package org.ebean.monitor;

import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import io.avaje.inject.test.TestScope;
import io.ebean.Database;

@TestScope
@Factory
class TestConfiguration {

  @Bean
  Database database() {
    return Database.builder()
      .name("db")
      .loadFromProperties()
      .build();
  }
}
