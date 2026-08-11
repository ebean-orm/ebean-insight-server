/*
 * Chart.js bootstrap for the /ux/metric-detail drill-down page. Renders the
 * hash-stacked "Total execution time" and label-level "Mean execution time" charts
 * from the JSON payloads embedded by the server, and wires up the hash legend
 * toggles that control visibility in both charts.
 */
(function () {
  if (typeof Chart === 'undefined' || !window.DashboardCharts) {
    return;
  }

  const visible = new Map();

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
                + window.DashboardCharts.detailedDuration(context.raw)
                + ' (' + context.formattedValue + ' ms)';
            }
          } : null)
        }
      }
    });
  };

  const renderTrendChart = function (dataElId, canvasId, style) {
    const dataEl = document.getElementById(dataElId);
    const canvas = document.getElementById(canvasId);
    if (!dataEl || !canvas) {
      return;
    }
    return chartFromData(canvas, JSON.parse(dataEl.textContent), style);
  };

  let totalChart = null;
  let meanChart = null;
  let totalStyle = 'stacked-bar';
  let meanMode = 'both';
  let meanScale = 'linear';

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
    });
  }
  if (lineButton) {
    lineButton.addEventListener('click', function () {
      renderTotalChart('line');
    });
  }
  const meanOnlyButton = document.getElementById('metric-mean-mode-only');
  const meanBothButton = document.getElementById('metric-mean-mode-both');
  const meanMaxButton = document.getElementById('metric-mean-mode-max');
  if (meanOnlyButton) {
    meanOnlyButton.addEventListener('click', function () {
      renderMeanChart('only');
    });
  }
  if (meanBothButton) {
    meanBothButton.addEventListener('click', function () {
      renderMeanChart('both');
    });
  }
  if (meanMaxButton) {
    meanMaxButton.addEventListener('click', function () {
      renderMeanChart('max');
    });
  }
  const meanScaleToggle = document.getElementById('metric-mean-scale-log');
  if (meanScaleToggle) {
    meanScaleToggle.addEventListener('change', function () {
      renderMeanChart(meanMode, meanScaleToggle.checked ? 'logarithmic' : 'linear');
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

  renderTotalChart('stacked-bar');
  renderMeanChart('both');
})();
