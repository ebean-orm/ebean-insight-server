(function () {
  const dataElement = document.getElementById('datasource-pool-data');
  const canvas = document.getElementById('datasource-pool-chart');
  const legend = document.getElementById('datasource-pool-legend');
  if (!dataElement || !canvas || typeof Chart === 'undefined' || !window.DashboardCharts) {
    return;
  }

  let data = window.DashboardCharts.localize(JSON.parse(dataElement.textContent));
  if (!data.labels || data.labels.length === 0) {
    return;
  }

  let chart = null;
  const tooltip = function () {
    return window.DashboardCharts.htmlTooltip(data.labels, 'datasource-pool-tooltip', function (point) {
      return {
        label: point.dataset.label,
        metric: 'Connections',
        value: Math.round(point.parsed.y).toLocaleString()
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
          title: {display: true, text: 'Connections'},
          ticks: {
            callback: function (value) {
              return value;
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
    const next = event.detail.datasourcePool;
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
    chart.update('none');
    window.DashboardCharts.renderSeriesLegend(legend, chart);
  });
  window.addEventListener('insight-theme-change', function () {
    window.DashboardCharts.hideHtmlTooltip('datasource-pool-tooltip');
    if (chart) {
      chart.destroy();
    }
    render();
  });
})();
