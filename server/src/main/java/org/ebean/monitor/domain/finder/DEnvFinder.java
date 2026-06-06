package org.ebean.monitor.domain.finder;

import io.ebean.Finder;
import org.ebean.monitor.api.Env;
import org.ebean.monitor.domain.DEnv;
import org.ebean.monitor.domain.query.QDEnv;

import java.util.List;

import static org.ebean.monitor.domain.query.QDEnv.Alias.name;

public class DEnvFinder extends Finder<Long,DEnv> {

  /**
   * Construct using the default Database.
   */
  public DEnvFinder() {
    super(DEnv.class);
  }

  public List<Env> findAll() {
    return new QDEnv()
      .select(name)
      .asDto(Env.class)
      .findList();
  }

  public DEnv byName(String envName) {
    return new QDEnv()
      .select(name)
      .name.ieq(envName)
      .findOne();
  }
}
