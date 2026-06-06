package org.ebean.monitor.ingest;

import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DEnv;

record IngestPlanHeader(DEnv env, DApp app) {
}
