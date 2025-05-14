package org.ebean.monitor.cleanup;

import io.avaje.config.Config;
import io.ebean.DB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;

public class CleanupPartitions {

  private static final Logger log = LoggerFactory.getLogger(CleanupPartitions.class);

  private final String schemaName = Config.get("ebean.dbSchema");

  private long count;

  public long run() {

    final int base = Config.getInt("partitions.base", 10);
    final int m1 = Config.getInt("partitions.m1", 30);
    final int m10 = Config.getInt("partitions.m10", 30);
    final int d1 = Config.getInt("partitions.d1", 40);

    cleanup("timed_entry", base);
    cleanup("gauge_entry", base);
    cleanup("timed_m1", m1);
    cleanup("gauge_m1", m1);
    cleanup("timed_m10", m10);
    cleanup("gauge_m10", m10);
    cleanup("timed_d1", d1);
    cleanup("gauge_d1", d1);
    return count;
  }

  private void cleanup(String prefix, int days) {

    final String sql = sql(schemaName, prefix);
    final List<String> names = DB.sqlQuery(sql)
      .mapToScalar(String.class)
      .findList();

    String suffix = LocalDate.now().minusDays(days).toString().replace('-', '_');
    String min = prefix + "_" + suffix;

    log.trace("min partition {}", min);

    for (String name : names) {
      if (name.compareTo(min) < 0) {
        dropTable(name);
      } else {
        log.trace("keeping partition {}", name);
      }
    }
  }

  private void dropTable(String name) {
    log.info("drop partition {}", name);
    DB.sqlUpdate("drop table " + name).executeNow();
    count++;
  }

  private String sql(String schemaName, String tableName) {
    return "select inhrelid::regclass AS child " +
      "from pg_catalog.pg_inherits " +
      "where inhparent = '" + schemaName + "." + tableName + "'::regclass " +
      "order by child";
  }
}
