package org.ebean.monitor.rollup;

import io.avaje.config.Config;
import io.avaje.moduuid.ModUUID;
import io.ebean.DB;
import io.ebean.Database;
import org.ebean.monitor.domain.DJob;
import org.ebean.monitor.domain.query.QDRollupJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.avaje.inject.PostConstruct;
import io.avaje.inject.PreDestroy;
import jakarta.inject.Singleton;
import jakarta.persistence.OptimisticLockException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

/**
 * Competes to 'own' the rollup job and run it (based on active flag).
 */
@Singleton
public class RollupService implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(RollupService.class);

  private final String owner = ModUUID.newShortId();

  private final long freqSecs = Config.getLong("rollup.freqSecs", 60);

  private final long expireSecs = Config.getLong("rollup.expireSecs", 90);

  private boolean active;

  private final Database database;

  public RollupService(Database database) {
    this.database = database;
  }

  @PostConstruct
  public void start() {
    log.info("rollup job owner:{} freqSecs:{} expireSecs:{}", owner, freqSecs, expireSecs);
    DB.backgroundExecutor().scheduleWithFixedDelay(this, freqSecs, freqSecs, TimeUnit.SECONDS);
  }

  @PreDestroy
  public void stop() {
    log.info("stopping rollup service owner:{}", owner);
    if (active) {
      try {
        final DJob rollup = DJob.find.findRollup();
        if (owner.equals(rollup.getOwner())) {
          rollup.setWhenExpire(Instant.now());
          rollup.save();
          log.error("releasing ownership of rollup job {} ", owner);
          active = false;
        }
      } catch (Exception e) {
        log.error("Error on stop trying to release rollup job", e);
      }
    }
  }

  @Override
  public void run() {
    try {
      checkOwnership();
      if (active) {
        performRollup();
      }
    } catch (Exception e) {
      log.error("Error performing rollup", e);
    }
  }

  private void performRollup() {

    final Instant currentRollupTime = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MINUTES);

    final Instant since = currentRollupTime.minusSeconds(60 * 15);

    final QDRollupJob r = QDRollupJob.alias();

    Instant lastRollupTime =
      new QDRollupJob()
        .select(r.maxEventTime)
        .eventTime.after(since)
        .findSingleAttribute();

    log.info("expected currentRollup:{}  lastRollup:{}", currentRollupTime, lastRollupTime);
    if (lastRollupTime == null || lastRollupTime.isBefore(currentRollupTime)) {
      final Rollup rollup = new Rollup(database, currentRollupTime);
      rollup.rollup();
    } else {
      log.error("skip already existing rollup?");
    }
  }

  private void checkOwnership() {
    final DJob rollup = DJob.find.findRollup();
    if (active) {
      if (!owner.equals(rollup.getOwner())) {
        log.error("Unexpected owner mismatch? {} != {}", rollup.getOwner(), owner);
        active = false;
      } else {
        extendJobExpiry(rollup);
      }
    }

    if (!active) {
      final Instant expire = rollup.getWhenExpire();
      if (expire.isBefore(Instant.now())) {
        obtainOwnership(rollup);
      }
    }
  }

  /**
   * Try to obtain ownership of the rollup job.
   */
  private void obtainOwnership(DJob rollup) {
    try {
      rollup.setOwner(owner);
      extendJobExpiry(rollup);
      active = true;
      log.info("obtained ownership of rollup job - owner:{}", owner);
    } catch (OptimisticLockException e) {
      log.debug("lost race to obtain ownership of rollup job");
    }
  }

  /**
   * Extend the lease/expiry of the rollup job.
   */
  private void extendJobExpiry(DJob rollup) {
    rollup.setWhenExpire(nextExpire());
    rollup.save();
  }

  private Instant nextExpire() {
    return Instant.now().plusSeconds(expireSecs);
  }

}
