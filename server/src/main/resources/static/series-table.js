(function () {
  const pageSize = 50;
  const countFormatter = new Intl.NumberFormat(undefined, {maximumFractionDigits: 0});
  const rateFormatter = new Intl.NumberFormat(undefined, {maximumFractionDigits: 3});
  const legendSelectors = {
    query: '[data-chart-legend="query-total"]',
    'web-api': '.web-api-legend',
    'app-component': '.component-legend',
    dml: '.dml-legend'
  };

  document.querySelectorAll('[data-series-table]').forEach(function (root) {
    const family = root.dataset.family;
    const viewParam = root.dataset.viewParam;
    const panelHeader = root.querySelector('.series-table-panel-header');
    const toggleButton = root.id
      ? document.querySelector('[aria-controls="' + root.id + '"]')
      : null;
    if (!family || !viewParam || !panelHeader || !toggleButton) {
      return;
    }

    const zoomChartId = document.body.classList.contains('zoomed-chart')
      ? new URLSearchParams(window.location.search).get('zoom')
      : null;
    const chartId = toggleButton.closest('[data-chart-id]')?.dataset.chartId;
    const isZoomedChart = zoomChartId !== null && zoomChartId === chartId;

    const panelTitle = panelHeader.textContent.trim();
    const title = document.createElement('span');
    title.textContent = panelTitle;
    const hideButton = document.createElement('button');
    hideButton.type = 'button';
    hideButton.className = 'series-table-hide';
    hideButton.textContent = 'Hide table';
    hideButton.setAttribute('aria-label', 'Hide ' + panelTitle);
    panelHeader.replaceChildren(title, hideButton);

    const controls = document.createElement('div');
    controls.className = 'series-table-toolbar';
    const searchLabel = document.createElement('label');
    searchLabel.className = 'series-table-search-label';
    searchLabel.htmlFor = 'series-table-search-' + family;
    searchLabel.textContent = 'Search all series';
    const searchInput = document.createElement('input');
    searchInput.id = searchLabel.htmlFor;
    searchInput.className = 'series-table-search';
    searchInput.type = 'search';
    searchInput.placeholder = 'Search all series';
    searchInput.autocomplete = 'off';
    searchInput.setAttribute('aria-label', 'Search all matching series');
    controls.append(searchLabel, searchInput);

    const resultCount = document.createElement('span');
    resultCount.className = 'series-table-result-count';
    resultCount.setAttribute('aria-live', 'polite');
    controls.append(resultCount);

    const tableScroll = document.createElement('div');
    tableScroll.className = 'series-table-scroll';
    const table = document.createElement('table');
    table.className = root.dataset.hashCount === 'true'
      ? 'series-table series-table-query'
      : 'series-table';
    const tableHead = document.createElement('thead');
    const headerRow = document.createElement('tr');
    const columns = [
      {key: 'label', title: 'Series'},
      {key: 'total', title: 'Total time'},
      {key: 'count', title: root.dataset.countLabel || 'Count'},
      {key: 'rate', title: 'Rate (' + (root.dataset.rateLabel || '/s') + ')'},
      {key: 'mean', title: 'Mean'},
      {key: 'max', title: 'Max'}
    ];
    if (root.dataset.hashCount === 'true') {
      columns.push({key: 'hashCount', title: 'Distinct queries'});
    }
    columns.forEach(function (column) {
      const heading = document.createElement('th');
      heading.scope = 'col';
      if (column.key !== 'hashCount') {
        heading.dataset.sort = column.key;
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'series-table-sort';
        button.dataset.sort = column.key;
        button.textContent = column.title;
        button.addEventListener('click', function () {
          if (state.sort === column.key) {
            state.direction = state.direction === 'asc' ? 'desc' : 'asc';
          } else {
            state.sort = column.key;
            state.direction = column.key === 'label' ? 'asc' : 'desc';
          }
          state.page = 0;
          updateSortHeaders();
          load();
        });
        heading.append(button);
      } else {
        heading.textContent = column.title;
        heading.className = 'series-table-hash-count';
      }
      headerRow.append(heading);
    });
    tableHead.append(headerRow);
    const tableBody = document.createElement('tbody');
    table.append(tableHead, tableBody);
    tableScroll.append(table);

    const navigation = document.createElement('div');
    navigation.className = 'series-table-navigation';
    const previous = document.createElement('button');
    previous.type = 'button';
    previous.textContent = 'Previous';
    previous.addEventListener('click', function () {
      if (state.page > 0) {
        state.page -= 1;
        load();
      }
    });
    const pageStatus = document.createElement('span');
    pageStatus.className = 'series-table-page-status';
    const next = document.createElement('button');
    next.type = 'button';
    next.textContent = 'Next';
    next.addEventListener('click', function () {
      state.page += 1;
      load();
    });
    navigation.append(previous, pageStatus, next);

    const message = document.createElement('p');
    message.className = 'series-table-message';
    message.setAttribute('role', 'status');
    message.setAttribute('aria-live', 'polite');
    message.textContent = 'Choose Table to load all matching series.';
    root.append(controls, tableScroll, navigation, message);

    const state = {page: 0, sort: 'total', direction: 'desc', search: ''};
    let active = false;
    let searchTimer = null;
    let requestId = 0;
    let requestController = null;

    const updateSortHeaders = function () {
      tableHead.querySelectorAll('th[data-sort]').forEach(function (heading) {
        const current = heading.dataset.sort === state.sort;
        heading.setAttribute('aria-sort', current
          ? state.direction === 'asc' ? 'ascending' : 'descending'
          : 'none');
      });
    };

    const updateMode = function (tableMode, updateUrl) {
      active = tableMode;
      root.hidden = !tableMode;
      toggleButton.setAttribute('aria-expanded', String(tableMode));
      if (isZoomedChart) {
        document.body.classList.toggle('zoomed-series-table', tableMode);
      }
      if (updateUrl) {
        const url = new URL(window.location.href);
        if (tableMode) {
          url.searchParams.set(viewParam, 'table');
        } else {
          url.searchParams.delete(viewParam);
        }
        window.history.replaceState(null, '', url.pathname + url.search + url.hash);
      }
      if (tableMode) {
        load();
      } else if (requestController) {
        requestController.abort();
      }
    };

    hideButton.addEventListener('click', function () {
      updateMode(false, true);
      toggleButton.focus();
    });

    const seriesColors = function () {
      const colors = new Map();
      let fallback = 'var(--pico-muted-color)';
      const legend = document.querySelector(legendSelectors[family]);
      if (legend) {
        legend.querySelectorAll('.legend-series-toggle[data-label]').forEach(function (button) {
          const swatch = button.querySelector('.legend-swatch');
          const label = button.dataset.label;
          if (!swatch || !label) {
            return;
          }
          const color = window.getComputedStyle(swatch).backgroundColor;
          if (!color || color === 'rgba(0, 0, 0, 0)') {
            return;
          }
          colors.set(label, color);
          if (label.toLowerCase() === 'other') {
            fallback = color;
          }
        });
      }
      return {colors: colors, fallback: fallback};
    };

    const requestUrl = function () {
      const params = new URLSearchParams();
      const current = new URLSearchParams(window.location.search);
      ['app', 'env', 'range', 'from', 'to', 'tz'].forEach(function (name) {
        if (current.has(name)) {
          params.set(name, current.get(name));
          return;
        }
        const control = document.querySelector('[name="' + name + '"]');
        if (control && control.value) {
          params.set(name, control.value);
        }
      });
      params.set('family', family);
      params.set('search', state.search);
      params.set('sort', state.sort);
      params.set('direction', state.direction);
      params.set('page', String(state.page));
      return '/ux/top/table?' + params.toString();
    };

    const formatDuration = function (micros) {
      return window.DashboardCharts.detailedDuration(Number(micros) / 1000);
    };

    const formatRate = function (count, elapsedSeconds) {
      return rateFormatter.format(Number(count) / elapsedSeconds);
    };

    const numericColumns = function (data) {
      return [
        {key: 'total', max: data.maxTotalMicros, value: function (row) { return row.totalMicros; },
          format: function (row) { return formatDuration(row.totalMicros); }},
        {key: 'count', max: data.maxCount, value: function (row) { return row.count; },
          format: function (row) { return countFormatter.format(Number(row.count)); }},
        {key: 'rate', max: data.maxCount / data.elapsedSeconds,
          value: function (row) { return Number(row.count) / data.elapsedSeconds; },
          format: function (row) { return formatRate(row.count, data.elapsedSeconds); }},
        {key: 'mean', max: data.maxMeanMicros, value: function (row) { return row.meanMicros; },
          format: function (row) { return formatDuration(row.meanMicros); }},
        {key: 'max', max: data.maxMaxMicros, value: function (row) { return row.maxMicros; },
          format: function (row) { return formatDuration(row.maxMicros); }}
      ];
    };

    const renderRows = function (data) {
      const metrics = numericColumns(data);
      const colors = seriesColors();
      const activeMetricKey = state.sort === 'label' ? 'total' : state.sort;
      const activeMetric = metrics.find(function (metric) { return metric.key === activeMetricKey; });
      const rows = document.createDocumentFragment();
      data.rows.forEach(function (row) {
        const tr = document.createElement('tr');
        const labelCell = document.createElement('td');
        labelCell.className = 'series-table-label';
        const label = row.label == null || row.label === '' ? '(unlabelled)' : row.label;
        const swatch = document.createElement('span');
        swatch.className = 'series-table-swatch';
        swatch.setAttribute('aria-hidden', 'true');
        swatch.style.backgroundColor = colors.colors.get(label) || colors.fallback;
        const labelText = document.createElement('span');
        labelText.className = 'series-table-label-text';
        if (row.detailUrl) {
          const link = document.createElement('a');
          link.href = row.detailUrl;
          link.textContent = label;
          labelText.append(link);
        } else {
          labelText.textContent = label;
        }
        labelCell.append(swatch, labelText);
        tr.append(labelCell);

        metrics.forEach(function (metric) {
          const cell = document.createElement('td');
          cell.className = 'series-table-number';
          const text = metric.format(row);
          if (activeMetric && metric.key === activeMetric.key) {
            const valueWrap = document.createElement('div');
            valueWrap.className = 'series-table-bar-value';
            const bar = document.createElement('span');
            bar.className = 'series-table-bar';
            const value = Number(metric.value(row));
            const maximum = Number(metric.max);
            bar.style.width = maximum > 0 ? Math.min(100, Math.max(0, value / maximum * 100)) + '%' : '0%';
            bar.setAttribute('aria-hidden', 'true');
            const textElement = document.createElement('span');
            textElement.className = 'series-table-value';
            textElement.textContent = text;
            valueWrap.append(bar, textElement);
            cell.append(valueWrap);
          } else {
            cell.textContent = text;
          }
          tr.append(cell);
        });
        if (root.dataset.hashCount === 'true') {
          const hashCell = document.createElement('td');
          hashCell.className = 'series-table-number series-table-hash-count';
          hashCell.textContent = countFormatter.format(Number(row.hashCount));
          tr.append(hashCell);
        }
        rows.append(tr);
      });
      tableBody.replaceChildren(rows);
    };

    const load = async function () {
      if (!active) {
        return;
      }
      if (requestController) {
        requestController.abort();
      }
      requestController = new AbortController();
      const currentRequestId = ++requestId;
      root.setAttribute('aria-busy', 'true');
      message.textContent = tableBody.childElementCount > 0 ? 'Updating…' : 'Loading…';
      try {
        const response = await fetch(requestUrl(), {
          headers: {Accept: 'application/json'},
          signal: requestController.signal
        });
        if (!response.ok) {
          throw new Error('HTTP ' + response.status);
        }
        const data = await response.json();
        if (currentRequestId !== requestId) {
          return;
        }
        const lastPage = Math.max(0, Math.ceil(data.totalRows / data.pageSize) - 1);
        if (state.page > lastPage) {
          state.page = lastPage;
          load();
          return;
        }
        renderRows(data);
        const start = data.totalRows === 0 ? 0 : data.page * data.pageSize + 1;
        const end = Math.min(data.totalRows, (data.page + 1) * data.pageSize);
        resultCount.textContent = data.totalRows === 0
          ? '0 series'
          : 'Showing ' + start + '-' + end + ' of ' + countFormatter.format(data.totalRows) + ' series';
        pageStatus.textContent = data.totalRows === 0
          ? 'Page 0 of 0'
          : 'Page ' + (data.page + 1) + ' of ' + (lastPage + 1);
        previous.disabled = data.page <= 0;
        next.disabled = data.page >= lastPage;
        message.textContent = data.totalRows === 0 ? 'No matching series.' : '';
      } catch (error) {
        if (error.name === 'AbortError' || currentRequestId !== requestId) {
          return;
        }
        console.error('Unable to load series table', error);
        message.textContent = 'Unable to load series table: ' + error.message;
      } finally {
        if (currentRequestId === requestId) {
          root.removeAttribute('aria-busy');
        }
      }
    };

    searchInput.addEventListener('input', function () {
      if (searchTimer !== null) {
        window.clearTimeout(searchTimer);
      }
      searchTimer = window.setTimeout(function () {
        state.search = searchInput.value.trim();
        state.page = 0;
        load();
      }, 200);
    });
    toggleButton.addEventListener('click', function () {
      updateMode(toggleButton.getAttribute('aria-expanded') !== 'true', true);
    });
    window.addEventListener('insight-top-data', function () {
      if (active) {
        load();
      }
    });

    updateSortHeaders();
    const initialUrlState = new URLSearchParams(window.location.search);
    const initialTableMode = initialUrlState.get(viewParam) === 'table'
      && (zoomChartId === null || isZoomedChart);
    updateMode(initialTableMode, false);
  });
}());
