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
  if (!dataEl || !canvas || typeof Chart === 'undefined' || !window.DashboardCharts) {
    return;
  }

  const chartData = JSON.parse(dataEl.textContent);
  if (!chartData.labels || chartData.labels.length === 0) {
    return;
  }

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
  const visible = new Map(chartData.datasets.map(function (ds) {
    return [ds.label, true];
  }));

  const render = function (type) {
    if (chart) {
      chart.destroy();
    }
    const isLine = type === 'line';
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
          y: {stacked: !isLine}
        },
        onClick: function (evt, elements) {
          if (!elements.length) {
            return;
          }
          const label = chartData.datasets[elements[0].datasetIndex].label;
          if (label !== 'Other') {
            window.location.href = detailUrlFor(label);
          }
        },
        onHover: window.DashboardCharts.pointerOnHover,
        plugins: {
          legend: {display: false},
          tooltip: window.DashboardCharts.tooltipOptions(chartData.labels)
        }
      }
    });
  };

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

  const renderRankingChart = function (dataId, canvasId) {
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
    const colorsByLabel = new Map(chartData.datasets.map(function (ds) {
      return [ds.label, ds.backgroundColor];
    }));
    new Chart(rankingCanvas.getContext('2d'), {
      type: 'bar',
      data: {
        labels: rankingData.labels,
        datasets: [{
          label: dataset.label,
          data: dataset.data,
          backgroundColor: rankingData.labels.map(function (label) {
            return colorsByLabel.get(label) || dataset.backgroundColor;
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
          x: {beginAtZero: true},
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
        onHover: window.DashboardCharts.pointerOnHover,
        plugins: {
          legend: {display: false},
          tooltip: window.DashboardCharts.tooltipOptions(rankingData.labels),
          rankingLabels: {}
        },
        interaction: {mode: 'index', intersect: false}
      },
      plugins: [{
        id: 'ranking-bar-labels',
        afterDatasetsDraw: function (chart) {
          const meta = chart.getDatasetMeta(0);
          const context = chart.ctx;
          context.save();
          context.font = '14px sans-serif';
          context.fillStyle = '#17202a';
          context.textBaseline = 'middle';
          meta.data.forEach(function (bar, index) {
            context.fillText(rankingData.labels[index], bar.base + 8, bar.y);
          });
          context.restore();
        }
      }]
    });
  };

  const renderMeanMaxChart = function (mode) {
    const meanDataEl = document.getElementById('top-mean-data');
    const maxDataEl = document.getElementById('top-max-data');
    const meanCanvas = document.getElementById('top-mean-max-chart');
    if (!meanDataEl || !maxDataEl || !meanCanvas) {
      return;
    }
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
          pointRadius: 5,
          pointHoverRadius: 7,
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
          pointRadius: 4,
          pointHoverRadius: 6,
          pointStyle: 'triangle'
        });
      });
    }
    meanMaxChart = new Chart(meanCanvas.getContext('2d'), {
      type: 'line',
      data: {labels: meanData.labels, datasets: datasets},
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        scales: {
          x: window.DashboardCharts.buildXScale(meanData.labels, meanData.bucketMinutes),
          y: {}
        },
        plugins: {
          legend: {display: false},
          tooltip: window.DashboardCharts.tooltipOptions(meanData.labels)
        }
      }
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
  };

  ['both', 'only', 'max'].forEach(function (mode) {
    const button = document.getElementById('top-mean-mode-' + mode);
    if (button) {
      button.addEventListener('click', function () {
        renderMeanMaxChart(mode);
      });
    }
  });

  render('bar');
  updateLegend();
  renderMeanMaxChart('both');
  renderRankingChart('top-by-time-data', 'top-by-time-chart');
  renderRankingChart('top-by-mean-data', 'top-by-mean-chart');
})();
