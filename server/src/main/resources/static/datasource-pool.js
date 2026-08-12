(function () {
  const dataElement = document.getElementById('datasource-pool-data');
  const canvas = document.getElementById('datasource-pool-chart');
  if (!dataElement || !canvas || typeof Chart === 'undefined' || !window.DashboardCharts) {
    return;
  }

  let data = window.DashboardCharts.localize(JSON.parse(dataElement.textContent));
  if (!data.labels || data.labels.length === 0) {
    return;
  }

  let chart = null;
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
          ticks: {
            callback: function (value) {
              return value;
            }
          }
        }
      },
      plugins: {
        legend: {display: true, position: 'bottom'},
        tooltip: window.DashboardCharts.tooltipOptions(data.labels, {
          label: function (context) {
            return context.dataset.label + ': ' + context.raw;
          }
        })
      }
    }
    });
  };

  render();
  window.addEventListener('insight-top-data', function (event) {
    const next = event.detail.datasourcePool;
    if (!next || !next.labels || next.labels.length === 0 || !chart) {
      return;
    }
    data = window.DashboardCharts.localize(next);
    chart.data.labels = data.labels;
    chart.data.datasets = datasets();
    chart.options.scales.x = Object.assign(
      window.DashboardCharts.buildXScale(data.labels, data.bucketMinutes),
      {stacked: true});
    chart.update('none');
  });
  window.addEventListener('insight-theme-change', function () {
    if (chart) {
      chart.destroy();
    }
    render();
  });
})();
