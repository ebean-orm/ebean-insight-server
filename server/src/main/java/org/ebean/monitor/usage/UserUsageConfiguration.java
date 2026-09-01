package org.ebean.monitor.usage;

import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import io.avaje.jex.spi.JexPlugin;
import io.ebean.Database;

@Factory
final class UserUsageConfiguration {

  @Bean
  UserUsageService userUsageService(Database database) {
    return new UserUsageService(database);
  }

  @Bean
  UserUsageReportingService userUsageReportingService(Database database) {
    return new UserUsageReportingService(database);
  }

  @Bean
  JexPlugin userUsageFilterPlugin(UserUsageService usage) {
    return jex -> jex.filter(new UserUsageFilter(usage));
  }
}
