package org.ebean.monitor.ingest;

import org.ebean.monitor.api.MetricDbData;
import org.ebean.monitor.api.MetricRequest;
import org.ebean.monitor.api.QueryPlanRequest;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DAppDatabase;
import org.ebean.monitor.domain.DAppPod;
import org.ebean.monitor.domain.DEnv;
import org.ebean.monitor.domain.query.QDApp;
import org.ebean.monitor.domain.query.QDAppDatabase;
import org.ebean.monitor.domain.query.QDAppPod;
import org.ebean.monitor.domain.query.QDEnv;

import jakarta.inject.Singleton;
import java.time.Instant;

/**
 * Process the header level properties like App, Environment etc.
 */
@Singleton
class ProcessHeader {

  IngestPlanHeader ingestHeader(QueryPlanRequest request) {
    final DEnv env = lookupEnv(request.environment);
    final DApp app = lookupApp(request.appName);
    return new IngestPlanHeader(env, app);
  }

  /**
   * Process header level properties returning the IngestHeader.
   */
  IngestHeader ingestHeader(MetricRequest request) {
    final Instant eventTime = toInstant(request.eventTime);
    final DEnv env = lookupEnv(request.environment);
    final DApp app = lookupApp(request.appName);
    final DAppPod pod = lookupPod(app, request.instanceId);

    IngestHeader header = new IngestHeader(eventTime, env, app, pod, request.metrics);
    for (MetricDbData db : request.dbs) {
      header.add(db, lookupDb(app, dbName(db.db)));
    }
    return header;
  }

  private static String dbName(String db) {
    return (db == null || db.trim().isEmpty()) ? "db" : db;
  }

  private Instant toInstant(long eventTime) {
    return Instant.ofEpochMilli(eventTime);
  }

  private DAppPod lookupPod(DApp app, String name) {
    if (name == null || name.isEmpty()) {
      return null;
    }
    DAppPod db = new QDAppPod()
      .app.eq(app)
      .name.eq(name)
      .findOne();

    if (db == null) {
      db = new DAppPod(app, name);
      db.save();
    }
    return db;
  }

  private DAppDatabase lookupDb(DApp app, String dbName) {
    DAppDatabase db = new QDAppDatabase()
      .app.eq(app)
      .name.eq(dbName)
      .findOne();

    if (db == null) {
      db = new DAppDatabase(app, dbName);
      db.save();
    }

    return db;
  }

  private DApp lookupApp(String appName) {
    DApp app = new QDApp()
      .name.eq(appName)
      .findOne();

    if (app == null) {
      app = new DApp(appName);
      app.save();
    }

    return app;
  }


  private DEnv lookupEnv(String environment) {
    if (environment == null) {
      environment = "no-environment";
    }

    DEnv env = new QDEnv()
      .name.eq(environment)
      .findOne();

    if (env == null) {
      env = new DEnv(environment);
      env.save();
    }

    return env;
  }
}
