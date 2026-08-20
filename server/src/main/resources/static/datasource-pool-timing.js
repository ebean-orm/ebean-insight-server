(function () {
  const dataElement = document.getElementById('datasource-pool-timing-data');
  const canvas = document.getElementById('datasource-pool-timing-chart');
  const legend = document.getElementById('datasource-pool-timing-legend');
  if (!dataElement || !canvas || typeof Chart === 'undefined' || !window.DashboardCharts) {
    return;
  }

  let data = window.DashboardCharts.localize(JSON.parse(dataElement.textContent));
  if (!data.labels || data.labels.length === 0) {
    return;
  }

  let chart = null;
  const durationUnit = function () {
    const max = data.labels.reduce(function (maximum, _, index) {
      const total = data.datasets.reduce(function (sum, dataset) {
        return sum + (Number(dataset.data[index]) || 0);
      }, 0);
      return Math.max(maximum, total);
    }, 0);
    if (max < 1_000) {
      return 'us';
    }
    if (max < 1_000_000) {
      return 'ms';
    }
    if (max < 60_000_000) {
      return 's';
    }
    if (max < 3_600_000_000) {
      return 'min';
    }
    return 'h';
  };
  const durationValue = function (value, unit) {
    const divisor = unit === 'ms' ? 1_000
      : unit === 's' ? 1_000_000
        : unit === 'min' ? 60_000_000
          : unit === 'h' ? 3_600_000_000 : 1;
    const rounded = Math.round((value / divisor) * 10) / 10;
    return String(rounded).replace(/\.0$/, '');
  };
  const tooltip = function () {
    const unit = durationUnit();
    return window.DashboardCharts.htmlTooltip(data.labels, 'datasource-pool-timing-tooltip', function (point) {
      return {
        label: point.dataset.label,
        metric: 'Duration',
        value: durationValue(point.parsed.y, unit) + ' ' + unit
      };
    });
  };
  const datasets = function () {
    return data.datasets.map(function (dataset) {
      return {
        label: dataset.label,
        data: dataset.data,
        backgroundColor: dataset.backgroundColor,
        categoryPercentage: 1,
        barPercentage: 1,
        borderWidth: 1,
        borderColor: getComputedStyle(document.body).backgroundColor,
        borderSkipped: false
      };
    });
  };

  const render = function () {
    const unit = durationUnit();
    chart = new Chart(canvas.getContext('2d'), {
    type: 'bar',
    data: {
      labels: data.labels,
      datasets: datasets()
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      animation: false,
      scales: {
        x: Object.assign(
          window.DashboardCharts.buildXScale(data.labels, data.bucketMinutes),
          {stacked: true}
        ),
        y: {
          stacked: true,
          beginAtZero: true,
          title: {display: true, text: 'Duration (' + unit + ')'},
          ticks: {
            callback: function (value) {
              return durationValue(value, unit);
            }
          }
        }
      },
      plugins: {
        legend: {
          display: false
        },
        tooltip: tooltip(),
        sharedCrosshair: window.DashboardCharts.crosshair(data)
      }
    }
    });
    window.DashboardCharts.renderSeriesLegend(legend, chart);
  };

  render();
  window.DashboardCharts.attachRangeSelection(canvas, function () {
    return chart;
  }, function () {
    return data;
  });
  window.addEventListener('insight-top-data', function (event) {
    const next = event.detail.datasourcePoolTiming;
    if (!next || !chart) {
      return;
    }
    data = window.DashboardCharts.localize(!next.labels.length && event.detail.timeRange
      ? window.DashboardCharts.emptyDataForRange(data, event.detail.timeRange) : next);
    chart.data.labels = data.labels;
    chart.data.datasets = datasets();
    chart.options.plugins.tooltip = tooltip();
    chart.options.plugins.sharedCrosshair = window.DashboardCharts.crosshair(data);
    chart.options.scales.x = Object.assign(
      window.DashboardCharts.buildXScale(data.labels, data.bucketMinutes),
      {stacked: true});
    const unit = durationUnit();
    chart.options.scales.y.title.text = 'Duration (' + unit + ')';
    chart.options.scales.y.ticks.callback = function (value) {
      return durationValue(value, unit);
    };
    chart.update('none');
    window.DashboardCharts.renderSeriesLegend(legend, chart);
  });
  window.addEventListener('insight-theme-change', function () {
    window.DashboardCharts.hideHtmlTooltip('datasource-pool-timing-tooltip');
    if (chart) {
      chart.destroy();
    }
    render();
  });
})();
