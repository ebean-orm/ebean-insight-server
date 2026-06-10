package main;

import io.ebean.annotation.Platform;
import io.ebean.dbmigration.DbMigration;

/**
 * Generate the DB Migration.
 */
public class GenerateDbMigration {

  public static void main(String[] args) throws Exception {

    DbMigration dbMigration = DbMigration.create();
    dbMigration.setPlatform(Platform.POSTGRES);
    dbMigration.setIncludeIndex(true);
    // Don't (re)generate the built-in partition helper script - the committed
    // I__partition_help.sql is hand-tuned (UNLOGGED, 1-min cadence) and must not
    // be clobbered on regeneration.
    dbMigration.setIncludeBuiltInPartitioning(false);

    // generate the migration ddl and xml
    dbMigration.generateMigration();
  }
}
