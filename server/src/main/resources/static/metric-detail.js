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
  const selectionData = totalDataElement ? JSON.parse(totalDataElement.textContent) : null;
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
  let sharedHoverIndex = null;

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

  const sharedHoverCrosshair = {
    id: 'metric-detail-hover-crosshair',
    afterDraw: function (chart) {
      if (sharedHoverIndex === null || !chart.scales.x || !chart.chartArea) {
        return;
      }
      const x = chart.scales.x.getPixelForValue(sharedHoverIndex);
      const context = chart.ctx;
      context.save();
      context.strokeStyle = 'rgba(40, 80, 120, 0.45)';
      context.lineWidth = 1;
      context.beginPath();
      context.moveTo(x, chart.chartArea.top);
      context.lineTo(x, chart.chartArea.bottom);
      context.stroke();
      context.restore();
    }
  };

  const chartFromData = function (canvas, chartData, style, yScale, durationUnit) {
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
            ticks: durationUnit ? {
              callback: function (value) {
                return window.DashboardCharts.compactDuration(value, durationUnit);
              }
            } : {}
          }
        },
        plugins: {
          legend: {display: false},
          tooltip: window.DashboardCharts.tooltipOptions(chartData.labels, durationUnit ? {
            label: function (context) {
              return context.dataset.label + ': '
                + window.DashboardCharts.detailedDuration(context.raw);
            }
          } : null)
        },
        onHover: function (event, elements) {
          window.DashboardCharts.pointerOnHover(event, elements);
          const index = elements.length ? elements[0].index : null;
          if (sharedHoverIndex !== index) {
            sharedHoverIndex = index;
            updateCharts();
          }
        }
      },
      plugins: [selectionOverlay, sharedHoverCrosshair]
    });
  };

  const renderTrendChart = function (dataElId, canvasId, style) {
    const dataEl = document.getElementById(dataElId);
    const canvas = document.getElementById(canvasId);
    if (!dataEl || !canvas) {
      return;
    }
    const chartData = JSON.parse(dataEl.textContent);
    const isStackedBar = style === 'stacked-bar';
    const maxValue = chartData.labels.reduce(function (max, _, index) {
      const value = chartData.datasets.reduce(function (seriesValue, dataset) {
        const pointValue = Number(dataset.data[index]) || 0;
        return isStackedBar ? seriesValue + pointValue : Math.max(seriesValue, pointValue);
      }, 0);
      return Math.max(max, value);
    }, 0);
    const durationUnit = window.DashboardCharts.durationUnitFor(maxValue);
    return chartFromData(canvas, chartData, style, undefined, durationUnit);
  };

  let totalChart = null;
  let meanChart = null;
  const initialUrlState = new URLSearchParams(window.location.search);
  let totalStyle = initialUrlState.get('chart') === 'line' ? 'line' : 'stacked-bar';
  let meanMode = ['both', 'only', 'max'].includes(initialUrlState.get('mean'))
    ? initialUrlState.get('mean') : 'both';
  let meanScale = initialUrlState.get('scale') === 'log' ? 'logarithmic' : 'linear';

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

  const renderMeanChart = function (mode, scale) {
    const meanDataEl = document.getElementById('mean-chart-data');
    const maxDataEl = document.getElementById('max-chart-data');
    const canvas = document.getElementById('mean-chart');
    if (!meanDataEl || !maxDataEl || !canvas) {
      return;
    }
    meanMode = mode || meanMode;
    meanScale = scale || meanScale;
    if (meanChart) {
      meanChart.destroy();
    }
    const meanData = JSON.parse(meanDataEl.textContent);
    const maxData = JSON.parse(maxDataEl.textContent);
    const datasets = mode === 'max'
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
    meanChart = chartFromData(canvas, {
      labels: meanData.labels,
      datasets: datasets,
      bucketMinutes: meanData.bucketMinutes
    }, 'points', meanScale, durationUnit);
    ['only', 'both', 'max'].forEach(function (name) {
      const button = document.getElementById('metric-mean-mode-' + name);
      if (button) {
        button.setAttribute('aria-pressed', String(
          (mode === 'only' && name === 'only')
          || (mode === 'both' && name === 'both')
          || (mode === 'max' && name === 'max')));
      }
    });
    const scaleToggle = document.getElementById('metric-mean-scale-log');
    if (scaleToggle) {
      scaleToggle.checked = meanScale === 'logarithmic';
    }
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
      [['chart', state.get('chart')], ['mean', state.get('mean')], ['scale', state.get('scale')]]
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
    renderTotalChart(totalStyle);
    renderMeanChart(meanMode, meanScale);
  });

  const hashSeriesButtons = document.querySelectorAll('.hash-series-toggle');
  hashSeriesButtons.forEach(function (button) {
    visible.set(button.dataset.label, true);
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

  renderTotalChart(totalStyle);
  renderMeanChart(meanMode, meanScale);
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
