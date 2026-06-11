package org.ebean.monitor.ingest;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Computes a structural "shape" fingerprint of a Postgres text EXPLAIN plan.
 *
 * <p>The fingerprint is a canonicalised skeleton of the plan tree (node types,
 * chosen indexes, relations, sort/group keys and value-masked predicates) plus a
 * SHA-256 hash of that skeleton. Two captures with the same hash are "the same
 * plan"; a differing hash signals a real structural change (scan method, chosen
 * index, join order/method, predicate column/operator).
 *
 * <p>Volatile noise is stripped: costs, row/width estimates, actual times,
 * buffers, Output lists, and bind literals. Time-partition suffixes on index and
 * relation names are canonicalised to a granularity token so calendar-driven
 * partition rollover (e.g. {@code _202606} -> {@code _202607}) is not seen as a
 * change, while a partitioning-scheme change (monthly -> daily) is.
 *
 * <p>Stateless and pure. See the design doc for the full specification (algo v1).
 */
public final class PlanShape {

  /** Normalizer algorithm version. Bump and re-backfill when the rules change. */
  public static final int ALGO = 1;

  static final String TOK_YEAR = "\u27EAyear\u27EB";
  static final String TOK_MONTH = "\u27EAmonth\u27EB";
  static final String TOK_DAY = "\u27EAday\u27EB";
  static final String TOK_WEEK = "\u27EAweek\u27EB";

  // Time-partition suffix patterns, most-specific first; only one fires.
  // Optional 'p' partman prefix and optional '_' component separators.
  private static final Pattern P_DAY =
    Pattern.compile("_p?(?:19|20)\\d{2}_?(?:0[1-9]|1[0-2])_?(?:0[1-9]|[12]\\d|3[01])$");
  private static final Pattern P_WEEK =
    Pattern.compile("_p?(?:19|20)\\d{2}_?w(?:0[1-9]|[1-4]\\d|5[0-3])$");
  private static final Pattern P_MONTH =
    Pattern.compile("_p?(?:19|20)\\d{2}_?(?:0[1-9]|1[0-2])$");
  private static final Pattern P_YEAR =
    Pattern.compile("_p?(?:19|20)\\d{2}$");

  // Predicate masking patterns.
  private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
  private static final Pattern CAST_MULTIWORD = Pattern.compile(
    "::(?:timestamp without time zone|timestamp with time zone|time without time zone|time with time zone|double precision|character varying|bit varying)");
  private static final Pattern CAST_SIMPLE = Pattern.compile("::\"?[A-Za-z_][A-Za-z0-9_]*\"?(?:\\[\\])?");
  private static final Pattern BIND_PARAM = Pattern.compile("\\$\\d+");
  private static final Pattern NUMERIC = Pattern.compile("(?<![\\w.])-?\\d+(?:\\.\\d+)?(?![\\w.])");
  private static final Pattern VALUE_LIST = Pattern.compile("\\?(?:\\s*,\\s*\\?)+");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private static final String COST = "(cost=";

  private PlanShape() {
  }

  /** Skeleton text + its SHA-256 hex hash. */
  public record Fingerprint(String skeleton, String hash) {
  }

  /**
   * Compute the shape fingerprint, or null when there is no usable plan (blank,
   * or a placeholder capture with no plan nodes).
   */
  @Nullable
  public static Fingerprint fingerprint(@Nullable String planText) {
    if (planText == null || planText.isBlank() || !planText.contains(COST)) {
      return null;
    }
    final var skeleton = skeleton(planText);
    if (skeleton.isEmpty()) {
      return null;
    }
    return new Fingerprint(skeleton, sha256Hex(skeleton));
  }

  static String skeleton(String planText) {
    final var out = new StringBuilder();
    int currentDepth = 0;
    final int[] stack = new int[64];
    int sp = 0; // stack pointer (number of entries)

    for (String raw : planText.split("\n")) {
      final String line = stripTrailing(raw);
      if (line.isEmpty()) {
        continue;
      }
      int indent = 0;
      while (indent < line.length() && line.charAt(indent) == ' ') {
        indent++;
      }
      String rest = line.substring(indent);
      if (rest.startsWith("->")) {
        rest = rest.substring(2).stripLeading();
      }
      final int costAt = rest.indexOf(COST);
      if (costAt >= 0) {
        // NODE line
        while (sp > 0 && stack[sp - 1] >= indent) {
          sp--;
        }
        currentDepth = sp;
        if (sp < stack.length) {
          stack[sp++] = indent;
        }
        final String node = canonicalizeIdentifiers(rest.substring(0, costAt).strip());
        append(out, currentDepth, node);
      } else {
        appendDetail(out, currentDepth, rest);
      }
    }
    return out.toString();
  }

