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

    // generate the migration ddl and xml
    dbMigration.generateMigration();
  }
}
