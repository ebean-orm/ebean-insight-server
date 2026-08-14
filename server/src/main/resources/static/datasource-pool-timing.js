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
  const tooltip = function () {
    return window.DashboardCharts.htmlTooltip(data.labels, 'datasource-pool-timing-tooltip', function (point) {
      return {
        label: point.dataset.label,
        metric: 'Duration',
        value: point.parsed.y.toFixed(1) + ' ms'
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

  const durationUnit = function () {
    const max = data.labels.reduce(function (maximum, _, index) {
      const total = data.datasets.reduce(function (sum, dataset) {
        return sum + (Number(dataset.data[index]) || 0);
      }, 0);
      return Math.max(maximum, total);
    }, 0);
    return window.DashboardCharts.durationUnitFor(max);
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
              return window.DashboardCharts.durationValue(value, unit);
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
  window.addEventListener('insight-top-data', function (event) {
    const next = event.detail.datasourcePoolTiming;
    if (!next || !next.labels || next.labels.length === 0 || !chart) {
      return;
    }
    data = window.DashboardCharts.localize(next);
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
      return window.DashboardCharts.durationValue(value, unit);
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
