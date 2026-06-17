package org.ebean.monitor.ingest;

import io.ebean.Database;
import jakarta.inject.Singleton;
import org.ebean.monitor.domain.DQueryPlan;
import org.ebean.monitor.domain.query.QDQueryPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Idempotent, batched backfill of {@code plan_shape} / {@code plan_shape_hash} /
 * {@code plan_shape_algo} for query plans captured before plan-shape fingerprinting
 * was added, or fingerprinted with an older {@link PlanShape#ALGO} version.
 *
 * <p>Safe to run repeatedly: it only processes rows that have a plan but are missing
 * a fingerprint or carry an older algorithm version. Re-running after a {@code ALGO}
 * bump re-fingerprints the affected rows.
 */
@Singleton
public class PlanShapeBackfill {

  private static final Logger log = LoggerFactory.getLogger(PlanShapeBackfill.class);

  private static final int BATCH_SIZE = 200;

  private final Database database;

  public PlanShapeBackfill(Database database) {
    this.database = database;
  }

  /**
   * Process all rows needing a (re)fingerprint, in id-ordered batches.
   *
   * @return the number of rows updated.
   */
  public int run() {
    int total = 0;
    int lastId = 0;
    while (true) {
      final List<DQueryPlan> batch = nextBatch(lastId);
      if (batch.isEmpty()) {
        break;
      }
      for (DQueryPlan plan : batch) {
        lastId = Math.max(lastId, plan.getId());
      }
      total += apply(batch);
    }
    if (total > 0) {
      log.info("PlanShape backfill updated {} query plans to algo {}", total, PlanShape.ALGO);
    }
    return total;
  }

  private List<DQueryPlan> nextBatch(int afterId) {
    return new QDQueryPlan()
      .plan.isNotNull()
      .id.greaterThan(afterId)
      .or()
        .planShapeHash.isNull()
        .planShapeAlgo.isNull()
        .planShapeAlgo.lessThan(PlanShape.ALGO)
      .endOr()
      .orderBy().id.asc()
      .setMaxRows(BATCH_SIZE)
      .findList();
  }

  private int apply(List<DQueryPlan> batch) {
    int updated = 0;
    for (DQueryPlan plan : batch) {
      final var shape = PlanShape.fingerprint(plan.plan());
      if (shape != null) {
        plan.setPlanShape(shape.skeleton())
          .setPlanShapeHash(shape.hash())
          .setPlanShapeAlgo(PlanShape.ALGO);
        updated++;
      }
    }
    if (updated > 0) {
      database.saveAll(batch);
    }
    return updated;
  }
}
