/*
 * Chart.js bootstrap for the /ux/top dashboard page. Reads the JSON
 * payload embedded by the server (see query-total.mustache) and renders a
 * stacked bar chart (or, when toggled, a non-stacked line chart, one line
 * per label) into #chartjs-canvas. Clicking a non-"Other" segment drills
 * down to /ux/metric-detail for that label.
 */
(function () {
  const currentUrl = new URL(window.location.href);
  const autoRefreshRanges = new Set(['30m', '1h', '4h']);
  const autoRefreshControl = document.getElementById('auto-refresh-control');
  const autoRefreshToggle = document.getElementById('auto-refresh-toggle');
  const autoRefreshRing = document.getElementById('auto-refresh-ring');
  const autoRefreshSeconds = document.getElementById('auto-refresh-seconds');
  const autoRefreshTimerDisplay = autoRefreshSeconds ? autoRefreshSeconds.parentElement : null;
  const autoRefreshEnabled = autoRefreshRanges.has(currentUrl.searchParams.get('range'));
  const autoRefreshStorageKey = 'insight.ux.top.autoRefresh';
  let autoRefreshTimer = null;
  let autoRefreshRemaining = 60;

  let pollTopData = null;
  let autoRefreshInFlight = false;
  const autoRefreshStatus = document.getElementById('auto-refresh-status');

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
      autoRefreshRemaining -= 1;
      updateAutoRefreshDisplay();
      if (autoRefreshRemaining <= 0) {
        if (pollTopData && !autoRefreshInFlight) {
          pollTopData();
        }
      }
    }, 1000);
  };

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

  const layoutToggle = document.getElementById('layout-toggle');
  if (layoutToggle) {
    layoutToggle.addEventListener('click', function () {
      const next = new URL(window.location.href);
      if (next.searchParams.get('layout') === 'compact') {
        next.searchParams.delete('layout');
      } else {
        next.searchParams.set('layout', 'compact');
      }
      window.location.href = next.toString();
    });
  }

  const compactLegendsToggle = document.getElementById('compact-legends-toggle');
  const compactDashboard = document.querySelector('.top-dashboard-compact');
  if (compactLegendsToggle && compactDashboard) {
    compactLegendsToggle.addEventListener('click', function () {
      const visible = compactDashboard.classList.toggle('compact-legends-visible');
      compactLegendsToggle.setAttribute('aria-pressed', String(visible));
      compactLegendsToggle.setAttribute('aria-label', visible ? 'Hide legends' : 'Show legends');
      compactLegendsToggle.title = visible ? 'Hide legends' : 'Show legends';
    });
  }
  const compactChartControlsToggle = document.getElementById('compact-chart-controls-toggle');
  if (compactChartControlsToggle && compactDashboard) {
    compactChartControlsToggle.addEventListener('click', function () {
      const visible = !compactDashboard.classList.toggle('compact-chart-controls-hidden');
      compactChartControlsToggle.setAttribute('aria-pressed', String(visible));
      compactChartControlsToggle.setAttribute('aria-label',
        visible ? 'Hide chart controls' : 'Show chart controls');
      compactChartControlsToggle.title = visible ? 'Hide chart controls' : 'Show chart controls';
    });
  }

  const chartStateNames = [
    'chart', 'mean', 'meanView', 'scale',
    'webChart', 'webMean', 'webMeanView', 'webScale',
    'dmlChart', 'dmlMean', 'dmlMeanView', 'dmlScale'
  ];
  const statePreservingForm = document.querySelector('.header-filters');
  if (statePreservingForm) {
    statePreservingForm.addEventListener('submit', function () {
      statePreservingForm.querySelectorAll('.chart-state-bound').forEach(function (input) {
        input.remove();
      });
      const state = new URLSearchParams(window.location.search);
      chartStateNames.forEach(function (name) {
        const value = state.get(name);
        if (!value) {
          return;
        }
        const input = document.createElement('input');
        input.type = 'hidden';
        input.name = name;
        input.value = value;
        input.className = 'chart-state-bound';
        statePreservingForm.appendChild(input);
      });
    });
  }

  const zoomRangeMinutes = {
    '30m': 30,
    '1h': 60,
    '4h': 240,
    '6h': 360,
    '24h': 1440,
    '2d': 2880,
    '7d': 10080
  };
  const zoomParam = new URLSearchParams(window.location.search).get('zoom');
  const zoomFrame = window.top !== window.self;
  if (zoomParam && zoomFrame) {
    document.body.classList.add('zoomed-chart');
    document.querySelectorAll('.query-total-section').forEach(function (section) {
      section.hidden = section.dataset.chartId !== zoomParam;
    });
    document.querySelectorAll('.top-chart-legend[data-chart-legend]').forEach(function (legend) {
      legend.hidden = legend.dataset.chartLegend !== zoomParam;
    });
    document.querySelectorAll('.chart-expand-btn').forEach(function (button) {
      button.hidden = true;
    });
  }

  const zoomButtons = document.querySelectorAll('.chart-expand-btn');
  if (zoomButtons.length && !zoomParam) {
    const zoomDialog = document.createElement('dialog');
    zoomDialog.className = 'chart-zoom-dialog';
    zoomDialog.innerHTML = '<div class="chart-zoom-header">'
      + '<div class="chart-zoom-controls">'
      + '<button type="button" class="chart-zoom-range-prev" title="Previous window">←</button>'
      + '<button type="button" class="chart-zoom-range-next" title="Next window">→</button>'
      + '<button type="button" class="chart-zoom-range-latest">Latest</button>'
      + '<select class="chart-zoom-range" aria-label="Zoom time range">'
      + '<option value="custom">Current window</option>'
      + '<option value="30m">30 minutes</option><option value="1h">1 hour</option>'
      + '<option value="4h">4 hours</option><option value="6h">6 hours</option>'
      + '<option value="24h">24 hours</option><option value="2d">2 days</option>'
      + '<option value="7d">7 days</option>'
      + '</select>'
      + '<button type="button" class="chart-zoom-close">Close</button>'
      + '</div></div><iframe class="chart-zoom-frame" title="Expanded chart"></iframe>';
    document.body.appendChild(zoomDialog);

    const frame = zoomDialog.querySelector('.chart-zoom-frame');
    const range = zoomDialog.querySelector('.chart-zoom-range');
    let windowStart = null;
    let windowEnd = null;

    const currentWindow = function () {
      const current = new URLSearchParams(window.location.search);
      const from = Date.parse(current.get('from') || '');
      const to = Date.parse(current.get('to') || '');
      if (Number.isFinite(from) && Number.isFinite(to) && from < to) {
        return {start: from, end: to};
      }
      const minutes = zoomRangeMinutes[current.get('range')] || 240;
      return {start: Date.now() - minutes * 60000, end: Date.now()};
    };

    const frameUrl = function (chartId, start, end) {
      const url = new URL(window.location.href);
      url.pathname = '/ux/top';
      url.searchParams.set('zoom', chartId);
      url.searchParams.set('range', 'custom');
      url.searchParams.set('from', new Date(start).toISOString());
      url.searchParams.set('to', new Date(end).toISOString());
      url.searchParams.delete('layout');
      return url.toString();
    };

    const loadFrame = function (chartId, start, end) {
      windowStart = start;
      windowEnd = end;
      frame.src = frameUrl(chartId, start, end);
    };

    const refreshFrame = function (start, end) {
      if (!frame.contentWindow.insightTopDataRefresh) {
        return;
      }
      windowStart = start;
      windowEnd = end;
      frame.contentWindow.insightTopDataRefresh(start, end);
    };

    const openZoom = function (button) {
      const chartId = button.dataset.chartZoom;
      const current = currentWindow();
      range.value = 'custom';
      const currentRange = new URLSearchParams(window.location.search).get('range');
      if (zoomRangeMinutes[currentRange]) {
        range.value = currentRange;
      }
      loadFrame(chartId, current.start, current.end);
      zoomDialog.showModal();
    };

    zoomButtons.forEach(function (button) {
      button.addEventListener('click', function () {
        openZoom(button);
      });
    });
    zoomDialog.querySelector('.chart-zoom-close').addEventListener('click', function () {
      zoomDialog.close();
      frame.removeAttribute('src');
    });
    zoomDialog.addEventListener('click', function (event) {
      if (event.target === zoomDialog) {
        zoomDialog.close();
        frame.removeAttribute('src');
      }
    });
    zoomDialog.querySelector('.chart-zoom-range-prev').addEventListener('click', function () {
      const size = windowEnd - windowStart;
      refreshFrame(windowStart - size, windowEnd - size);
    });
    zoomDialog.querySelector('.chart-zoom-range-next').addEventListener('click', function () {
      const size = windowEnd - windowStart;
      const end = Math.min(Date.now(), windowEnd + size);
      refreshFrame(end - size, end);
    });
    zoomDialog.querySelector('.chart-zoom-range-latest').addEventListener('click', function () {
      const minutes = zoomRangeMinutes[range.value] || Math.max(1, (windowEnd - windowStart) / 60000);
      refreshFrame(Date.now() - minutes * 60000, Date.now());
    });
    range.addEventListener('change', function () {
      const minutes = zoomRangeMinutes[range.value];
      if (minutes) {
        refreshFrame(Date.now() - minutes * 60000, Date.now());
      }
    });
    window.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && zoomDialog.open) {
        zoomDialog.close();
      }
    });
  }

  const dataEl = document.getElementById('chart-data');
  const canvas = document.getElementById('chartjs-canvas');
  const meanCanvas = document.getElementById('top-mean-max-chart');
  if (!dataEl || !canvas || typeof Chart === 'undefined' || !window.DashboardCharts) {
    return;
  }

  let chartData = window.DashboardCharts.localize(JSON.parse(dataEl.textContent));
  let meanData = window.DashboardCharts.localize(
    JSON.parse(document.getElementById('top-mean-data').textContent));
  let maxData = window.DashboardCharts.localize(
    JSON.parse(document.getElementById('top-max-data').textContent));
  let countData = window.DashboardCharts.localize(
    JSON.parse(document.getElementById('top-count-data').textContent));
  if (!chartData.labels || chartData.labels.length === 0) {
    return;
  }
  chartData.timestamps = chartData.timestamps || [];

  const detailUrlFor = function (label) {
    const current = new URLSearchParams(window.location.search);
    const target = new URLSearchParams();
    target.set('app', current.get('app') || '');
    target.set('range', current.get('range') || '');
    target.set('label', label);
    const env = current.get('env');
    if (env) {
      target.set('env', env);
    }
    const timezone = current.get('tz');
    if (timezone) {
      target.set('tz', timezone);
    }
    return '/ux/metric-detail?' + target.toString();
  };

  let chart = null;
  let meanMaxChart = null;
  const initialUrlState = new URLSearchParams(window.location.search);
  let chartType = initialUrlState.get('chart') === 'line' ? 'line' : 'bar';
  let rankingCharts = [];
  let rankingHoverLabel = null;
  let meanMaxMode = ['both', 'only', 'max', 'count'].includes(initialUrlState.get('mean'))
    ? initialUrlState.get('mean') : 'both';
  let meanMaxView = initialUrlState.get('meanView') === 'lines' ? 'lines' : 'dots';
  let meanMaxScale = initialUrlState.get('scale') === 'log' ? 'logarithmic' : 'linear';
  let sharedHoverIndex = null;
  let selectedRange = null;
  let dragRange = null;
  let dragStartIndex = null;
  let activeDragCanvas = null;
  let activeDragChart = null;
  let dragging = false;
  let dragged = false;
  let ignoreNextClick = false;
  const visible = new Map(chartData.datasets.map(function (ds) {
    return [ds.label, true];
  }));

  const setChartStateUrl = function (name, value) {
    const current = new URLSearchParams(window.location.search);
    current.set(name, value);
    window.history.replaceState(null, '', window.location.pathname + '?' + current.toString());
  };

  const sharedTimeCrosshair = {
    id: 'shared-time-crosshair',
    beforeDatasetsDraw: function (currentChart) {
      const visibleRange = dragRange || selectedRange;
      if (!visibleRange
        || (!dragRange && visibleRange.start === 0 && visibleRange.end === chartData.labels.length - 1)
        || !currentChart.scales.x
        || !currentChart.chartArea) {
        return;
      }
      const start = currentChart.scales.x.getPixelForValue(visibleRange.start);
      const end = currentChart.scales.x.getPixelForValue(visibleRange.end);
      const step = visibleRange.end > visibleRange.start
        ? (end - start) / (visibleRange.end - visibleRange.start)
        : currentChart.chartArea.width / Math.max(1, chartData.labels.length);
      const left = Math.max(currentChart.chartArea.left, Math.min(start, end) - step / 2);
      const right = Math.min(currentChart.chartArea.right, Math.max(start, end) + step / 2);
      const context = currentChart.ctx;
      context.save();
      context.fillStyle = 'rgba(40, 80, 120, 0.12)';
      context.fillRect(left, currentChart.chartArea.top, right - left, currentChart.chartArea.height);
      context.restore();
    }
  };

  const updateCharts = function () {
    [chart, meanMaxChart].forEach(function (currentChart) {
      if (currentChart) {
        currentChart.update('none');
      }
    });
  };

  const setSharedHover = function (index) {
    if (sharedHoverIndex === index) {
      return;
    }
    sharedHoverIndex = index;
    window.DashboardCharts.setSharedCrosshairTimestamp(
      index === null ? null : chartData.timestamps[index]);
    updateCharts();
  };

  const chartIndexAt = function (event, currentChart) {
    if (!currentChart || !currentChart.scales.x || !currentChart.chartArea) {
      return null;
    }
    const position = Chart.helpers.getRelativePosition(event, currentChart);
    if (position.x < currentChart.chartArea.left
      || position.x > currentChart.chartArea.right
      || position.y < currentChart.chartArea.top
      || position.y > currentChart.chartArea.bottom) {
      return null;
    }
    const index = Math.round(currentChart.scales.x.getValueForPixel(position.x));
    return Math.max(0, Math.min(chartData.labels.length - 1, index));
  };

  const selectionText = function () {
    if (!selectedRange || !chartData.timestamps || !chartData.timestamps.length) {
      return '';
    }
    const start = new Date(chartData.timestamps[selectedRange.start]);
    const end = new Date(chartData.timestamps[selectedRange.end] + chartData.bucketMinutes * 60 * 1000);
    return start.toLocaleString() + ' - ' + end.toLocaleString();
  };

  const updateSelectionStatus = function () {
    const status = document.getElementById('chart-range-selection');
    const text = document.getElementById('chart-range-selection-text');
    if (!status || !text) {
      return;
    }
    text.textContent = selectedRange ? 'Selected: ' + selectionText() : '';
    status.hidden = !selectedRange;
  };

  const selectionFromUrl = function () {
    const current = new URLSearchParams(window.location.search);
    const from = Date.parse(current.get('from') || '');
    const to = Date.parse(current.get('to') || '');
    if (!Number.isFinite(from) || !Number.isFinite(to) || !chartData.timestamps.length) {
      return null;
    }
    let start = 0;
    while (start < chartData.timestamps.length && chartData.timestamps[start] <= from) {
      start++;
    }
    let end = chartData.timestamps.length - 1;
    while (end >= 0 && chartData.timestamps[end] >= to) {
      end--;
    }
    if (start > end || end < 0 || start >= chartData.timestamps.length) {
      return null;
    }
    return {start: start, end: end};
  };

  const applySelection = function (start, end) {
    if (start === end || !chartData.timestamps || !chartData.timestamps.length) {
      selectedRange = null;
      updateSelectionStatus();
      updateCharts();
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
    const bucketMillis = chartData.bucketMinutes * 60 * 1000;
    const current = new URLSearchParams(window.location.search);
    current.set('from', new Date(chartData.timestamps[start] - 1).toISOString());
    current.set('to', new Date(chartData.timestamps[end] + bucketMillis - 1).toISOString());
    window.location.href = window.location.pathname + '?' + current.toString();
  };

  const mainDatasets = function (isLine) {
    const gapColor = getComputedStyle(document.body).backgroundColor;
    return chartData.datasets.map(function (ds) {
      return isLine
        ? {
          label: ds.label,
          data: ds.data,
          hidden: !visible.get(ds.label),
          borderColor: ds.backgroundColor,
          backgroundColor: ds.backgroundColor,
          fill: false,
          pointRadius: 0,
          borderWidth: 2,
          tension: 0.15
        }
        : {
          label: ds.label,
          data: ds.data,
          hidden: !visible.get(ds.label),
          backgroundColor: ds.backgroundColor,
          categoryPercentage: 1.0,
          barPercentage: 1.0,
          borderWidth: 1,
          borderColor: gapColor,
          borderSkipped: false
        };
    });
  };

  const queryTotalTooltip = function () {
    return window.DashboardCharts.htmlTooltip(chartData.labels, 'query-total-tooltip', function (point) {
      return {
        label: point.dataset.label,
        metric: 'Total time',
        value: window.DashboardCharts.detailedDuration(point.parsed.y)
      };
    });
  };

  const queryStatisticsTooltip = function (activeData, countMode) {
    return window.DashboardCharts.htmlTooltip(activeData.labels, 'query-statistics-tooltip', function (point) {
      const metric = countMode
        ? 'Executions'
        : point.dataset.pointStyle === 'triangle' ? 'Max' : 'Mean';
      return {
        label: point.dataset.label,
        metric: metric,
        value: countMode
          ? Number(point.parsed.y).toLocaleString()
          : window.DashboardCharts.detailedDuration(point.parsed.y)
      };
    });
  };

  const render = function (type) {
    chartType = type;
    window.DashboardCharts.hideHtmlTooltip('query-total-tooltip');
    window.DashboardCharts.hideHtmlTooltip('query-statistics-tooltip');
    if (chart) {
      chart.destroy();
    }
    const isLine = type === 'line';
    const maxTotalMs = chartData.labels.reduce(function (max, _, index) {
      const total = chartData.datasets.reduce(function (sum, ds) {
        return sum + (Number(ds.data[index]) || 0);
      }, 0);
      return Math.max(max, total);
    }, 0);
    const durationUnit = window.DashboardCharts.durationUnitFor(maxTotalMs);
    const xScale = Object.assign(
      window.DashboardCharts.buildXScale(chartData.labels, chartData.bucketMinutes),
      {stacked: !isLine});
    chart = new Chart(canvas.getContext('2d'), {
      type: type,
      data: {
        labels: chartData.labels,
        datasets: mainDatasets(isLine)
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        scales: {
          x: xScale,
          y: {
            stacked: !isLine,
            title: {display: true, text: 'Total time (' + durationUnit + ')'},
            ticks: {
              callback: function (value) {
                return window.DashboardCharts.durationValue(value, durationUnit);
              }
            }
          }
        },
        onClick: function (evt, elements) {
          if (ignoreNextClick) {
            ignoreNextClick = false;
            return;
          }
          if (!elements.length) {
            return;
          }
          const label = chartData.datasets[elements[0].datasetIndex].label;
          if (label !== 'Other') {
            window.location.href = detailUrlFor(label);
          }
        },
        onHover: function (event, elements) {
          window.DashboardCharts.pointerOnHover(event, elements);
          setSharedHover(elements.length ? elements[0].index : null);
          const label = elements.length
            ? chartData.datasets[elements[0].datasetIndex].label : null;
          setRankingHover(label === 'Other' ? null : label);
        },
        plugins: {
          legend: {display: false},
          tooltip: queryTotalTooltip(),
          sharedCrosshair: window.DashboardCharts.crosshair(chartData)
        }
      },
      plugins: [sharedTimeCrosshair]
    });
    canvas.onmouseleave = clearRankingHover;
  };

  const updateMainChart = function () {
    if (!chart) {
      return;
    }
    const isLine = chartType === 'line';
    chart.data.labels = chartData.labels;
    chart.data.datasets = mainDatasets(isLine);
    chart.options.scales.x = Object.assign(
      window.DashboardCharts.buildXScale(chartData.labels, chartData.bucketMinutes),
      {stacked: !isLine});
    const maxTotalMs = chartData.labels.reduce(function (max, _, index) {
      const total = chartData.datasets.reduce(function (sum, ds) {
        return sum + (Number(ds.data[index]) || 0);
      }, 0);
      return Math.max(max, total);
    }, 0);
    const durationUnit = window.DashboardCharts.durationUnitFor(maxTotalMs);
    chart.options.scales.y.ticks.callback = function (value) {
      return window.DashboardCharts.durationValue(value, durationUnit);
    };
    chart.options.scales.y.title.text = 'Total time (' + durationUnit + ')';
    chart.options.plugins.tooltip = queryTotalTooltip();
    chart.options.plugins.sharedCrosshair = window.DashboardCharts.crosshair(chartData);
    chart.update('none');
  };

  const attachDragHandlers = function (dragCanvas, chartSupplier) {
    dragCanvas.addEventListener('pointerdown', function (event) {
      if (event.button !== 0) {
        return;
      }
      const index = chartIndexAt(event, chartSupplier());
      if (index === null) {
        return;
      }
      dragging = true;
      dragged = false;
      dragStartIndex = index;
      activeDragCanvas = dragCanvas;
      activeDragChart = chartSupplier();
      dragRange = {start: index, end: index};
      dragCanvas.setPointerCapture(event.pointerId);
      updateCharts();
      event.preventDefault();
    });

    dragCanvas.addEventListener('pointermove', function (event) {
      if (!dragging || dragCanvas !== activeDragCanvas) {
        return;
      }
      const index = chartIndexAt(event, activeDragChart);
      if (index === null) {
        cancelDrag(event);
        return;
      }
      dragged = index !== dragStartIndex;
      dragRange = {start: Math.min(dragStartIndex, index), end: Math.max(dragStartIndex, index)};
      updateCharts();
      event.preventDefault();
    });

    dragCanvas.addEventListener('pointerup', finishDrag);
    dragCanvas.addEventListener('pointercancel', cancelDrag);
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
    ignoreNextClick = true;
    applySelection(dragStartIndex, index);
    event.preventDefault();
  };

  attachDragHandlers(canvas, function () {
    return chart;
  });
  if (meanCanvas) {
    attachDragHandlers(meanCanvas, function () {
      return meanMaxChart;
    });
  }

  const clearSelection = document.getElementById('chart-range-selection-clear');
  if (clearSelection) {
    clearSelection.addEventListener('click', function () {
      const current = new URLSearchParams(window.location.search);
      current.delete('from');
      current.delete('to');
      if (current.get('range') === 'custom') {
        current.set('range', '4h');
      }
      window.location.href = window.location.pathname + '?' + current.toString();
    });
  }

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
  }

  const updateLegend = function () {
    document.querySelectorAll('.legend-series-toggle:not(.timer-dashboard-series-toggle):not(.jvm-series-toggle)').forEach(function (button) {
      button.setAttribute('aria-pressed', String(visible.get(button.dataset.label)));
    });
  };

  const updateChartVisibility = function () {
    [chart, meanMaxChart].forEach(function (currentChart) {
      if (!currentChart) {
        return;
      }
      currentChart.data.datasets.forEach(function (ds) {
        ds.hidden = !visible.get(ds.label);
      });
      currentChart.update();
    });
  };

  const toggleLegend = function (button, event) {
    const label = button.dataset.label;
    if (event.ctrlKey || event.metaKey) {
      visible.set(label, !visible.get(label));
    } else {
      const onlyThisSeries = Array.from(visible.entries()).every(function (entry) {
        return entry[0] === label ? entry[1] : !entry[1];
      });
      chartData.datasets.forEach(function (ds) {
        visible.set(ds.label, onlyThisSeries || ds.label === label);
      });
    }
    updateChartVisibility();
    updateLegend();
    rankingCharts.forEach(function (entry) {
      entry.chart.update('none');
    });
  };

  const bindLegendButton = function (button) {
    button.addEventListener('click', function (event) {
      toggleLegend(button, event);
    });
  };

  document.querySelectorAll('.legend-series-toggle:not(.timer-dashboard-series-toggle):not(.jvm-series-toggle)')
    .forEach(bindLegendButton);

  const legendDetails = document.querySelector('.legend-toggle');
  if (legendDetails) {
    legendDetails.addEventListener('toggle', function () {
      legendDetails.classList.toggle('is-open', legendDetails.open);
    });
  }

  const barBtn = document.getElementById('chart-type-bar');
  const lineBtn = document.getElementById('chart-type-line');

  const selectType = function (type) {
    render(type);
    setChartStateUrl('chart', type);
    if (barBtn && lineBtn) {
      barBtn.setAttribute('aria-pressed', String(type === 'bar'));
      lineBtn.setAttribute('aria-pressed', String(type === 'line'));
    }
  };

  if (barBtn) {
    barBtn.addEventListener('click', function () {
      selectType('bar');
    });
  }
  if (lineBtn) {
    lineBtn.addEventListener('click', function () {
      selectType('line');
    });
  }

  const withAlpha = function (color, alpha) {
    if (color && color.charAt(0) === '#' && color.length === 7) {
      const value = parseInt(color.slice(1), 16);
      return 'rgba('
        + ((value >> 16) & 255) + ','
        + ((value >> 8) & 255) + ','
        + (value & 255) + ','
        + alpha + ')';
    }
    return color;
  };

  const clearRankingHover = function () {
    rankingHoverLabel = null;
    rankingCharts.forEach(function (entry) {
      entry.chart.setActiveElements([]);
      entry.chart.update('none');
    });
  };

  const setRankingHover = function (label) {
    if (rankingHoverLabel === label) {
      return;
    }
    rankingHoverLabel = label;
    rankingCharts.forEach(function (entry) {
      entry.chart.update('none');
    });
  };

  const rankingHighlight = {
    id: 'ranking-highlight',
    afterDatasetsDraw: function (currentChart) {
      if (!rankingHoverLabel || !currentChart.chartArea) {
        return;
      }
      const labels = currentChart.data.labels || [];
      const activeIndex = labels.indexOf(rankingHoverLabel);
      if (activeIndex < 0) {
        return;
      }
      const activeElement = currentChart.getDatasetMeta(0).data[activeIndex];
      if (!activeElement) {
        return;
      }
      const highlightColor = getComputedStyle(document.documentElement)
        .getPropertyValue('--insight-ranking-hover').trim();
      const properties = activeElement.getProps(['base', 'x', 'y', 'height'], true);
      const context = currentChart.ctx;
      context.save();
      context.fillStyle = highlightColor;
      context.fillRect(
        Math.min(properties.base, properties.x),
        properties.y - properties.height / 2,
        Math.abs(properties.x - properties.base),
        properties.height);
      context.restore();
    }
  };

  const renderRankingChart = function (dataId, canvasId, unit) {
    const rankingDataEl = document.getElementById(dataId);
    const rankingCanvas = document.getElementById(canvasId);
    if (!rankingDataEl || !rankingCanvas) {
      return;
    }
    let rankingData = window.DashboardCharts.localize(JSON.parse(rankingDataEl.textContent));
    if (!rankingData.labels || rankingData.labels.length === 0) {
      return;
    }
    const dataset = rankingData.datasets[0];
    const maxValue = dataset.data.reduce(function (max, value) {
      return Math.max(max, Number(value) || 0);
    }, 0);
    const durationUnit = window.DashboardCharts.durationUnitFor(maxValue);
    const styles = getComputedStyle(document.documentElement);
    const barColor = styles.getPropertyValue('--insight-ranking-bar').trim();
    const hoverColor = styles.getPropertyValue('--insight-ranking-hover').trim();
    const colorsByLabel = new Map(chartData.datasets.map(function (entry) {
      return [entry.label, entry.backgroundColor];
    }));
    const compactValue = function (value) {
      const absolute = Math.abs(value);
      if (absolute >= 1000000) {
        return (value / 1000000).toFixed(1).replace(/\.0$/, '') + 'm';
      }
      if (absolute >= 1000) {
        return (value / 1000).toFixed(1).replace(/\.0$/, '') + 'k';
      }
      return String(Math.round(value));
    };
    const rankingElementsAtEvent = function (chart, event, elements) {
      if (elements.length) {
        return elements;
      }
      const meta = chart.getDatasetMeta(0);
      let closestIndex = -1;
      let closestDistance = Infinity;
      meta.data.forEach(function (bar, index) {
        const distance = Math.abs(bar.y - event.y);
        if (distance <= bar.height / 2 && distance < closestDistance) {
          closestIndex = index;
          closestDistance = distance;
        }
      });
      return closestIndex < 0 ? [] : [{
        datasetIndex: 0,
        index: closestIndex,
        element: meta.data[closestIndex]
      }];
    };
    const rankingChart = new Chart(rankingCanvas.getContext('2d'), {
      type: 'bar',
      data: {
        labels: rankingData.labels,
        datasets: [{
          label: dataset.label,
          data: dataset.data,
          backgroundColor: barColor,
          hoverBackgroundColor: hoverColor,
          borderWidth: 0,
          barPercentage: 1.0,
          categoryPercentage: 1.0
        }]
      },
      options: {
        indexAxis: 'y',
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        layout: {
          padding: {left: 68}
        },
        scales: {
          x: {
            beginAtZero: true,
            ticks: {
              callback: function (value) {
                return window.DashboardCharts.compactDuration(value, durationUnit);
              }
            }
          },
          y: {
            ticks: {
              display: false
            }
          }
        },
        onClick: function (evt, elements) {
          const activeElements = rankingElementsAtEvent(this, evt, elements);
          if (activeElements.length) {
            window.location.href = detailUrlFor(rankingData.labels[activeElements[0].index]);
          }
        },
        onHover: function (event, elements) {
          const activeElements = rankingElementsAtEvent(this, event, elements);
          window.DashboardCharts.pointerOnHover(event, activeElements);
          this.setActiveElements(activeElements);
          const label = activeElements.length ? rankingData.labels[activeElements[0].index] : null;
          setRankingHover(label);
        },
        plugins: {
          legend: {display: false},
          tooltip: {enabled: false},
          rankingLabels: {}
        },
        interaction: {mode: 'nearest', intersect: true}
      },
      plugins: [rankingHighlight, {
        id: 'ranking-bar-labels',
        afterDatasetsDraw: function (chart) {
          const meta = chart.getDatasetMeta(0);
          const context = chart.ctx;
          const colors = window.DashboardCharts.themeColors();
          context.save();
          context.font = '12px sans-serif';
          context.fillStyle = colors.text;
          context.lineJoin = 'round';
          context.textBaseline = 'middle';
          meta.data.forEach(function (bar, index) {
            const x = bar.base + 8;
            const y = bar.y;
            const value = compactValue(rankingData.datasets[0].data[index]);
            const valueX = bar.base - 8;
            context.fillText(rankingData.labels[index], x, y);
            context.textAlign = 'right';
            context.fillText(value, valueX, y);
            const hasHiddenSeries = chartData.datasets.some(function (entry) {
              return !visible.get(entry.label);
            });
            const seriesActive = hasHiddenSeries && visible.get(rankingData.labels[index]);
            if (rankingHoverLabel === rankingData.labels[index] || seriesActive) {
              context.fillStyle = colorsByLabel.get(rankingData.labels[index]) || colors.text;
              const markerSize = 14;
              context.fillRect(
                valueX - context.measureText(value).width - markerSize - 4,
                y - markerSize / 2, markerSize, markerSize);
              context.fillStyle = colors.text;
            }
            context.textAlign = 'left';
          });
          context.restore();
        }
      }]
    });
    rankingCanvas.onmouseleave = clearRankingHover;
    rankingCanvas.onpointerleave = clearRankingHover;
    rankingCharts.push({
      chart: rankingChart,
      labels: rankingData.labels,
      update: function (nextData) {
        rankingData = window.DashboardCharts.localize(nextData);
        rankingChart.data.labels = rankingData.labels;
        rankingChart.data.datasets[0].data = rankingData.datasets.length
          ? rankingData.datasets[0].data : [];
        rankingChart.update('none');
      }
    });
  };

  const meanMaxDatasets = function () {
    const datasets = [];
    if (meanMaxMode === 'count') {
      const gapColor = getComputedStyle(document.body).backgroundColor;
      countData.datasets.forEach(function (ds) {
        datasets.push({
          label: ds.label,
          data: ds.data,
          hidden: !visible.get(ds.label),
          backgroundColor: ds.backgroundColor,
          categoryPercentage: 1.0,
          barPercentage: 1.0,
          borderWidth: 1,
          borderColor: gapColor,
          borderSkipped: false
        });
      });
      return datasets;
    }
    if (meanMaxMode !== 'max') {
      meanData.datasets.forEach(function (ds) {
        datasets.push({
          label: ds.label,
          data: ds.data,
          hidden: !visible.get(ds.label),
          borderColor: ds.backgroundColor,
          backgroundColor: ds.backgroundColor,
          showLine: meanMaxView === 'lines',
          pointRadius: meanMaxView === 'lines' ? 0 : 3,
          pointHoverRadius: meanMaxView === 'lines' ? 0 : 5,
          borderWidth: meanMaxView === 'lines' ? 2 : 1,
          tension: 0.15,
          spanGaps: meanMaxView === 'lines',
          pointStyle: 'circle'
        });
      });
    }
    if (meanMaxMode !== 'only') {
      maxData.datasets.forEach(function (ds) {
        datasets.push({
          label: ds.label,
          data: ds.data,
          hidden: !visible.get(ds.label),
          borderColor: ds.backgroundColor,
          backgroundColor: ds.backgroundColor,
          showLine: meanMaxView === 'lines',
          pointRadius: meanMaxView === 'lines' ? 0 : 2,
          pointHoverRadius: meanMaxView === 'lines' ? 0 : 4,
          borderWidth: meanMaxView === 'lines' ? 2 : 1,
          tension: 0.15,
          spanGaps: meanMaxView === 'lines',
          pointStyle: 'triangle'
        });
      });
    }
    return datasets;
  };

  const renderMeanMaxChart = function (mode, scale, view) {
    const meanCanvas = document.getElementById('top-mean-max-chart');
    if (!meanCanvas) {
      return;
    }
    meanMaxMode = mode || meanMaxMode;
    meanMaxScale = scale || meanMaxScale;
    meanMaxView = view || meanMaxView;
    window.DashboardCharts.hideHtmlTooltip('query-statistics-tooltip');
    if (meanMaxChart) {
      meanMaxChart.destroy();
    }
    const datasets = meanMaxDatasets();
    const activeData = meanMaxMode === 'count' ? countData : meanData;
    const maxValue = datasets.reduce(function (max, ds) {
      return Math.max(max, ds.data.reduce(function (seriesMax, value) {
        return Math.max(seriesMax, Number(value) || 0);
      }, 0));
    }, 0);
    const durationUnit = window.DashboardCharts.durationUnitFor(maxValue);
    const countMode = meanMaxMode === 'count';
    const viewGroup = document.getElementById('top-mean-view-group');
    if (viewGroup) {
      viewGroup.hidden = countMode;
    }
    const scaleToggle = document.getElementById('top-mean-scale-log');
    if (scaleToggle) {
      scaleToggle.closest('label').hidden = countMode;
    }
    meanMaxChart = new Chart(meanCanvas.getContext('2d'), {
      type: countMode ? 'bar' : 'line',
      data: {labels: activeData.labels, datasets: datasets},
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        scales: {
          x: Object.assign(
            window.DashboardCharts.buildXScale(activeData.labels, activeData.bucketMinutes),
            {stacked: countMode}
          ),
          y: {
            type: countMode ? 'linear' : meanMaxScale,
            stacked: countMode,
            title: {
              display: true,
              text: countMode ? 'Executions' : 'Duration (' + durationUnit + ')'
            },
            ticks: {
              callback: function (value) {
                return countMode
                  ? Number(value).toLocaleString()
                  : window.DashboardCharts.durationValue(value, durationUnit);
              }
            }
          }
        },
        onHover: function (event, elements) {
          window.DashboardCharts.pointerOnHover(event, elements);
          setSharedHover(elements.length ? elements[0].index : null);
        },
        plugins: {
          legend: {display: false},
          tooltip: queryStatisticsTooltip(activeData, countMode),
          sharedCrosshair: window.DashboardCharts.crosshair(activeData)
        }
      },
      plugins: [sharedTimeCrosshair]
    });
    ['both', 'only', 'max', 'count'].forEach(function (name) {
      const button = document.getElementById('top-mean-mode-' + name);
      if (button) {
        button.setAttribute('aria-pressed', String(name === mode));
      }
    });
    if (scaleToggle) {
      scaleToggle.checked = meanMaxScale === 'logarithmic';
    }
    ['dots', 'lines'].forEach(function (name) {
      const button = document.getElementById('top-mean-view-' + name);
      if (button) {
        button.setAttribute('aria-pressed', String(meanMaxView === name));
      }
    });
  };

  const updateMeanMaxChart = function () {
    if (!meanMaxChart) {
      return;
    }
    const datasets = meanMaxDatasets();
    const activeData = meanMaxMode === 'count' ? countData : meanData;
    const countMode = meanMaxMode === 'count';
    const viewGroup = document.getElementById('top-mean-view-group');
    if (viewGroup) {
      viewGroup.hidden = countMode;
    }
    const scaleToggle = document.getElementById('top-mean-scale-log');
    if (scaleToggle) {
      scaleToggle.closest('label').hidden = countMode;
    }
    const maxValue = datasets.reduce(function (max, ds) {
      return Math.max(max, ds.data.reduce(function (seriesMax, value) {
        return Math.max(seriesMax, Number(value) || 0);
      }, 0));
    }, 0);
    const durationUnit = window.DashboardCharts.durationUnitFor(maxValue);
    meanMaxChart.data.labels = activeData.labels;
    meanMaxChart.data.datasets = datasets;
    meanMaxChart.options.scales.x = Object.assign(
      window.DashboardCharts.buildXScale(activeData.labels, activeData.bucketMinutes),
      {stacked: countMode});
    meanMaxChart.options.scales.y.type = countMode ? 'linear' : meanMaxScale;
    meanMaxChart.options.scales.y.stacked = countMode;
    meanMaxChart.options.scales.y.title.text = countMode
      ? 'Executions'
      : 'Duration (' + durationUnit + ')';
    meanMaxChart.options.scales.y.ticks.callback = function (value) {
      return countMode
        ? Number(value).toLocaleString()
        : window.DashboardCharts.durationValue(value, durationUnit);
    };
    meanMaxChart.options.plugins.tooltip = queryStatisticsTooltip(activeData, countMode);
    meanMaxChart.options.plugins.sharedCrosshair = window.DashboardCharts.crosshair(activeData);
    meanMaxChart.update('none');
  };

  ['both', 'only', 'max', 'count'].forEach(function (mode) {
    const button = document.getElementById('top-mean-mode-' + mode);
    if (button) {
      button.addEventListener('click', function () {
        renderMeanMaxChart(mode);
        setChartStateUrl('mean', mode);
      });
    }
  });
  ['dots', 'lines'].forEach(function (view) {
    const button = document.getElementById('top-mean-view-' + view);
    if (button) {
      button.addEventListener('click', function () {
        renderMeanMaxChart(meanMaxMode, meanMaxScale, view);
        setChartStateUrl('meanView', view);
      });
    }
  });
  const meanMaxScaleToggle = document.getElementById('top-mean-scale-log');
  if (meanMaxScaleToggle) {
    meanMaxScaleToggle.addEventListener('change', function () {
      renderMeanMaxChart(meanMaxMode, meanMaxScaleToggle.checked ? 'logarithmic' : 'linear');
      setChartStateUrl('scale', meanMaxScaleToggle.checked ? 'log' : 'linear');
    });
  }

  const redrawRankingCharts = function () {
    rankingCharts.forEach(function (entry) {
      entry.chart.destroy();
    });
    rankingCharts = [];
    renderRankingChart('top-by-time-data', 'top-by-time-chart', 'ms');
    renderRankingChart('top-by-mean-data', 'top-by-mean-chart', 'ms');
  };

  const updatePrimaryLegends = function (legend) {
    document.querySelectorAll('.top-chart-legend:not(.timer-dashboard-legend):not(.web-api-legend):not(.jvm-legend)').forEach(function (container) {
      container.replaceChildren();
      legend.forEach(function (entry) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'legend-series-toggle';
        button.dataset.label = entry.group;
        button.setAttribute('aria-pressed', String(visible.get(entry.group)));
        button.setAttribute('aria-label', 'Toggle ' + entry.group);
        button.title = entry.group;
        const swatch = document.createElement('span');
        swatch.className = 'legend-swatch';
        swatch.style.backgroundColor = entry.color;
        button.appendChild(swatch);
        bindLegendButton(button);
        container.appendChild(button);
      });
    });
  };

  const applyPolledData = function (data) {
    const emptyQueryData = !data.queryTotal.labels.length && data.timeRange;
    chartData = window.DashboardCharts.localize(emptyQueryData
      ? window.DashboardCharts.emptyDataForRange(chartData, data.timeRange) : data.queryTotal);
    meanData = window.DashboardCharts.localize(emptyQueryData
      ? window.DashboardCharts.emptyDataForRange(meanData, data.timeRange) : data.mean);
    maxData = window.DashboardCharts.localize(emptyQueryData
      ? window.DashboardCharts.emptyDataForRange(maxData, data.timeRange) : data.max);
    countData = window.DashboardCharts.localize(emptyQueryData
      ? window.DashboardCharts.emptyDataForRange(countData, data.timeRange) : data.count);
    const queryRate = document.getElementById('query-total-rate');
    if (queryRate) {
      queryRate.textContent = data.queryRate + ' qry/s   load: ' + data.queryLoad;
    }
    chartData.timestamps = chartData.timestamps || [];
    chartData.datasets.forEach(function (dataset) {
      if (!visible.has(dataset.label)) {
        visible.set(dataset.label, true);
      }
    });
    updatePrimaryLegends(data.legend || []);
    updateMainChart();
    updateMeanMaxChart();
    if (rankingCharts.length >= 2) {
      rankingCharts[0].update(data.topByTime);
      rankingCharts[1].update(data.topByMean);
    }
    if (selectedRange && chartData.labels.length > 0) {
      selectedRange.end = Math.min(selectedRange.end, chartData.labels.length - 1);
      selectedRange.start = Math.min(selectedRange.start, selectedRange.end);
      updateSelectionStatus();
    }
    window.dispatchEvent(new CustomEvent('insight-top-data', {detail: data}));
  };

  const zoomLegendState = function () {
    return Array.from(document.querySelectorAll('.legend-series-toggle')).filter(function (button) {
      return !button.closest('[hidden]') && !button.closest('.top-chart-legend[hidden]');
    }).map(function (button) {
      return {
        label: button.dataset.label || '',
        pod: button.dataset.pod || '',
        visible: button.getAttribute('aria-pressed') === 'true'
      };
    });
  };

  const restoreZoomLegendState = function (series) {
    series.forEach(function (state) {
      const selector = state.pod
        ? '.legend-series-toggle[data-pod="' + CSS.escape(state.pod) + '"]'
        : '.legend-series-toggle[data-label="' + CSS.escape(state.label) + '"]';
      const button = document.querySelector(selector);
      if (button && (button.getAttribute('aria-pressed') === 'true') !== state.visible) {
        button.dispatchEvent(new MouseEvent('click', {bubbles: true, ctrlKey: true}));
      }
    });
  };

  window.insightTopDataRefresh = function (from, to) {
    const url = new URL('/ux/top/data', window.location.origin);
    const current = new URLSearchParams(window.location.search);
    ['app', 'env'].forEach(function (name) {
      const value = current.get(name);
      if (value) {
        url.searchParams.set(name, value);
      }
    });
    url.searchParams.set('range', 'custom');
    url.searchParams.set('from', new Date(from).toISOString());
    url.searchParams.set('to', new Date(to).toISOString());
    const series = zoomLegendState();
    return fetch(url, {headers: {Accept: 'application/json'}, cache: 'no-store'})
      .then(function (response) {
        if (!response.ok) {
          throw new Error('Refresh failed: ' + response.status);
        }
        return response.json();
      })
      .then(function (data) {
        data.timeRange = {from: from, to: to};
        applyPolledData(data);
        restoreZoomLegendState(series);
      });
  };

  const poll = function () {
    if (autoRefreshInFlight) {
      return;
    }
    autoRefreshInFlight = true;
    const url = new URL('/ux/top/data', window.location.origin);
    const current = new URLSearchParams(window.location.search);
    ['app', 'env', 'range', 'from', 'to'].forEach(function (name) {
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

  pollTopData = poll;

  window.addEventListener('insight-theme-change', function () {
    window.DashboardCharts.applyTheme();
    render(chartType);
    renderMeanMaxChart(meanMaxMode, meanMaxScale);
    redrawRankingCharts();
  });

  selectedRange = selectionFromUrl();
  updateSelectionStatus();
  selectType(chartType);
  updateLegend();
  renderMeanMaxChart(meanMaxMode, meanMaxScale);
  redrawRankingCharts();
})();
