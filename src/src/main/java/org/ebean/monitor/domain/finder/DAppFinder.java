package org.ebean.monitor.domain.finder;

import io.ebean.Finder;
import org.ebean.monitor.api.App;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.query.QDApp;

import java.util.List;

import static org.ebean.monitor.domain.query.QDApp.Alias.name;
import static org.ebean.monitor.domain.query.QDApp.Alias.id;

public class DAppFinder extends Finder<Long, DApp> {

  /**
   * Construct using the default Database.
   */
  public DAppFinder() {
    super(DApp.class);
  }

  public List<App> findAll() {
    return new QDApp()
      .select(id, name)
      .name.desc()
      .asDto(App.class)
      .findList();
  }
}
