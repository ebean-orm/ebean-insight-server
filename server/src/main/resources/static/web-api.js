(function () {
  // This shared renderer is loaded for both the Web API and optional DML
  // timer dashboards; the script data attribute selects the dashboard.
  const script = document.currentScript;
  const dashboard = script && script.dataset.dashboard === 'dml' ? 'dml' : 'web-api';
  const statePrefix = dashboard === 'dml' ? 'dml' : 'web';
  const id = function (suffix) {
    return dashboard + '-' + suffix;
  };
  const totalElement = document.getElementById(id('data'));
  const meanElement = document.getElementById(id('mean-data'));
  const maxElement = document.getElementById(id('max-data'));
  const countElement = document.getElementById(id('count-data'));
  const totalCanvas = document.getElementById(id('chart'));
  const meanMaxCanvas = document.getElementById(id('mean-max-chart'));
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

  const initialUrlState = new URLSearchParams(window.location.search);
  let chartType = initialUrlState.get(statePrefix + 'Chart') === 'line' ? 'line' : 'bar';
  let meanMode = ['both', 'only', 'max', 'count'].includes(initialUrlState.get(statePrefix + 'Mean'))
    ? initialUrlState.get(statePrefix + 'Mean') : 'both';
  let meanChartType = initialUrlState.get(statePrefix + 'MeanView') === 'lines' ? 'lines' : 'dots';
  let meanScale = initialUrlState.get(statePrefix + 'Scale') === 'log' ? 'logarithmic' : 'linear';
  let totalChart = null;
  let meanMaxChart = null;
  const visible = new Map(total.datasets.map(function (dataset) {
    return [dataset.label, true];
  }));

  const totalTooltip = function () {
    return window.DashboardCharts.htmlTooltip(total.labels, id('total-tooltip'), function (point) {
      return {
        label: point.dataset.label,
        metric: 'Total time',
        value: window.DashboardCharts.detailedDuration(point.parsed.y)
      };
    });
  };

  const statisticsTooltip = function (activeData, countMode) {
    return window.DashboardCharts.htmlTooltip(activeData.labels, id('mean-max-tooltip'), function (point) {
      const metric = countMode
        ? dashboard === 'dml' ? 'Operations' : 'Requests'
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

  const currentSeries = function (data, line) {
    const gapColor = getComputedStyle(document.body).backgroundColor;
    return data.datasets.map(function (dataset) {
      return {
        label: dataset.label,
        data: dataset.data,
        hidden: !visible.get(dataset.label),
        backgroundColor: dataset.backgroundColor,
        fill: false,
        pointRadius: line ? 0 : undefined,
        borderWidth: line ? 2 : 1,
        borderColor: line ? dataset.backgroundColor : gapColor,
        categoryPercentage: line ? undefined : 1,
        barPercentage: line ? undefined : 1,
        borderSkipped: line ? undefined : false,
        tension: line ? 0.15 : undefined
      };
    });
  };

  const totalDurationUnit = function () {
    const max = total.labels.reduce(function (maximum, _, index) {
      const value = total.datasets.reduce(function (aggregate, dataset) {
        const point = Number(dataset.data[index]) || 0;
        return chartType === 'line' ? Math.max(aggregate, point) : aggregate + point;
      }, 0);
      return Math.max(maximum, value);
    }, 0);
    return window.DashboardCharts.durationUnitFor(max);
  };

  const statisticsDurationUnit = function (datasets) {
    const max = datasets.reduce(function (maximum, dataset) {
      return Math.max(maximum, dataset.data.reduce(function (seriesMaximum, value) {
        return Math.max(seriesMaximum, Number(value) || 0);
      }, 0));
    }, 0);
    return window.DashboardCharts.durationUnitFor(max);
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

  const setChartStateUrl = function (name, value) {
    const current = new URLSearchParams(window.location.search);
    current.set(name, value);
    window.history.replaceState(null, '', window.location.pathname + '?' + current.toString());
  };

  const render = function () {
    window.DashboardCharts.hideHtmlTooltip(id('total-tooltip'));
    window.DashboardCharts.hideHtmlTooltip(id('mean-max-tooltip'));
    if (totalChart) {
      totalChart.destroy();
    }
    if (meanMaxChart) {
      meanMaxChart.destroy();
    }
    const line = chartType === 'line';
    const totalUnit = totalDurationUnit();
    totalChart = new Chart(totalCanvas.getContext('2d'), {
      type: chartType,
      data: {labels: total.labels, datasets: currentSeries(total, line)},
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        scales: {
          x: Object.assign(window.DashboardCharts.buildXScale(total.labels, total.bucketMinutes), {stacked: !line}),
          y: {
            stacked: !line,
            beginAtZero: true,
            title: {display: true, text: 'Total time (' + totalUnit + ')'},
            ticks: {
              callback: function (value) {
                return window.DashboardCharts.durationValue(value, totalUnit);
              }
            }
          }
        },
        plugins: {
          legend: {display: false},
          tooltip: totalTooltip(),
          sharedCrosshair: window.DashboardCharts.crosshair(total)
        }
      }
    });

    const datasets = meanMaxSeries();
    const countMode = meanMode === 'count';
    const statisticsUnit = countMode ? null : statisticsDurationUnit(datasets);
    const viewGroup = document.getElementById(id('mean-view-group'));
    if (viewGroup) {
      viewGroup.hidden = countMode;
    }
    const scaleGroup = document.getElementById(id('mean-scale-group'));
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
            title: {
              display: true,
              text: countMode ? 'Executions' : 'Duration (' + statisticsUnit + ')'
            },
            ticks: {
              callback: function (value) {
                return countMode
                  ? Number(value).toLocaleString()
                  : window.DashboardCharts.durationValue(value, statisticsUnit);
              }
            }
          }
        },
        plugins: {
          legend: {display: false},
          tooltip: statisticsTooltip(countMode ? count : mean, countMode),
          sharedCrosshair: window.DashboardCharts.crosshair(countMode ? count : mean)
        }
      }
    });
  };

  const showSelection = function () {
    const current = new URLSearchParams(window.location.search);
    const from = Date.parse(current.get('from') || '');
    const to = Date.parse(current.get('to') || '');
    const status = document.getElementById(id('range-selection'));
    const text = document.getElementById(id('range-selection-text'));
    if (!status || !text || !Number.isFinite(from) || !Number.isFinite(to)) {
      return;
    }
    text.textContent = 'Selected: ' + new Date(from).toLocaleString()
      + ' - ' + new Date(to).toLocaleString();
    status.hidden = false;
  };

  document.getElementById(id('chart-type-bar')).addEventListener('click', function () {
    chartType = 'bar';
    updateButton(id('chart-type-bar'), true);
    updateButton(id('chart-type-line'), false);
    setChartStateUrl(statePrefix + 'Chart', chartType);
    render();
  });
  document.getElementById(id('chart-type-line')).addEventListener('click', function () {
    chartType = 'line';
    updateButton(id('chart-type-bar'), false);
    updateButton(id('chart-type-line'), true);
    setChartStateUrl(statePrefix + 'Chart', chartType);
    render();
  });
  document.getElementById(id('mean-mode-both')).addEventListener('click', function () {
    meanMode = 'both';
    updateButton(id('mean-mode-both'), true);
    updateButton(id('mean-mode-only'), false);
    updateButton(id('mean-mode-max'), false);
    updateButton(id('mean-mode-count'), false);
    setChartStateUrl(statePrefix + 'Mean', meanMode);
    render();
  });
  document.getElementById(id('mean-mode-only')).addEventListener('click', function () {
    meanMode = 'only';
    updateButton(id('mean-mode-both'), false);
    updateButton(id('mean-mode-only'), true);
    updateButton(id('mean-mode-max'), false);
    updateButton(id('mean-mode-count'), false);
    setChartStateUrl(statePrefix + 'Mean', meanMode);
    render();
  });
  document.getElementById(id('mean-mode-max')).addEventListener('click', function () {
    meanMode = 'max';
    updateButton(id('mean-mode-both'), false);
    updateButton(id('mean-mode-only'), false);
    updateButton(id('mean-mode-max'), true);
    updateButton(id('mean-mode-count'), false);
    setChartStateUrl(statePrefix + 'Mean', meanMode);
    render();
  });
  document.getElementById(id('mean-mode-count')).addEventListener('click', function () {
    meanMode = 'count';
    updateButton(id('mean-mode-both'), false);
    updateButton(id('mean-mode-only'), false);
    updateButton(id('mean-mode-max'), false);
    updateButton(id('mean-mode-count'), true);
    setChartStateUrl(statePrefix + 'Mean', meanMode);
    render();
  });
  document.getElementById(id('mean-view-dots')).addEventListener('click', function () {
    meanChartType = 'dots';
    updateButton(id('mean-view-dots'), true);
    updateButton(id('mean-view-lines'), false);
    setChartStateUrl(statePrefix + 'MeanView', meanChartType);
    render();
  });
  document.getElementById(id('mean-view-lines')).addEventListener('click', function () {
    meanChartType = 'lines';
    updateButton(id('mean-view-dots'), false);
    updateButton(id('mean-view-lines'), true);
    setChartStateUrl(statePrefix + 'MeanView', meanChartType);
    render();
  });
  document.getElementById(id('mean-scale-log')).addEventListener('change', function (event) {
    meanScale = event.target.checked ? 'logarithmic' : 'linear';
    setChartStateUrl(statePrefix + 'Scale', meanScale === 'logarithmic' ? 'log' : 'linear');
    render();
  });
  const updateLegend = function () {
    document.querySelectorAll('.' + dashboard + '-series-toggle').forEach(function (button) {
      const label = button.dataset.label;
      const dataset = total.datasets.find(function (entry) { return entry.label === label; });
      const swatch = button.querySelector('.legend-swatch');
      button.setAttribute('aria-pressed', String(visible.get(label)));
      if (swatch && dataset) {
        swatch.style.backgroundColor = dataset.backgroundColor;
      }
    });
  };
  document.querySelectorAll('.' + dashboard + '-series-toggle').forEach(function (button) {
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
  document.getElementById(id('range-selection-clear')).addEventListener('click', function () {
    const current = new URLSearchParams(window.location.search);
    current.delete('from');
    current.delete('to');
    window.location.href = window.location.pathname + '?' + current.toString();
  });

  window.addEventListener('insight-top-data', function (event) {
    const data = event.detail;
    const enabled = dashboard === 'dml' ? data.dmlDashboard : data.webApiDashboard;
    const nextData = dashboard === 'dml' ? data.dml : data.webApi;
    if (!enabled || !nextData) {
      return;
    }
    const emptyTimerData = !nextData.labels.length && data.timeRange;
    const nextMean = dashboard === 'dml' ? data.dmlMean : data.webApiMean;
    const nextMax = dashboard === 'dml' ? data.dmlMax : data.webApiMax;
    const nextCount = dashboard === 'dml' ? data.dmlCount : data.webApiCount;
    total = window.DashboardCharts.localize(emptyTimerData
      ? window.DashboardCharts.emptyDataForRange(total, data.timeRange) : nextData);
    mean = window.DashboardCharts.localize(emptyTimerData
      ? window.DashboardCharts.emptyDataForRange(mean, data.timeRange) : nextMean);
    max = window.DashboardCharts.localize(emptyTimerData
      ? window.DashboardCharts.emptyDataForRange(max, data.timeRange) : nextMax);
    count = window.DashboardCharts.localize(emptyTimerData
      ? window.DashboardCharts.emptyDataForRange(count, data.timeRange) : nextCount);
    const totalRate = document.getElementById(id('total-rate'));
    if (totalRate) {
      const rate = dashboard === 'dml' ? data.dmlRate : data.webApiRate;
      const load = dashboard === 'dml' ? data.dmlLoad : data.webApiLoad;
      totalRate.textContent = rate + (dashboard === 'dml' ? ' op/s   load: ' : ' req/s   load: ') + load;
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
      const totalUnit = totalDurationUnit();
      totalChart.options.scales.y.title.text = 'Total time (' + totalUnit + ')';
      totalChart.options.scales.y.ticks.callback = function (value) {
        return window.DashboardCharts.durationValue(value, totalUnit);
      };
      totalChart.options.plugins.tooltip = totalTooltip();
      totalChart.options.plugins.sharedCrosshair = window.DashboardCharts.crosshair(total);
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
      const statisticsUnit = countMode ? null : statisticsDurationUnit(meanMaxChart.data.datasets);
      meanMaxChart.options.scales.y.title.text = countMode
        ? 'Executions'
        : 'Duration (' + statisticsUnit + ')';
      meanMaxChart.options.scales.y.ticks.callback = function (value) {
        return countMode
          ? Number(value).toLocaleString()
          : window.DashboardCharts.durationValue(value, statisticsUnit);
      };
      meanMaxChart.options.plugins.tooltip = statisticsTooltip(activeData, countMode);
      meanMaxChart.options.plugins.sharedCrosshair = window.DashboardCharts.crosshair(activeData);
      meanMaxChart.update('none');
    }
    updateLegend();
  });

  showSelection();
  updateButton(id('chart-type-bar'), chartType === 'bar');
  updateButton(id('chart-type-line'), chartType === 'line');
  updateButton(id('mean-mode-both'), meanMode === 'both');
  updateButton(id('mean-mode-only'), meanMode === 'only');
  updateButton(id('mean-mode-max'), meanMode === 'max');
  updateButton(id('mean-mode-count'), meanMode === 'count');
  updateButton(id('mean-view-dots'), meanChartType === 'dots');
  updateButton(id('mean-view-lines'), meanChartType === 'lines');
  document.getElementById(id('mean-scale-log')).checked = meanScale === 'logarithmic';
  updateLegend();
  render();
  window.DashboardCharts.attachRangeSelection(totalCanvas, function () {
    return totalChart;
  }, function () {
    return total;
  });
  window.DashboardCharts.attachRangeSelection(meanMaxCanvas, function () {
    return meanMaxChart;
  }, function () {
    return meanMode === 'count' ? count : mean;
  });
  window.addEventListener('insight-theme-change', function () {
    render();
  });
})();
