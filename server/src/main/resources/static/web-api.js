(function () {
  const totalElement = document.getElementById('web-api-data');
  const meanElement = document.getElementById('web-api-mean-data');
  const maxElement = document.getElementById('web-api-max-data');
  const countElement = document.getElementById('web-api-count-data');
  const totalCanvas = document.getElementById('web-api-chart');
  const meanMaxCanvas = document.getElementById('web-api-mean-max-chart');
  if (!totalElement || !meanElement || !maxElement || !countElement
    || !totalCanvas || !meanMaxCanvas
    || typeof Chart === 'undefined' || !window.DashboardCharts) {
    return;
  }

  let total = window.DashboardCharts.localize(JSON.parse(totalElement.textContent));
  let mean = window.DashboardCharts.localize(JSON.parse(meanElement.textContent));
  let max = window.DashboardCharts.localize(JSON.parse(maxElement.textContent));
  let count = window.DashboardCharts.localize(JSON.parse(countElement.textContent));
  if (!total.labels || total.labels.length === 0) {
    return;
  }

  let chartType = 'bar';
  let meanMode = 'both';
  let meanChartType = 'dots';
  let meanScale = 'linear';
  let totalChart = null;
  let meanMaxChart = null;
  let dragStart = null;
  let dragEnd = null;
  const visible = new Map(total.datasets.map(function (dataset) {
    return [dataset.label, true];
  }));

  const currentSeries = function (data, line) {
    return data.datasets.map(function (dataset) {
      return {
        label: dataset.label,
        data: dataset.data,
        hidden: !visible.get(dataset.label),
        borderColor: dataset.backgroundColor,
        backgroundColor: dataset.backgroundColor,
        fill: false,
        pointRadius: line ? 0 : undefined,
        borderWidth: line ? 2 : 1,
        tension: line ? 0.15 : undefined
      };
    });
  };

  const meanMaxSeries = function () {
    if (meanMode === 'count') {
      const gapColor = getComputedStyle(document.body).backgroundColor;
      return count.datasets.map(function (dataset) {
        return {
          label: dataset.label,
          data: dataset.data,
          hidden: !visible.get(dataset.label),
          backgroundColor: dataset.backgroundColor,
          categoryPercentage: 1.0,
          barPercentage: 1.0,
          borderWidth: 1,
          borderColor: gapColor,
          borderSkipped: false
        };
      });
    }
    const meanDatasets = mean.datasets.map(function (dataset) {
      return {
        label: dataset.label,
        data: dataset.data,
        hidden: !visible.get(dataset.label),
        borderColor: dataset.backgroundColor,
        backgroundColor: dataset.backgroundColor,
        showLine: meanChartType === 'lines',
        pointRadius: meanChartType === 'lines' ? 0 : 3,
        pointHoverRadius: meanChartType === 'lines' ? 0 : 5,
        borderWidth: meanChartType === 'lines' ? 2 : 1,
        tension: 0.15,
        spanGaps: meanChartType === 'lines',
        pointStyle: 'circle'
      };
    });
    const maxDatasets = max.datasets.map(function (dataset) {
      return {
        label: dataset.label,
        data: dataset.data,
        hidden: !visible.get(dataset.label),
        borderColor: dataset.backgroundColor,
        backgroundColor: dataset.backgroundColor,
        showLine: meanChartType === 'lines',
        pointRadius: meanChartType === 'lines' ? 0 : 2,
        pointHoverRadius: meanChartType === 'lines' ? 0 : 4,
        borderWidth: meanChartType === 'lines' ? 2 : 1,
        tension: 0.15,
        spanGaps: meanChartType === 'lines',
        pointStyle: 'triangle'
      };
    });
    if (meanMode === 'only') {
      maxDatasets.forEach(function (dataset) { dataset.hidden = true; });
    } else if (meanMode === 'max') {
      meanDatasets.forEach(function (dataset) { dataset.hidden = true; });
    }
    return meanDatasets.concat(maxDatasets);
  };

  const updateButton = function (id, pressed) {
    const button = document.getElementById(id);
    if (button) {
      button.setAttribute('aria-pressed', String(pressed));
    }
  };

  const render = function () {
    if (totalChart) {
      totalChart.destroy();
    }
    if (meanMaxChart) {
      meanMaxChart.destroy();
    }
    const line = chartType === 'line';
    totalChart = new Chart(totalCanvas.getContext('2d'), {
      type: chartType,
      data: {labels: total.labels, datasets: currentSeries(total, line)},
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        scales: {
          x: Object.assign(window.DashboardCharts.buildXScale(total.labels, total.bucketMinutes), {stacked: !line}),
          y: {stacked: !line, beginAtZero: true}
        },
        plugins: {legend: {display: false}}
      }
    });

    const datasets = meanMaxSeries();
    const countMode = meanMode === 'count';
    const viewGroup = document.getElementById('web-api-mean-view-group');
    if (viewGroup) {
      viewGroup.hidden = countMode;
    }
    const scaleGroup = document.getElementById('web-api-mean-scale-group');
    if (scaleGroup) {
      scaleGroup.hidden = countMode;
    }
    meanMaxChart = new Chart(meanMaxCanvas.getContext('2d'), {
      type: countMode ? 'bar' : 'line',
      data: {labels: countMode ? count.labels : mean.labels, datasets: datasets},
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        scales: {
          x: Object.assign(
            window.DashboardCharts.buildXScale(
              countMode ? count.labels : mean.labels,
              countMode ? count.bucketMinutes : mean.bucketMinutes),
            {stacked: countMode}
          ),
          y: {
            beginAtZero: true,
            stacked: countMode,
            type: countMode ? 'linear' : meanScale,
            title: {display: true, text: countMode ? 'Executions' : 'Milliseconds'}
          }
        },
        plugins: {legend: {display: false}}
      }
    });
  };

  const applySelection = function (start, end) {
    if (start === null || end === null || start === end) {
      return;
    }
    if (start > end) {
      const swap = start;
      start = end;
      end = swap;
    }
    const bucketMillis = total.bucketMinutes * 60 * 1000;
    const current = new URLSearchParams(window.location.search);
    current.set('from', new Date(total.timestamps[start] - 1).toISOString());
    current.set('to', new Date(total.timestamps[end] + bucketMillis - 1).toISOString());
    window.location.href = window.location.pathname + '?' + current.toString();
  };

  const showSelection = function () {
    const current = new URLSearchParams(window.location.search);
    const from = Date.parse(current.get('from') || '');
    const to = Date.parse(current.get('to') || '');
    const status = document.getElementById('web-api-range-selection');
    const text = document.getElementById('web-api-range-selection-text');
    if (!status || !text || !Number.isFinite(from) || !Number.isFinite(to)) {
      return;
    }
    text.textContent = 'Selected: ' + new Date(from).toLocaleString()
      + ' - ' + new Date(to).toLocaleString();
    status.hidden = false;
  };

  const indexAt = function (event, chart) {
    if (!chart || !chart.scales.x || !chart.chartArea) {
      return null;
    }
    const position = Chart.helpers.getRelativePosition(event, chart);
    if (position.x < chart.chartArea.left || position.x > chart.chartArea.right
      || position.y < chart.chartArea.top || position.y > chart.chartArea.bottom) {
      return null;
    }
    return Math.max(0, Math.min(total.labels.length - 1,
      Math.round(chart.scales.x.getValueForPixel(position.x))));
  };

  totalCanvas.addEventListener('mousedown', function (event) {
    dragStart = indexAt(event, totalChart);
    dragEnd = dragStart;
  });
  totalCanvas.addEventListener('mousemove', function (event) {
    if (dragStart !== null) {
      dragEnd = indexAt(event, totalChart);
    }
  });
  totalCanvas.addEventListener('mouseup', function () {
    applySelection(dragStart, dragEnd);
    dragStart = null;
    dragEnd = null;
  });

  document.getElementById('web-api-chart-type-bar').addEventListener('click', function () {
    chartType = 'bar';
    updateButton('web-api-chart-type-bar', true);
    updateButton('web-api-chart-type-line', false);
    render();
  });
  document.getElementById('web-api-chart-type-line').addEventListener('click', function () {
    chartType = 'line';
    updateButton('web-api-chart-type-bar', false);
    updateButton('web-api-chart-type-line', true);
    render();
  });
  document.getElementById('web-api-mean-mode-both').addEventListener('click', function () {
    meanMode = 'both';
    updateButton('web-api-mean-mode-both', true);
    updateButton('web-api-mean-mode-only', false);
    updateButton('web-api-mean-mode-max', false);
    updateButton('web-api-mean-mode-count', false);
    render();
  });
  document.getElementById('web-api-mean-mode-only').addEventListener('click', function () {
    meanMode = 'only';
    updateButton('web-api-mean-mode-both', false);
    updateButton('web-api-mean-mode-only', true);
    updateButton('web-api-mean-mode-max', false);
    updateButton('web-api-mean-mode-count', false);
    render();
  });
  document.getElementById('web-api-mean-mode-max').addEventListener('click', function () {
    meanMode = 'max';
    updateButton('web-api-mean-mode-both', false);
    updateButton('web-api-mean-mode-only', false);
    updateButton('web-api-mean-mode-max', true);
    updateButton('web-api-mean-mode-count', false);
    render();
  });
  document.getElementById('web-api-mean-mode-count').addEventListener('click', function () {
    meanMode = 'count';
    updateButton('web-api-mean-mode-both', false);
    updateButton('web-api-mean-mode-only', false);
    updateButton('web-api-mean-mode-max', false);
    updateButton('web-api-mean-mode-count', true);
    render();
  });
  document.getElementById('web-api-mean-view-dots').addEventListener('click', function () {
    meanChartType = 'dots';
    updateButton('web-api-mean-view-dots', true);
    updateButton('web-api-mean-view-lines', false);
    render();
  });
  document.getElementById('web-api-mean-view-lines').addEventListener('click', function () {
    meanChartType = 'lines';
    updateButton('web-api-mean-view-dots', false);
    updateButton('web-api-mean-view-lines', true);
    render();
  });
  document.getElementById('web-api-mean-scale-log').addEventListener('change', function (event) {
    meanScale = event.target.checked ? 'logarithmic' : 'linear';
    render();
  });
  const updateLegend = function () {
    document.querySelectorAll('.web-api-series-toggle').forEach(function (button) {
      const label = button.dataset.label;
      const dataset = total.datasets.find(function (entry) { return entry.label === label; });
      const swatch = button.querySelector('.legend-swatch');
      button.setAttribute('aria-pressed', String(visible.get(label)));
      if (swatch && dataset) {
        swatch.style.backgroundColor = dataset.backgroundColor;
      }
    });
  };
  document.querySelectorAll('.web-api-series-toggle').forEach(function (button) {
    button.addEventListener('click', function (event) {
      const label = button.dataset.label;
      if (event.ctrlKey || event.metaKey) {
        visible.set(label, !visible.get(label));
      } else {
        const onlyThisSeries = Array.from(visible.entries()).every(function (entry) {
          return entry[0] === label ? entry[1] : !entry[1];
        });
        total.datasets.forEach(function (dataset) {
          visible.set(dataset.label, onlyThisSeries || dataset.label === label);
        });
      }
      updateLegend();
      render();
    });
  });
  document.getElementById('web-api-range-selection-clear').addEventListener('click', function () {
    const current = new URLSearchParams(window.location.search);
    current.delete('from');
    current.delete('to');
    window.location.href = window.location.pathname + '?' + current.toString();
  });

  window.addEventListener('insight-top-data', function (event) {
    const data = event.detail;
    if (!data.webApiDashboard || !data.webApi || !data.webApi.labels
      || data.webApi.labels.length === 0) {
      return;
    }
    total = window.DashboardCharts.localize(data.webApi);
    mean = window.DashboardCharts.localize(data.webApiMean);
    max = window.DashboardCharts.localize(data.webApiMax);
    count = window.DashboardCharts.localize(data.webApiCount);
    const totalRate = document.getElementById('web-api-total-rate');
    if (totalRate) {
      totalRate.textContent = data.webApiRate + ' req/s   load: ' + data.webApiLoad;
    }
    total.datasets.forEach(function (dataset) {
      if (!visible.has(dataset.label)) {
        visible.set(dataset.label, true);
      }
    });
    if (totalChart) {
      const line = chartType === 'line';
      totalChart.data.labels = total.labels;
      totalChart.data.datasets = currentSeries(total, line);
      totalChart.options.scales.x = Object.assign(
        window.DashboardCharts.buildXScale(total.labels, total.bucketMinutes),
        {stacked: !line});
      totalChart.update('none');
    }
    if (meanMaxChart) {
      const countMode = meanMode === 'count';
      const activeData = countMode ? count : mean;
      meanMaxChart.data.labels = activeData.labels;
      meanMaxChart.data.datasets = meanMaxSeries();
      meanMaxChart.options.scales.x = Object.assign(
        window.DashboardCharts.buildXScale(activeData.labels, activeData.bucketMinutes),
        {stacked: countMode});
      meanMaxChart.options.scales.y.type = countMode ? 'linear' : meanScale;
      meanMaxChart.update('none');
    }
    updateLegend();
  });

  showSelection();
  updateLegend();
  render();
  window.addEventListener('insight-theme-change', function () {
    render();
  });
})();