  private static void appendDetail(StringBuilder out, int depth, String rest) {
    final String key = detailKey(rest);
    if (key == null) {
      return;
    }
    final String value = rest.substring(key.length()).strip();
    if (value.isEmpty()) {
      return;
    }
    if (isCondKey(key)) {
      append(out, depth, "cond:" + maskPredicate(value));
    } else {
      append(out, depth, "key:" + value);
    }
  }

  @Nullable
  private static String detailKey(String rest) {
    if (rest.startsWith("Index Cond:")) return "Index Cond:";
    if (rest.startsWith("Recheck Cond:")) return "Recheck Cond:";
    if (rest.startsWith("Join Filter:")) return "Join Filter:";
    if (rest.startsWith("Hash Cond:")) return "Hash Cond:";
    if (rest.startsWith("Merge Cond:")) return "Merge Cond:";
    if (rest.startsWith("Filter:")) return "Filter:";
    if (rest.startsWith("Sort Key:")) return "Sort Key:";
    if (rest.startsWith("Group Key:")) return "Group Key:";
    if (rest.startsWith("Presorted Key:")) return "Presorted Key:";
    return null;
  }

  private static boolean isCondKey(String key) {
    return key.endsWith("Cond:") || key.endsWith("Filter:");
  }

  private static void append(StringBuilder out, int depth, String token) {
    if (!out.isEmpty()) {
      out.append('\n');
    }
    out.append(depth).append('|').append(token);
  }

  /**
   * Canonicalise time-partition date suffixes on each whitespace-delimited token
   * (index and relation names) to a granularity token. Other tokens (keywords,
   * aliases) are left untouched.
   */
  static String canonicalizeIdentifiers(String nodeDesc) {
    final String[] tokens = nodeDesc.split(" ");
    final var sb = new StringBuilder(nodeDesc.length());
    for (int i = 0; i < tokens.length; i++) {
      if (i > 0) {
        sb.append(' ');
      }
      sb.append(canonicalizeToken(tokens[i]));
    }
    return sb.toString();
  }

  private static String canonicalizeToken(String token) {
    String t = replaceSuffix(token, P_DAY, TOK_DAY);
    if (t != null) return t;
    t = replaceSuffix(token, P_WEEK, TOK_WEEK);
    if (t != null) return t;
    t = replaceSuffix(token, P_MONTH, TOK_MONTH);
    if (t != null) return t;
    t = replaceSuffix(token, P_YEAR, TOK_YEAR);
    if (t != null) return t;
    return token;
  }

  @Nullable
  private static String replaceSuffix(String token, Pattern p, String granularityToken) {
    final var m = p.matcher(token);
    if (m.find()) {
      return token.substring(0, m.start()) + "_" + granularityToken;
    }
    return null;
  }

  /**
   * Mask bind literals/params in a predicate so value churn is not a change,
   * while column/operator structure is preserved.
   */
  static String maskPredicate(String predicate) {
    String p = STRING_LITERAL.matcher(predicate).replaceAll("?");
    p = CAST_MULTIWORD.matcher(p).replaceAll("");
    p = CAST_SIMPLE.matcher(p).replaceAll("");
    p = BIND_PARAM.matcher(p).replaceAll("?");
    p = NUMERIC.matcher(p).replaceAll("?");
    p = VALUE_LIST.matcher(p).replaceAll("?");
    p = p.toLowerCase(Locale.ROOT);
    return WHITESPACE.matcher(p).replaceAll(" ").trim();
  }

  static String sha256Hex(String s) {
    try {
      final byte[] digest = MessageDigest.getInstance("SHA-256")
        .digest(s.getBytes(StandardCharsets.UTF_8));
      final var sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private static String stripTrailing(String s) {
    int end = s.length();
    while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\r' || s.charAt(end - 1) == '\t')) {
      end--;
    }
    return s.substring(0, end);
  }
}
