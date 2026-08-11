package org.ebean.monitor.web;

import io.avaje.htmx.api.Html;
import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Path;
import io.avaje.http.api.QueryParam;
import io.avaje.jex.http.NotFoundException;
import org.ebean.monitor.v1.web.V1QueryService;
import org.ebean.monitor.web.view.Breadcrumb;
import org.ebean.monitor.web.view.QuerySqlView;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * Shows the SQL stored for one metric hash, including hashes without a
 * captured query plan.
 */
@Html
@Controller
@Path("/ux")
public class UIQuerySqlController {

  private final V1QueryService service;

  public UIQuerySqlController(V1QueryService service) {
    this.service = service;
  }

  @Get("query-sql")
  QuerySqlView querySql(@QueryParam("app") String app,
                        @QueryParam("env") @Nullable String env,
                        @QueryParam("range") @Nullable String range,
                        @QueryParam("label") String label,
                        @QueryParam("hash") String hash,
                        @QueryParam("from") @Nullable String fromParam,
                        @QueryParam("to") @Nullable String toParam) {
    final String sql = service.getMetricSql(app, label, hash);
    if (sql == null || sql.isBlank()) {
      throw new NotFoundException("No SQL stored for metric hash " + hash);
    }
    final Instant from = UIQueryTotalController.parseInstant(fromParam, "from");
    final Instant to = UIQueryTotalController.parseInstant(toParam, "to");
    if ((from == null) != (to == null)) {
      throw new io.avaje.jex.http.BadRequestException("Both from and to timestamps are required");
    }
    final String detailUrl = UIQueryTotalController.metricDetailUrl(
      app, env, UIQueryTotalController.rangeKey(range, from, to), label, from, to);
    final Breadcrumb breadcrumb = new Breadcrumb(List.of(
      new Breadcrumb.Item(detailUrl, label),
      new Breadcrumb.Item("SQL")));
    return new QuerySqlView(breadcrumb, app, env == null ? "" : env, label, hash, sql);
  }

}
