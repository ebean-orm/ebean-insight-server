package org.ebean.monitor.ingest;

/**
 * Derive the rollup group name given the full metric name.
 */
class DeriveGroup {

  private static final String L2N = "l2n";
  private static final String L2R = "l2r";
  private static final String IUD = "iud";

  /**
   * Derive and return the rollup group name.
   */
  static String of(String metricName) {
    int pos = metricName.indexOf('.');
    if (pos == -1) {
      return null;
    }
    String prefix = metricName.substring(0, pos);
    if (L2N.equals(prefix)) {
      return l2nGroup(metricName);
    }
    if (L2R.equals(prefix)) {
      return l2rGroup(metricName);
    }
    if (IUD.equals(prefix)) {
      return iudGroup(metricName);
    }
    return null;
  }

  /**
   * Return L2 near cache rollup group name.
   */
  private static String l2nGroup(String metricName) {
    return l2Group("l2n", metricName);
  }

  /**
   * Return L2 remote cache rollup group name.
   */
  private static String l2rGroup(String metricName) {
    return l2Group("l2r", metricName);
  }

  private static String l2Group(String prefix, String metricName) {
    int pos = metricName.lastIndexOf('.');
    if (pos == -1) {
      return null;
    }
    String suffix = metricName.substring(pos);
    return prefix + suffix;
  }

  /**
   * Return the IUD rollup group name.
   */
  private static String iudGroup(String metricName) {
    int pos = metricName.lastIndexOf('.');
    if (pos == -1) {
      return null;
    }
    String suffix = metricName.substring(pos);
    if (suffix.length() > 7) {
      return "iud" + suffix.substring(0, 7);
    } else {
      return "iud" + suffix;
    }
  }
}
