/*
 * Chart.js bootstrap for the /ux/metric-detail drill-down page. Renders the
 * hash-stacked "Total execution time" and label-level "Mean execution time" charts
 * from the JSON payloads embedded by the server, and wires up hash selection
 * and SQL inspection.
 */
(function () {
  if (typeof Chart === 'undefined' || !window.DashboardCharts) {
    return;
  }

  const totalDataElement = document.getElementById('total-chart-data');
  let selectionData = totalDataElement
    ? window.DashboardCharts.localize(JSON.parse(totalDataElement.textContent)) : null;
  const visible = new Map(selectionData && selectionData.datasets
    ? selectionData.datasets.map(function (dataset) {
      return [dataset.label, true];
    })
    : []);
  let selectedRange = null;
  let dragRange = null;
  let dragStartIndex = null;
  let activeDragCanvas = null;
  let activeDragChart = null;
  let dragging = false;
  const currentUrl = new URL(window.location.href);
  const autoRefreshRanges = new Set(['30m', '1h', '4h']);
  const autoRefreshControl = document.getElementById('auto-refresh-control');
  const autoRefreshToggle = document.getElementById('auto-refresh-toggle');
  const autoRefreshRing = document.getElementById('auto-refresh-ring');
  const autoRefreshSeconds = document.getElementById('auto-refresh-seconds');
  const autoRefreshTimerDisplay = autoRefreshSeconds ? autoRefreshSeconds.parentElement : null;
  const autoRefreshEnabled = autoRefreshRanges.has(currentUrl.searchParams.get('range'))
    && !currentUrl.searchParams.has('from') && !currentUrl.searchParams.has('to');
  const autoRefreshStorageKey = 'insight.ux.metricDetail.autoRefresh';
  const autoRefreshStatus = document.getElementById('auto-refresh-status');
  let pollMetricDetailData = null;
  let autoRefreshTimer = null;
  let autoRefreshInFlight = false;
  let autoRefreshRemaining = 60;

  const updateAutoRefreshDisplay = function () {
    if (autoRefreshTimerDisplay) {
      autoRefreshTimerDisplay.hidden = !autoRefreshToggle.checked;
    }
    if (autoRefreshRing) {
      autoRefreshRing.style.setProperty('--auto-refresh-progress',
        ((60 - autoRefreshRemaining) / 60) * 360 + 'deg');
    }
    if (autoRefreshSeconds) {
      autoRefreshSeconds.textContent = String(autoRefreshRemaining).padStart(2, '\u00a0') + 's';
    }
  };

  const stopAutoRefresh = function () {
    if (autoRefreshTimer !== null) {
      window.clearInterval(autoRefreshTimer);
      autoRefreshTimer = null;
    }
  };

  const startAutoRefresh = function () {
    stopAutoRefresh();
    autoRefreshRemaining = 60;
    updateAutoRefreshDisplay();
    autoRefreshTimer = window.setInterval(function () {
      if (document.visibilityState !== 'visible') {
        return;
      }
      autoRefreshRemaining -= 1;
      updateAutoRefreshDisplay();
      if (autoRefreshRemaining <= 0 && pollMetricDetailData && !autoRefreshInFlight) {
        pollMetricDetailData();
      }
    }, 1000);
  };

  const updateCharts = function () {
    [totalChart, meanChart].forEach(function (chart) {
      if (chart) {
        chart.update('none');
      }
    });
  };

  const selectionOverlay = {
    id: 'metric-detail-selection',
    beforeDatasetsDraw: function (chart) {
      const range = dragRange || selectedRange;
      if (!range || !chart.scales.x || !chart.chartArea || !selectionData) {
        return;
      }
      const start = chart.scales.x.getPixelForValue(range.start);
      const end = chart.scales.x.getPixelForValue(range.end);
      const step = range.end > range.start
        ? (end - start) / (range.end - range.start)
        : chart.chartArea.width / Math.max(1, selectionData.labels.length);
      const left = Math.max(chart.chartArea.left, Math.min(start, end) - step / 2);
      const right = Math.min(chart.chartArea.right, Math.max(start, end) + step / 2);
      const context = chart.ctx;
      context.save();
      context.fillStyle = 'rgba(40, 80, 120, 0.12)';
      context.fillRect(left, chart.chartArea.top, right - left, chart.chartArea.height);
      context.restore();
    }
  };

  const chartFromData = function (
    canvas, chartData, style, yScale, durationUnit, axisTitle, tooltipId, valueFormatter, metricName) {
    if (!chartData.labels || chartData.labels.length === 0) {
      return null;
    }
    const xScale = window.DashboardCharts.buildXScale(chartData.labels, chartData.bucketMinutes);
    const isPoints = style === 'points';
    const isStackedBar = style === 'stacked-bar';
    xScale.stacked = isStackedBar;
    return new Chart(canvas.getContext('2d'), {
      type: isStackedBar ? 'bar' : 'line',
      data: {
        labels: chartData.labels,
        datasets: chartData.datasets.map(function (ds) {
          return isStackedBar
            ? {
              label: ds.label,
              data: ds.data,
              hidden: visible.has(ds.label) && !visible.get(ds.label),
              backgroundColor: ds.backgroundColor,
              categoryPercentage: 1.0,
              barPercentage: 1.0,
              borderWidth: 1,
              borderColor: getComputedStyle(document.body).backgroundColor,
              borderSkipped: false
            }
            : {
              label: ds.label,
              data: ds.data,
              hidden: visible.has(ds.label) && !visible.get(ds.label),
              borderColor: ds.backgroundColor,
              backgroundColor: ds.backgroundColor,
              showLine: !isPoints,
              pointRadius: isPoints ? (ds.maxSeries ? 2 : 3) : 0,
              pointHoverRadius: isPoints ? (ds.maxSeries ? 4 : 5) : 3,
              pointStyle: ds.maxSeries ? 'triangle' : 'circle',
              borderWidth: 2,
              tension: 0.15
            };
        })
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        scales: {
          x: xScale,
          y: {
            type: yScale || 'linear',
            stacked: isStackedBar,
            title: {display: true, text: durationUnit ? axisTitle + ' (' + durationUnit + ')' : axisTitle},
            ticks: {
              callback: function (value) {
                return valueFormatter(value);
              }
            }
          }
        },
        plugins: {
          legend: {display: false},
          tooltip: window.DashboardCharts.htmlTooltip(chartData.labels, tooltipId, function (point) {
            return {
              label: point.dataset.label,
              metric: metricName(point),
              value: valueFormatter(point.parsed.y)
            };
          }),
          sharedCrosshair: window.DashboardCharts.crosshair(chartData)
        },
        interaction: {mode: 'nearest', intersect: false},
        onHover: function (event, elements) {
          window.DashboardCharts.pointerOnHover(event, elements);
        }
      },
      plugins: [selectionOverlay]
    });
  };

  const renderTrendChart = function (dataElId, canvasId, style) {
    const dataEl = document.getElementById(dataElId);
    const canvas = document.getElementById(canvasId);
    if (!dataEl || !canvas) {
      return;
    }
    const chartData = window.DashboardCharts.localize(JSON.parse(dataEl.textContent));
    const isStackedBar = style === 'stacked-bar';
    const maxValue = chartData.labels.reduce(function (max, _, index) {
      const value = chartData.datasets.reduce(function (seriesValue, dataset) {
        const pointValue = Number(dataset.data[index]) || 0;
        return isStackedBar ? seriesValue + pointValue : Math.max(seriesValue, pointValue);
      }, 0);
      return Math.max(max, value);
    }, 0);
    const durationUnit = window.DashboardCharts.durationUnitFor(maxValue);
    return chartFromData(
      canvas, chartData, style, undefined, durationUnit, 'Total time', 'metric-total-tooltip',
      window.DashboardCharts.detailedDuration, function () { return 'Total time'; });
  };

  let totalChart = null;
  let meanChart = null;
  const initialUrlState = new URLSearchParams(window.location.search);
  let totalStyle = initialUrlState.get('chart') === 'line' ? 'line' : 'stacked-bar';
  let meanMode = ['both', 'only', 'max', 'count'].includes(initialUrlState.get('mean'))
    ? initialUrlState.get('mean') : 'both';
  let meanScale = initialUrlState.get('scale') === 'log' ? 'logarithmic' : 'linear';
  let meanView = initialUrlState.get('meanView') === 'lines' ? 'lines' : 'dots';

  const setChartStateUrl = function (name, value) {
    const current = new URLSearchParams(window.location.search);
    current.set(name, value);
    window.history.replaceState(null, '', window.location.pathname + '?' + current.toString());
  };

  const renderTotalChart = function (style) {
    totalStyle = style;
    if (totalChart) {
      totalChart.destroy();
    }
    totalChart = renderTrendChart('total-chart-data', 'total-chart', style);
    const barButton = document.getElementById('metric-chart-type-bar');
    const lineButton = document.getElementById('metric-chart-type-line');
    if (barButton && lineButton) {
      barButton.setAttribute('aria-pressed', String(style === 'stacked-bar'));
      lineButton.setAttribute('aria-pressed', String(style === 'line'));
    }
  };

  const updateMainCharts = function () {
    [totalChart, meanChart].forEach(function (chart) {
      if (!chart) {
        return;
      }
      chart.data.datasets.forEach(function (ds) {
        ds.hidden = !visible.get(ds.label);
      });
      chart.update();
    });
  };

  const renderMeanChart = function (mode, scale, view) {
    const meanDataEl = document.getElementById('mean-chart-data');
    const maxDataEl = document.getElementById('max-chart-data');
    const countDataEl = document.getElementById('count-chart-data');
    const canvas = document.getElementById('mean-chart');
    if (!meanDataEl || !maxDataEl || !countDataEl || !canvas) {
      return;
    }
    meanMode = mode || meanMode;
    meanScale = scale || meanScale;
    meanView = view || meanView;
    if (meanChart) {
      meanChart.destroy();
    }
    const meanData = window.DashboardCharts.localize(JSON.parse(meanDataEl.textContent));
    const maxData = window.DashboardCharts.localize(JSON.parse(maxDataEl.textContent));
    const countData = window.DashboardCharts.localize(JSON.parse(countDataEl.textContent));
    const countMode = meanMode === 'count';
    const datasets = countMode
      ? countData.datasets
      : mode === 'max'
      ? maxData.datasets.map(function (ds) {
        return Object.assign({}, ds, {maxSeries: true});
      })
      : meanData.datasets.map(function (ds) {
        return Object.assign({}, ds, {maxSeries: false});
      });
    if (mode === 'both') {
      maxData.datasets.forEach(function (ds) {
        datasets.push(Object.assign({}, ds, {maxSeries: true}));
      });
    }
    const maxMeanMs = datasets.reduce(function (max, ds) {
      return Math.max(max, ds.data.reduce(function (seriesMax, value) {
        return Math.max(seriesMax, Number(value) || 0);
      }, 0));
    }, 0);
    const durationUnit = window.DashboardCharts.durationUnitFor(maxMeanMs);
    const activeData = countMode ? countData : meanData;
    meanChart = chartFromData(canvas, {
      labels: activeData.labels,
      timestamps: activeData.timestamps,
      datasets: datasets,
      bucketMinutes: activeData.bucketMinutes
    }, countMode ? 'stacked-bar' : meanView === 'lines' ? 'line' : 'points',
    countMode ? 'linear' : meanScale, countMode ? null : durationUnit,
    countMode ? 'Executions' : 'Duration', 'metric-mean-tooltip',
    countMode ? function (value) {
      return Number(value).toLocaleString();
    } : window.DashboardCharts.detailedDuration, function (point) {
      return countMode ? 'Executions' : point.dataset.maxSeries ? 'Max' : 'Mean';
    });
    ['only', 'both', 'max', 'count'].forEach(function (name) {
      const button = document.getElementById('metric-mean-mode-' + name);
      if (button) {
        button.setAttribute('aria-pressed', String(mode === name));
      }
    });
    const scaleToggle = document.getElementById('metric-mean-scale-log');
    if (scaleToggle) {
      scaleToggle.checked = meanScale === 'logarithmic';
    }
    const viewGroup = document.getElementById('metric-mean-view-group');
    if (viewGroup) {
      viewGroup.hidden = countMode;
    }
    const scaleGroup = document.getElementById('metric-mean-scale-group');
    if (scaleGroup) {
      scaleGroup.hidden = countMode;
    }
    ['dots', 'lines'].forEach(function (name) {
      const button = document.getElementById('metric-mean-view-' + name);
      if (button) {
        button.setAttribute('aria-pressed', String(meanView === name));
      }
    });
  };

  const barButton = document.getElementById('metric-chart-type-bar');
  const lineButton = document.getElementById('metric-chart-type-line');
  if (barButton) {
    barButton.addEventListener('click', function () {
      renderTotalChart('stacked-bar');
      setChartStateUrl('chart', 'bar');
    });
  }
  if (lineButton) {
    lineButton.addEventListener('click', function () {
      renderTotalChart('line');
      setChartStateUrl('chart', 'line');
    });
  }
  const meanOnlyButton = document.getElementById('metric-mean-mode-only');
  const meanBothButton = document.getElementById('metric-mean-mode-both');
  const meanMaxButton = document.getElementById('metric-mean-mode-max');
  const meanCountButton = document.getElementById('metric-mean-mode-count');
  if (meanOnlyButton) {
    meanOnlyButton.addEventListener('click', function () {
      renderMeanChart('only');
      setChartStateUrl('mean', 'only');
    });
  }
  if (meanBothButton) {
    meanBothButton.addEventListener('click', function () {
      renderMeanChart('both');
      setChartStateUrl('mean', 'both');
    });
  }
  if (meanMaxButton) {
    meanMaxButton.addEventListener('click', function () {
      renderMeanChart('max');
      setChartStateUrl('mean', 'max');
    });
  }
  if (meanCountButton) {
    meanCountButton.addEventListener('click', function () {
      renderMeanChart('count');
      setChartStateUrl('mean', 'count');
    });
  }
  ['dots', 'lines'].forEach(function (view) {
    const button = document.getElementById('metric-mean-view-' + view);
    if (button) {
      button.addEventListener('click', function () {
        renderMeanChart(meanMode, meanScale, view);
        setChartStateUrl('meanView', view);
      });
    }
  });
  const meanScaleToggle = document.getElementById('metric-mean-scale-log');
  if (meanScaleToggle) {
    meanScaleToggle.addEventListener('change', function () {
      renderMeanChart(meanMode, meanScaleToggle.checked ? 'logarithmic' : 'linear');
      setChartStateUrl('scale', meanScaleToggle.checked ? 'log' : 'linear');
    });
  }

  const chartIndexAt = function (event, chart) {
    if (!chart || !chart.scales.x || !chart.chartArea || !selectionData) {
      return null;
    }
    const position = Chart.helpers.getRelativePosition(event, chart);
    if (position.x < chart.chartArea.left
      || position.x > chart.chartArea.right
      || position.y < chart.chartArea.top
      || position.y > chart.chartArea.bottom) {
      return null;
    }
    const index = Math.round(chart.scales.x.getValueForPixel(position.x));
    return Math.max(0, Math.min(selectionData.labels.length - 1, index));
  };

  const updateSelectionStatus = function () {
    const status = document.getElementById('metric-chart-range-selection');
    const text = document.getElementById('metric-chart-range-selection-text');
    if (!status || !text || !selectedRange || !selectionData) {
      if (status) {
        status.hidden = true;
      }
      return;
    }
    const start = new Date(selectionData.timestamps[selectedRange.start]);
    const end = new Date(selectionData.timestamps[selectedRange.end]
      + selectionData.bucketMinutes * 60 * 1000);
    text.textContent = 'Selected: ' + start.toLocaleString() + ' - ' + end.toLocaleString();
    status.hidden = false;
  };

  const selectionFromUrl = function () {
    const current = new URLSearchParams(window.location.search);
    const from = Date.parse(current.get('from') || '');
    const to = Date.parse(current.get('to') || '');
    if (!Number.isFinite(from) || !Number.isFinite(to) || !selectionData) {
      return null;
    }
    let start = 0;
    while (start < selectionData.timestamps.length && selectionData.timestamps[start] <= from) {
      start++;
    }
    let end = selectionData.timestamps.length - 1;
    while (end >= 0 && selectionData.timestamps[end] >= to) {
      end--;
    }
    return start <= end ? {start: start, end: end} : null;
  };

  const applySelection = function (start, end) {
    if (start === end || !selectionData) {
      return;
    }
    if (start > end) {
      const swap = start;
      start = end;
      end = swap;
    }
    selectedRange = {start: start, end: end};
    updateSelectionStatus();
    updateCharts();
    const bucketMillis = selectionData.bucketMinutes * 60 * 1000;
    const current = new URLSearchParams(window.location.search);
    current.set('from', new Date(selectionData.timestamps[start] - 1).toISOString());
    current.set('to', new Date(selectionData.timestamps[end] + bucketMillis - 1).toISOString());
    window.location.href = window.location.pathname + '?' + current.toString();
  };

  const cancelDrag = function (event) {
    if (!dragging) {
      return;
    }
    dragging = false;
    dragRange = null;
    if (activeDragCanvas && activeDragCanvas.hasPointerCapture(event.pointerId)) {
      activeDragCanvas.releasePointerCapture(event.pointerId);
    }
    activeDragCanvas = null;
    activeDragChart = null;
    updateCharts();
  };

  const finishDrag = function (event) {
    if (!dragging || event.currentTarget !== activeDragCanvas) {
      return;
    }
    const index = chartIndexAt(event, activeDragChart);
    dragging = false;
    if (activeDragCanvas.hasPointerCapture(event.pointerId)) {
      activeDragCanvas.releasePointerCapture(event.pointerId);
    }
    activeDragCanvas = null;
    activeDragChart = null;
    dragRange = null;
    if (index === null || index === dragStartIndex) {
      updateCharts();
      return;
    }
    applySelection(dragStartIndex, index);
    event.preventDefault();
  };

  const attachDragHandlers = function (canvas, chartSupplier) {
    if (!canvas) {
      return;
    }
    canvas.addEventListener('pointerdown', function (event) {
      if (event.button !== 0) {
        return;
      }
      const index = chartIndexAt(event, chartSupplier());
      if (index === null) {
        return;
      }
      dragging = true;
      dragStartIndex = index;
      activeDragCanvas = canvas;
      activeDragChart = chartSupplier();
      dragRange = {start: index, end: index};
      canvas.setPointerCapture(event.pointerId);
      updateCharts();
      event.preventDefault();
    });
    canvas.addEventListener('pointermove', function (event) {
      if (!dragging || canvas !== activeDragCanvas) {
        return;
      }
      const index = chartIndexAt(event, activeDragChart);
      if (index === null) {
        cancelDrag(event);
        return;
      }
      dragRange = {start: Math.min(dragStartIndex, index), end: Math.max(dragStartIndex, index)};
      updateCharts();
      event.preventDefault();
    });
    canvas.addEventListener('pointerup', finishDrag);
    canvas.addEventListener('pointercancel', cancelDrag);
  };

  const filterForm = document.querySelector('.header-filters');
  const rangeSelect = filterForm ? filterForm.querySelector('select[name="range"]') : null;
  if (filterForm && rangeSelect) {
    const customControls = document.getElementById('custom-range-controls');
    const customFrom = document.getElementById('custom-range-from');
    const customTo = document.getElementById('custom-range-to');
    const current = new URLSearchParams(window.location.search);
    const localDateTimeValue = function (value) {
      const date = new Date(value);
      const offset = date.getTimezoneOffset() * 60 * 1000;
      return new Date(date.getTime() - offset).toISOString().slice(0, 16);
    };
    if (customControls && customFrom && customTo && rangeSelect.value === 'custom') {
      customControls.hidden = false;
      customFrom.value = current.get('from') ? localDateTimeValue(current.get('from')) : '';
      customTo.value = current.get('to') ? localDateTimeValue(current.get('to')) : '';
    }
    filterForm.addEventListener('submit', function (event) {
      filterForm.querySelectorAll('.custom-range-bound').forEach(function (input) {
        input.remove();
      });
      if (rangeSelect.value !== 'custom') {
        return;
      }
      if (!customFrom || !customTo || !customFrom.value || !customTo.value) {
        event.preventDefault();
        window.alert('Enter both a custom range start and end.');
        return;
      }
      const from = new Date(customFrom.value);
      const to = new Date(customTo.value);
      if (!(from < to)) {
        event.preventDefault();
        window.alert('The custom range start must be before the end.');
        return;
      }
      ['from', 'to'].forEach(function (name) {
        const input = document.createElement('input');
        input.type = 'hidden';
        input.name = name;
        input.value = (name === 'from' ? from : to).toISOString();
        input.className = 'custom-range-bound';
        filterForm.appendChild(input);
      });
    });
    filterForm.addEventListener('submit', function () {
      filterForm.querySelectorAll('.chart-state-bound').forEach(function (input) {
        input.remove();
      });
      const state = new URLSearchParams(window.location.search);
      [['chart', state.get('chart')], ['mean', state.get('mean')], ['meanView', state.get('meanView')],
        ['scale', state.get('scale')]]
        .forEach(function (entry) {
          if (!entry[1]) {
            return;
          }
          const input = document.createElement('input');
          input.type = 'hidden';
          input.name = entry[0];
          input.value = entry[1];
          input.className = 'chart-state-bound';
          filterForm.appendChild(input);
        });
    });
  }

  window.addEventListener('insight-theme-change', function () {
    window.DashboardCharts.applyTheme();
    window.DashboardCharts.hideHtmlTooltip('metric-total-tooltip');
    window.DashboardCharts.hideHtmlTooltip('metric-mean-tooltip');
    renderTotalChart(totalStyle);
    renderMeanChart(meanMode, meanScale, meanView);
  });

  let hashSeriesButtons;
  const bindHashSeriesButtons = function () {
    hashSeriesButtons = document.querySelectorAll('.hash-series-toggle');
    hashSeriesButtons.forEach(function (button) {
      if (!visible.has(button.dataset.label)) {
        visible.set(button.dataset.label, true);
      }
      button.addEventListener('click', function (event) {
      event.stopPropagation();
      const label = button.dataset.label;
      if (event.ctrlKey || event.metaKey) {
        visible.set(label, !visible.get(label));
      } else {
        const onlyThisSeries = Array.from(visible.entries()).every(function (entry) {
          return entry[0] === label ? entry[1] : !entry[1];
        });
        visible.forEach(function (_, key) {
          visible.set(key, onlyThisSeries || key === label);
        });
      }
      hashSeriesButtons.forEach(function (seriesButton) {
        seriesButton.setAttribute('aria-pressed', String(visible.get(seriesButton.dataset.label)));
      });
      updateMainCharts();
    });
    });
  };
  bindHashSeriesButtons();

  const formatSql = function (sql) {
    return sql
      .replace(/\s+/g, ' ')
      .replace(/\s+(left join|right join|inner join|full join|cross join|from|where|group by|order by|limit|offset)\s+/gi,
        function (_, keyword) {
          return '\n' + keyword.toUpperCase() + ' ';
        })
      .replace(/\s+(and|or)\s+/gi, function (_, keyword) {
        return '\n  ' + keyword.toUpperCase() + ' ';
      });
  };

  const sqlSources = new Map();
  let selectedHash = null;
  document.querySelectorAll('.hash-sql-source').forEach(function (source) {
    const text = source.content ? source.content.textContent : source.textContent;
    sqlSources.set(source.dataset.hash, text.trim());
  });
  const selectHash = function (hash) {
    const row = document.querySelector('.hash-query-row[data-hash="' + hash + '"]');
    const source = sqlSources.get(hash);
    const title = document.getElementById('hash-sql-title');
    const location = document.getElementById('hash-sql-location');
    const content = document.getElementById('hash-sql-content');
    const open = document.getElementById('hash-sql-open');
    if (!row || !title || !location || !content || !open) {
      return;
    }
    selectedHash = hash;
    document.querySelectorAll('.hash-query-row').forEach(function (candidate) {
      candidate.classList.toggle('is-selected', candidate === row);
    });
    title.textContent = hash;
    location.textContent = row.cells[1].textContent.trim();
    content.textContent = source ? formatSql(source) : 'SQL is not available for this hash.';
    const hashUrl = document.querySelector(
      '.hash-query-select[data-hash="' + hash + '"]')?.dataset.hashUrl;
    if (hashUrl) {
      open.href = hashUrl;
      open.hidden = false;
    } else {
      open.hidden = true;
    }
  };
  document.querySelectorAll('.hash-query-select').forEach(function (button) {
    button.addEventListener('click', function () {
      selectHash(button.dataset.hash);
    });
  });

  const resetSqlInspector = function () {
    selectedHash = null;
    const title = document.getElementById('hash-sql-title');
    const location = document.getElementById('hash-sql-location');
    const content = document.getElementById('hash-sql-content');
    const open = document.getElementById('hash-sql-open');
    if (title) {
      title.textContent = 'Select a hash to view SQL';
    }
    if (location) {
      location.textContent = '';
    }
    if (content) {
      content.textContent = '';
    }
    if (open) {
      open.hidden = true;
    }
  };

  const addCell = function (row, text, className) {
    const cell = document.createElement('td');
    if (className) {
      cell.className = className;
    }
    cell.textContent = text;
    row.appendChild(cell);
    return cell;
  };

  const renderHashBreakdown = function (data) {
    const activeHashes = new Set(data.total.datasets.map(function (dataset) {
      return dataset.label;
    }));
    visible.forEach(function (_, hash) {
      if (!activeHashes.has(hash)) {
        visible.delete(hash);
      }
    });
    activeHashes.forEach(function (hash) {
      if (!visible.has(hash)) {
        visible.set(hash, true);
      }
    });

    document.querySelectorAll('.metric-hash-legend').forEach(function (legend) {
      legend.replaceChildren();
      data.hashBreakdown.forEach(function (entry) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'legend-series-toggle hash-series-toggle';
        button.dataset.label = entry.hash;
        button.setAttribute('aria-pressed', String(visible.get(entry.hash)));
        button.setAttribute('aria-label', 'Toggle ' + entry.hash);
        button.title = entry.hash;
        const swatch = document.createElement('span');
        swatch.className = 'legend-swatch';
        swatch.style.backgroundColor = entry.color;
        button.appendChild(swatch);
        legend.appendChild(button);
      });
    });
    bindHashSeriesButtons();

    const body = document.getElementById('hash-query-table-body');
    if (body) {
      body.replaceChildren();
      if (data.hashBreakdown.length === 0) {
        const row = document.createElement('tr');
        const cell = addCell(row, 'No hash-level data.');
        cell.colSpan = 4;
        body.appendChild(row);
      } else {
        data.hashBreakdown.forEach(function (entry) {
          const row = document.createElement('tr');
          row.className = 'hash-query-row';
          row.dataset.hash = entry.hash;
          row.dataset.color = entry.color;
          const hashCell = document.createElement('td');
          const button = document.createElement('button');
          button.type = 'button';
          button.className = 'hash-query-select';
          button.dataset.hash = entry.hash;
          button.dataset.hashUrl = entry.hashUrl || '';
          button.setAttribute('aria-label', 'Show SQL for ' + entry.hash);
          button.title = entry.hash;
          const swatch = document.createElement('span');
          swatch.className = 'hash-swatch';
          swatch.style.backgroundColor = entry.color;
          const hash = document.createElement('code');
          hash.textContent = entry.hash.length <= 8 ? entry.hash : entry.hash.slice(0, 8) + '...';
          button.append(swatch, hash);
          button.addEventListener('click', function () {
            selectHash(entry.hash);
          });
          hashCell.appendChild(button);
          row.appendChild(hashCell);
          const locationCell = addCell(row, '', 'hash-location');
          const location = document.createElement('code');
          location.textContent = entry.location || '';
          locationCell.appendChild(location);
          addCell(row, entry.totalMs, 'numeric');
          addCell(row, entry.meanMs, 'numeric');
          body.appendChild(row);
        });
      }
    }

    sqlSources.clear();
    data.hashBreakdown.forEach(function (entry) {
      sqlSources.set(entry.hash, entry.sql || '');
    });
    const sqlSourceContainer = document.getElementById('hash-sql-sources');
    if (sqlSourceContainer) {
      sqlSourceContainer.replaceChildren();
    }
    if (selectedHash && data.hashBreakdown.some(function (entry) {
      return entry.hash === selectedHash;
    })) {
      selectHash(selectedHash);
    } else {
      resetSqlInspector();
    }
  };

  const renderRecentPlans = function (plans) {
    const body = document.getElementById('recent-plans-body');
    if (!body) {
      return;
    }
    body.replaceChildren();
    if (plans.length === 0) {
      const row = document.createElement('tr');
      const cell = addCell(row, 'No query plans collected for this label.');
      cell.colSpan = 6;
      body.appendChild(row);
      return;
    }
    plans.forEach(function (plan) {
      const row = document.createElement('tr');
      row.className = 'plan-row';
      row.dataset.hash = plan.hash;
      row.dataset.color = plan.color;
      addCell(row, plan.env);
      const hashCell = document.createElement('td');
      const swatch = document.createElement('span');
      swatch.className = 'hash-swatch';
      swatch.style.backgroundColor = plan.color;
      const hash = document.createElement('code');
      hash.textContent = plan.hash;
      hashCell.append(swatch, hash);
      row.appendChild(hashCell);
      const capturedCell = document.createElement('td');
      const captured = document.createElement('a');
      captured.href = plan.url;
      captured.textContent = plan.whenCaptured;
      capturedCell.appendChild(captured);
      row.appendChild(capturedCell);
      addCell(row, plan.queryTimeMs);
      addCell(row, plan.captureCount);
      const changedCell = document.createElement('td');
      if (plan.shapeChanged) {
        const changed = document.createElement('mark');
        changed.textContent = 'Yes';
        changedCell.appendChild(changed);
      } else {
        changedCell.textContent = '\u2014';
      }
      row.appendChild(changedCell);
      body.appendChild(row);
    });
  };

  const renderFamily = function (family) {
    const section = document.getElementById('metric-query-family');
    const body = document.getElementById('family-table-body');
    if (!section || !body) {
      return;
    }
    section.hidden = family.length === 0;
    body.replaceChildren();
    family.forEach(function (member) {
      const row = document.createElement('tr');
      row.className = 'family-row';
      row.classList.toggle('family-row-current', member.current);
      const labelCell = document.createElement('td');
      labelCell.style.paddingLeft = member.indent + 'px';
      const label = document.createElement('a');
      label.href = member.detailUrl;
      label.textContent = member.display;
      const track = document.createElement('div');
      track.className = 'family-bar-track';
      const bar = document.createElement('div');
      bar.className = 'family-bar';
      bar.style.width = member.pct + '%';
      track.appendChild(bar);
      labelCell.append(label, track);
      row.appendChild(labelCell);
      addCell(row, member.count);
      addCell(row, member.totalMs, 'numeric');
      addCell(row, member.meanMs, 'numeric');
      body.appendChild(row);
    });
  };

  const applyPolledData = function (data) {
    if (!data.hasData) {
      return;
    }
    const chartElements = [
      ['total-chart-data', data.total],
      ['mean-chart-data', data.mean],
      ['max-chart-data', data.max],
      ['count-chart-data', data.count]
    ];
    chartElements.forEach(function (entry) {
      document.getElementById(entry[0]).textContent = JSON.stringify(entry[1]);
    });
    selectionData = window.DashboardCharts.localize(data.total);
    window.DashboardCharts.setSharedCrosshairTimestamp(null);
    renderHashBreakdown(data);
    renderRecentPlans(data.recentPlans);
    renderFamily(data.family);
    renderTotalChart(totalStyle);
    renderMeanChart(meanMode, meanScale, meanView);
    selectedRange = selectionFromUrl();
    updateSelectionStatus();
  };

  const poll = function () {
    if (autoRefreshInFlight || document.visibilityState !== 'visible') {
      return;
    }
    autoRefreshInFlight = true;
    const url = new URL('/ux/metric-detail/data', window.location.origin);
    const current = new URLSearchParams(window.location.search);
    ['app', 'env', 'range', 'label', 'from', 'to', 'tz'].forEach(function (name) {
      const value = current.get(name)
        || (name === 'from' || name === 'to' ? '' : (
          document.querySelector('[name="' + name + '"]') || {}).value || '');
      if (value) {
        url.searchParams.set(name, value);
      }
    });
    fetch(url, {headers: {Accept: 'application/json'}, cache: 'no-store'})
      .then(function (response) {
        if (!response.ok) {
          throw new Error('Refresh failed: ' + response.status);
        }
        return response.json();
      })
      .then(function (data) {
        applyPolledData(data);
        autoRefreshRemaining = 60;
        if (autoRefreshStatus) {
          autoRefreshStatus.textContent = '';
          autoRefreshStatus.hidden = true;
        }
        updateAutoRefreshDisplay();
      })
      .catch(function () {
        autoRefreshRemaining = 5;
        if (autoRefreshStatus) {
          autoRefreshStatus.textContent = 'Refresh unavailable';
          autoRefreshStatus.hidden = false;
        }
        updateAutoRefreshDisplay();
      })
      .finally(function () {
        autoRefreshInFlight = false;
      });
  };

  pollMetricDetailData = poll;
  if (autoRefreshEnabled && autoRefreshControl && autoRefreshToggle) {
    autoRefreshControl.hidden = false;
    autoRefreshToggle.checked = window.localStorage.getItem(autoRefreshStorageKey) === 'true';
    autoRefreshToggle.addEventListener('change', function () {
      window.localStorage.setItem(autoRefreshStorageKey, String(autoRefreshToggle.checked));
      if (autoRefreshToggle.checked) {
        startAutoRefresh();
      } else {
        stopAutoRefresh();
        updateAutoRefreshDisplay();
      }
    });
    if (autoRefreshToggle.checked) {
      startAutoRefresh();
    }
  }

  renderTotalChart(totalStyle);
  renderMeanChart(meanMode, meanScale, meanView);
  selectedRange = selectionFromUrl();
  updateSelectionStatus();
  attachDragHandlers(document.getElementById('total-chart'), function () {
    return totalChart;
  });
  attachDragHandlers(document.getElementById('mean-chart'), function () {
    return meanChart;
  });

  const clearSelection = document.getElementById('metric-chart-range-selection-clear');
  if (clearSelection) {
    clearSelection.addEventListener('click', function () {
      const current = new URLSearchParams(window.location.search);
      current.delete('from');
      current.delete('to');
      if (current.get('range') === 'custom') {
        current.set('range', '1h');
      }
      window.location.href = window.location.pathname + '?' + current.toString();
    });
  }
})();
