/*
 * Chart.js bootstrap for the /ux/top dashboard page. Reads the JSON
 * payload embedded by the server (see query-total.mustache) and renders a
 * stacked bar chart (or, when toggled, a non-stacked line chart, one line
 * per label) into #chartjs-canvas. Clicking a non-"Other" segment drills
 * down to /ux/metric-detail for that label.
 */
(function () {
  const dataEl = document.getElementById('chart-data');
  const canvas = document.getElementById('chartjs-canvas');
  const meanCanvas = document.getElementById('top-mean-max-chart');
  if (!dataEl || !canvas || typeof Chart === 'undefined' || !window.DashboardCharts) {
    return;
  }

  const chartData = JSON.parse(dataEl.textContent);
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
    return '/ux/metric-detail?' + target.toString();
  };

  let chart = null;
  let meanMaxChart = null;
  const initialUrlState = new URLSearchParams(window.location.search);
  let chartType = initialUrlState.get('chart') === 'line' ? 'line' : 'bar';
  let rankingCharts = [];
  let rankingHoverLabel = null;
  let meanMaxMode = ['both', 'only', 'max'].includes(initialUrlState.get('mean'))
    ? initialUrlState.get('mean') : 'both';
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
    },
    afterDraw: function (currentChart) {
      if (sharedHoverIndex === null || !currentChart.scales.x) {
        return;
      }
      const x = currentChart.scales.x.getPixelForValue(sharedHoverIndex);
      const context = currentChart.ctx;
      context.save();
      context.strokeStyle = 'rgba(40, 80, 120, 0.45)';
      context.lineWidth = 1;
      context.beginPath();
      context.moveTo(x, currentChart.chartArea.top);
      context.lineTo(x, currentChart.chartArea.bottom);
      context.stroke();
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

  const render = function (type) {
    chartType = type;
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
    // A percentage-based barPercentage/categoryPercentage gap scales with bar
    // width and can't guarantee a fixed visual gap. Instead we draw each bar
    // segment with a 1px border in the page's background color, giving a true
    // ~1px gap between adjacent time-bucket columns regardless of chart width.
    const gapColor = getComputedStyle(document.body).backgroundColor;

    chart = new Chart(canvas.getContext('2d'), {
      type: type,
      data: {
        labels: chartData.labels,
        datasets: chartData.datasets.map(function (ds) {
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
        })
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        scales: {
          x: xScale,
          y: {
            stacked: !isLine,
            ticks: {
              callback: function (value) {
                return window.DashboardCharts.compactDuration(value, durationUnit);
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
        },
        plugins: {
          legend: {display: false},
          tooltip: window.DashboardCharts.tooltipOptions(chartData.labels, {
            label: function (context) {
              return context.dataset.label + ': ' + window.DashboardCharts.detailedDuration(context.raw)
                + ' (' + context.formattedValue + ' ms)';
            }
          })
        }
      },
      plugins: [sharedTimeCrosshair]
    });
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

  const updateLegend = function () {
    document.querySelectorAll('.legend-series-toggle').forEach(function (button) {
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

  document.querySelectorAll('.legend-series-toggle').forEach(function (button) {
    button.addEventListener('click', function (event) {
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
    });
  });

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
      const context = currentChart.ctx;
      context.save();
      const properties = activeElement.getProps(['base', 'x', 'y', 'height'], true);
      context.strokeStyle = window.DashboardCharts.themeColors().text;
      context.lineWidth = 2;
      context.strokeRect(
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
    const rankingData = JSON.parse(rankingDataEl.textContent);
    if (!rankingData.labels || rankingData.labels.length === 0) {
      return;
    }
    const dataset = rankingData.datasets[0];
    const maxValue = dataset.data.reduce(function (max, value) {
      return Math.max(max, Number(value) || 0);
    }, 0);
    const durationUnit = window.DashboardCharts.durationUnitFor(maxValue);
    const colorsByLabel = new Map(chartData.datasets.map(function (ds) {
      return [ds.label, ds.backgroundColor];
    }));
    const barColors = rankingData.labels.map(function (label) {
      return colorsByLabel.get(label) || dataset.backgroundColor;
    });
    const rankingChart = new Chart(rankingCanvas.getContext('2d'), {
      type: 'bar',
      data: {
        labels: rankingData.labels,
        datasets: [{
          label: dataset.label,
          data: dataset.data,
          backgroundColor: barColors.map(function (color) {
            return withAlpha(color, 0.3);
          }),
          hoverBackgroundColor: barColors.map(function (color) {
            return withAlpha(color, 0.55);
          }),
          borderWidth: 0,
          barPercentage: 0.8,
          categoryPercentage: 0.9
        }]
      },
      options: {
        indexAxis: 'y',
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
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
          if (elements.length) {
            window.location.href = detailUrlFor(rankingData.labels[elements[0].index]);
          }
        },
        onHover: function (event, elements) {
          window.DashboardCharts.pointerOnHover(event, elements);
          const label = elements.length ? rankingData.labels[elements[0].index] : null;
          if (rankingHoverLabel !== label) {
            rankingHoverLabel = label;
            rankingCharts.forEach(function (entry) {
              entry.chart.update('none');
            });
          }
        },
        plugins: {
          legend: {display: false},
          tooltip: window.DashboardCharts.tooltipOptions(rankingData.labels, unit ? {
            label: function (context) {
              return context.dataset.label + ': '
                + window.DashboardCharts.detailedDuration(context.raw)
                + ' (' + context.formattedValue + ' ' + unit + ')';
            }
          } : null),
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
          context.font = '14px sans-serif';
          context.fillStyle = colors.text;
          context.strokeStyle = colors.background;
          context.lineWidth = 1.5;
          context.lineJoin = 'round';
          context.textBaseline = 'middle';
          meta.data.forEach(function (bar, index) {
            const x = bar.base + 8;
            const y = bar.y;
            context.strokeText(rankingData.labels[index], x, y);
            context.fillText(rankingData.labels[index], x, y);
          });
          context.restore();
        }
      }]
    });
    rankingCharts.push({chart: rankingChart, labels: rankingData.labels});
  };

  const renderMeanMaxChart = function (mode, scale) {
    const meanDataEl = document.getElementById('top-mean-data');
    const maxDataEl = document.getElementById('top-max-data');
    const meanCanvas = document.getElementById('top-mean-max-chart');
    if (!meanDataEl || !maxDataEl || !meanCanvas) {
      return;
    }
    meanMaxMode = mode || meanMaxMode;
    meanMaxScale = scale || meanMaxScale;
    if (meanMaxChart) {
      meanMaxChart.destroy();
    }
    const meanData = JSON.parse(meanDataEl.textContent);
    const maxData = JSON.parse(maxDataEl.textContent);
    const datasets = [];
    if (mode !== 'max') {
      meanData.datasets.forEach(function (ds) {
        datasets.push({
          label: ds.label,
          data: ds.data,
          hidden: !visible.get(ds.label),
          borderColor: ds.backgroundColor,
          backgroundColor: ds.backgroundColor,
          showLine: false,
          pointRadius: 3,
          pointHoverRadius: 5,
          pointStyle: 'circle'
        });
      });
    }
    if (mode !== 'only') {
      maxData.datasets.forEach(function (ds) {
        datasets.push({
          label: ds.label,
          data: ds.data,
          hidden: !visible.get(ds.label),
          borderColor: ds.backgroundColor,
          backgroundColor: ds.backgroundColor,
          showLine: false,
          pointRadius: 2,
          pointHoverRadius: 4,
          pointStyle: 'triangle'
        });
      });
    }
    const maxValue = datasets.reduce(function (max, ds) {
      return Math.max(max, ds.data.reduce(function (seriesMax, value) {
        return Math.max(seriesMax, Number(value) || 0);
      }, 0));
    }, 0);
    const durationUnit = window.DashboardCharts.durationUnitFor(maxValue);
    meanMaxChart = new Chart(meanCanvas.getContext('2d'), {
      type: 'line',
      data: {labels: meanData.labels, datasets: datasets},
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        scales: {
          x: window.DashboardCharts.buildXScale(meanData.labels, meanData.bucketMinutes),
          y: {
            type: meanMaxScale,
            ticks: {
              callback: function (value) {
                return window.DashboardCharts.compactDuration(value, durationUnit);
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
          tooltip: window.DashboardCharts.tooltipOptions(meanData.labels, {
            label: function (context) {
              return context.dataset.label + ': '
                + window.DashboardCharts.detailedDuration(context.raw)
                + ' (' + context.formattedValue + ' ms)';
            }
          })
        }
      },
      plugins: [sharedTimeCrosshair]
    });
    ['both', 'only', 'max'].forEach(function (name) {
      const button = document.getElementById('top-mean-mode-' + name);
      if (button) {
        button.setAttribute('aria-pressed', String(
          (mode === 'both' && name === 'both')
          || (mode === 'only' && name === 'only')
          || (mode === 'max' && name === 'max')));
      }
    });
    const scaleToggle = document.getElementById('top-mean-scale-log');
    if (scaleToggle) {
      scaleToggle.checked = meanMaxScale === 'logarithmic';
    }
  };

  ['both', 'only', 'max'].forEach(function (mode) {
    const button = document.getElementById('top-mean-mode-' + mode);
    if (button) {
      button.addEventListener('click', function () {
        renderMeanMaxChart(mode);
        setChartStateUrl('mean', mode);
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
