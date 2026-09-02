# UX development

The server-rendered UX is intentionally split between Java view construction,
Mustache markup, and browser-side static assets.

## Request-to-browser flow

For a page change, follow the request through these layers:

1. A controller under `server/src/main/java/org/ebean/monitor/web` builds the
   page view.
2. A view model under `server/src/main/java/org/ebean/monitor/web/view`
   carries escaped display values and embedded JSON.
3. A Mustache template under `server/src/main/resources/ui` defines the page
   structure.
4. CSS and JavaScript under `server/src/main/resources/static` provide layout,
   theme behavior, charts, and interaction.

The shared shell is `ui/fragments/layout.mustache`. It owns the Ebean logo,
breadcrumb, filters, theme toggle, and common Pico CSS/style includes.

## Main UX pages

- `/ux/top` is the dashboard and ranking view.
- `/ux/metric-detail` is the label drill-down with hash-level charts and SQL
  inspection.
- `/ux/query-hash` shows one query hash, its metadata, statistics, and plans.

The `/ux/top` page always includes the query dashboard. When the selected
application enables them in `DApp.config`, it can also include the predefined
datasource-pool chart for `datasource.pool.size`, the Web API timer dashboard,
the DML timer dashboard for `ebean.dml`, the application-component timer
dashboard for `app.component`, or JVM charts. Applications without an optional
dashboard flag do not get its chart section.

The optional timer dashboards use the same total-time and mean/max/count chart
controls. The application-component section is rendered directly below Web
API charts, while DML remains below the core query/ranking charts.

The `/ux/top` chart payload is embedded in `query-total.mustache` and rendered
by `query-total.js`. Metric-detail payloads and interactions are rendered by
`metric-detail.js`. Shared Chart.js setup, scales, theme colors, and tooltip
formatting belong in `dashboard-charts.js`.

Dashboard-specific sections are enabled per application through the JSONB
configuration on `DApp`. Missing dashboard flags are disabled by default, so
the common query dashboard remains safe for applications that do not report
optional infrastructure metrics.

## Chart and URL conventions

- Keep chart state in URL parameters when it affects navigation or reload
  behavior.
- Keep the two primary charts synchronized when they represent the same time
  series.
- Preserve `null` data points for buckets with no measurement; zero means a
  real measured zero.
- Reuse `DashboardCharts.tooltipOptions` and duration formatters so `/ux/top`
  and `/ux/metric-detail` remain visually consistent.
- Keep Chart.js's built-in legend disabled when the page provides an accessible
  HTML legend.
- Escape `<` in JSON embedded inside HTML script elements.

## Templates and static assets

Mustache templates are compiled into server-side generated code during the
Maven build; the running server does not read template source files directly.
After changing a template, JavaScript, or CSS file:

1. Run the relevant syntax/build checks.
2. Rebuild the server so the generated template code and packaged resources
   are updated.
3. Restart the running server.
4. Hard-refresh the browser if the old asset is cached.

Do not put large application logic in Mustache templates. Add display shaping
to the view/controller and interaction logic to the page JavaScript.
