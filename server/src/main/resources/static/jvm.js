(function () {
  const memoryElement = document.getElementById('jvm-memory-data');
  const cpuElement = document.getElementById('jvm-cpu-data');
  const gcTimeElement = document.getElementById('jvm-gc-time-data');
  const gcCountElement = document.getElementById('jvm-gc-count-data');
  const memoryCanvas = document.getElementById('jvm-memory-chart');
  const cpuCanvas = document.getElementById('jvm-cpu-chart');
  const gcTimeCanvas = document.getElementById('jvm-gc-time-chart');
  const gcCountCanvas = document.getElementById('jvm-gc-count-chart');
  const memoryLegend = document.getElementById('jvm-memory-legend');
  const cpuLegend = document.getElementById('jvm-cpu-legend');
  const gcTimeLegend = document.getElementById('jvm-gc-time-legend');
  const gcCountLegend = document.getElementById('jvm-gc-count-legend');
  if (!memoryElement || !cpuElement || !gcTimeElement || !gcCountElement
    || !memoryCanvas || !cpuCanvas || !gcTimeCanvas || !gcCountCanvas
    || typeof Chart === 'undefined' || !window.DashboardCharts) {
    return;
  }

  let memory = window.DashboardCharts.localize(JSON.parse(memoryElement.textContent));
  let cpu = window.DashboardCharts.localize(JSON.parse(cpuElement.textContent));
  let gcTime = window.DashboardCharts.localize(JSON.parse(gcTimeElement.textContent));
  let gcCount = window.DashboardCharts.localize(JSON.parse(gcCountElement.textContent));
  let memoryChart = null;
  let cpuChart = null;
  let gcTimeChart = null;
  let gcCountChart = null;

  const series = function (data) {
    return data.datasets.map(function (dataset) {
      const heap = dataset.label.endsWith(' · Heap');
      return {
        label: dataset.label,
        data: dataset.data,
        borderColor: dataset.backgroundColor,
        backgroundColor: dataset.backgroundColor,
        fill: false,
        pointRadius: 0,
        pointHoverRadius: 4,
        pointHitRadius: 10,
        borderWidth: 2,
        borderDash: !heap ? [] : [6, 4],
        tension: 0.3,
        cubicInterpolationMode: 'monotone',
        spanGaps: false
      };
    });
  };

  const podName = function (label) {
    const separator = label.lastIndexOf(' · ');
    return separator < 0 ? label : label.substring(0, separator);
  };

  const seriesName = function (label) {
    const separator = label.lastIndexOf(' · ');
    return separator < 0 ? label : label.substring(separator + 3);
  };

  const jvmTooltip = function (labels, tooltipId, unit, defaultSeriesName, decimals) {
    return window.DashboardCharts.htmlTooltip(labels, tooltipId, function (point) {
      const label = point.dataset.label;
      const series = seriesName(label);
      return {
        label: podName(label),
        metric: series === label ? defaultSeriesName : series,
        value: point.parsed.y.toFixed(decimals) + unit
      };
    });
  };

  const updateLegendPressed = function (legend, chart) {
    legend.querySelectorAll('.jvm-series-toggle').forEach(function (item) {
      const visible = chart.data.datasets.some(function (dataset, index) {
        return podName(dataset.label) === item.dataset.pod && chart.isDatasetVisible(index);
      });
      item.setAttribute('aria-pressed', String(visible));
    });
    legend.querySelectorAll('.jvm-gc-type-toggle').forEach(function (item) {
      const visible = chart.data.datasets.some(function (dataset, index) {
        return seriesName(dataset.label) === item.dataset.gcType && chart.isDatasetVisible(index);
      });
      item.setAttribute('aria-pressed', String(visible));
    });
  };

  const resetLegendVisibility = function (legend, chart) {
    chart.data.datasets.forEach(function (_, index) {
      chart.setDatasetVisibility(index, true);
    });
    legend.dataset.soloMode = 'false';
    chart.update('none');
    updateLegendPressed(legend, chart);
  };

  const renderPodLegend = function (legend, data, chart, colorForPod, showSwatches) {
    if (!legend || !chart) {
      return;
    }
    legend.replaceChildren();
    legend.dataset.soloMode = 'false';
    const pods = [];
    data.datasets.forEach(function (dataset) {
      const pod = podName(dataset.label);
      if (!pods.includes(pod)) {
        pods.push(pod);
      }
    });
    pods.forEach(function (pod) {
      const podSeries = data.datasets.filter(function (item) {
        return podName(item.label) === pod;
      });
      const dataset = podSeries.find(function (item) {
        return seriesName(item.label) === 'User';
      }) || podSeries[0];
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'legend-series-toggle jvm-series-toggle';
      button.title = pod + ' (Command-click to show only this pod)';
      button.setAttribute('aria-label', 'Toggle ' + pod + '; Command-click to show only this pod');
      button.dataset.pod = pod;
      if (showSwatches !== false) {
        const swatch = document.createElement('span');
        swatch.className = 'legend-swatch';
        swatch.style.backgroundColor = colorForPod
          ? colorForPod(pod) || dataset.backgroundColor : dataset.backgroundColor;
        button.appendChild(swatch);
      }
      window.DashboardCharts.appendSeriesLegendLabel(button, pod);

      const podDatasets = function () {
        return chart.data.datasets
          .map(function (item, index) {
            return {item: item, index: index};
          })
          .filter(function (entry) {
            return podName(entry.item.label) === pod;
          });
      };
      const updatePressed = function () {
        const visible = podDatasets().some(function (entry) {
          return chart.isDatasetVisible(entry.index);
        });
        button.setAttribute('aria-pressed', String(visible));
      };
      button.addEventListener('click', function (event) {
        const entries = podDatasets();
        if (event.ctrlKey || event.metaKey) {
          legend.dataset.soloMode = 'true';
          chart.data.datasets.forEach(function (_, index) {
            chart.setDatasetVisibility(index, entries.some(function (entry) {
              return entry.index === index;
            }));
          });
        } else if (legend.dataset.soloMode === 'true') {
          resetLegendVisibility(legend, chart);
          return;
        } else {
          const visible = entries.some(function (entry) {
            return chart.isDatasetVisible(entry.index);
          });
          entries.forEach(function (entry) {
            chart.setDatasetVisibility(entry.index, !visible);
          });
        }
        chart.update('none');
        updateLegendPressed(legend, chart);
      });
      legend.appendChild(button);
      updatePressed();
    });
    window.DashboardCharts.renderSeriesLegendLabelToggle(legend);
  };

  const renderMemoryLegend = function () {
    renderPodLegend(memoryLegend, memory, memoryChart);
  };

  const renderCpuLegend = function () {
    renderPodLegend(cpuLegend, cpu, cpuChart, function (pod) {
      const memoryDataset = memory.datasets.find(function (item) {
        return podName(item.label) === pod;
      });
      return memoryDataset ? memoryDataset.backgroundColor : undefined;
    });
  };

  const cpuSeries = function () {
    const gapColor = getComputedStyle(document.body).backgroundColor;
    const userColors = ['#9acb8f', '#71b96d', '#4fa052', '#33863d'];
    const systemColors = ['#9ec5f8', '#75a9eb', '#4d88d2', '#2f6eb8'];
    const podIndexes = new Map();
    return cpu.datasets.map(function (dataset) {
      const pod = podName(dataset.label);
      if (!podIndexes.has(pod)) {
        podIndexes.set(pod, podIndexes.size);
      }
      const colorIndex = podIndexes.get(pod) % userColors.length;
      return {
        label: dataset.label,
        stack: 'cpu',
        data: dataset.data.map(function (value) {
          return value === null ? null : value / 1000;
        }),
        backgroundColor: seriesName(dataset.label) === 'System'
          ? systemColors[colorIndex]
          : userColors[colorIndex],
        categoryPercentage: 1,
        barPercentage: 1,
        borderWidth: 1,
        borderColor: gapColor,
        borderSkipped: false
      };
    });
  };

  const gcTypeOrder = ['Young', 'Concurrent', 'Full'];
  const gcColors = {
    Young: '#54b393',
    Concurrent: '#987edc',
    Full: '#ed695b',
    Other: '#8996a5'
  };
  const gcColor = function (type) {
    return gcColors[type] || gcColors.Other;
  };

  const gcSeries = function (data) {
    const pods = [];
    data.datasets.forEach(function (dataset) {
      const pod = podName(dataset.label);
      if (!pods.includes(pod)) {
        pods.push(pod);
      }
    });
    const podIndexes = new Map(pods.map(function (pod, index) {
      return [pod, index];
    }));
    return data.datasets.slice().sort(function (left, right) {
      const podDiff = podIndexes.get(podName(left.label)) - podIndexes.get(podName(right.label));
      if (podDiff !== 0) {
        return podDiff;
      }
      const leftType = seriesName(left.label);
      const rightType = seriesName(right.label);
      const leftOrder = gcTypeOrder.indexOf(leftType);
      const rightOrder = gcTypeOrder.indexOf(rightType);
      return (leftOrder < 0 ? gcTypeOrder.length : leftOrder)
        - (rightOrder < 0 ? gcTypeOrder.length : rightOrder);
    }).map(function (dataset) {
      const pod = podName(dataset.label);
      const type = seriesName(dataset.label);
      return {
        label: dataset.label,
        stack: pod,
        data: dataset.data,
        backgroundColor: gcColor(type),
        categoryPercentage: 1,
        barPercentage: 1,
        borderWidth: 1,
        borderColor: getComputedStyle(document.body).backgroundColor,
        borderSkipped: false
      };
    });
  };

  const renderGcLegend = function (legend, data, chart) {
    legend.replaceChildren();
    let podButtons = [];
    if (chart) {
      renderPodLegend(legend, data, chart, null, false);
      podButtons = Array.from(legend.querySelectorAll('.jvm-series-toggle'));
      legend.replaceChildren();
    } else {
      legend.classList.remove('legend-label-toggle-enabled', 'legend-labels-visible');
    }
    const types = Array.from(new Set(gcTypeOrder.concat(data.datasets.map(function (dataset) {
      return seriesName(dataset.label);
    }))))
      .sort(function (left, right) {
      const leftOrder = gcTypeOrder.indexOf(left);
      const rightOrder = gcTypeOrder.indexOf(right);
      return (leftOrder < 0 ? gcTypeOrder.length : leftOrder)
        - (rightOrder < 0 ? gcTypeOrder.length : rightOrder);
    });
    types.forEach(function (type) {
      const button = document.createElement(chart ? 'button' : 'span');
      if (chart) {
        button.type = 'button';
        button.className = 'legend-series-toggle jvm-gc-type-toggle';
        button.dataset.gcType = type;
        button.title = 'Toggle ' + type + ' GC across pods';
        button.setAttribute('aria-label', 'Toggle ' + type + ' GC across pods');
      } else {
        button.className = 'legend-series-toggle jvm-gc-type-key';
      }
      const swatch = document.createElement('span');
      swatch.className = 'legend-swatch';
      swatch.style.backgroundColor = gcColor(type);
      button.appendChild(swatch);
      window.DashboardCharts.appendSeriesLegendLabel(button, type);
      if (!chart) {
        legend.appendChild(button);
        return;
      }
      const matching = function () {
        return chart.data.datasets
          .map(function (dataset, index) { return {dataset: dataset, index: index}; })
          .filter(function (entry) { return seriesName(entry.dataset.label) === type; });
      };
      button.disabled = matching().length === 0;
      if (button.disabled) {
        button.title = type + ' GC has no samples in this range';
      } else {
        button.title = 'Toggle ' + type + ' GC across pods; Command-click to show only this type';
        button.setAttribute('aria-label',
          'Toggle ' + type + ' GC across pods; Command-click to show only this type');
      }
      const update = function () {
        button.setAttribute('aria-pressed', String(matching().some(function (entry) {
          return chart.isDatasetVisible(entry.index);
        })));
      };
      button.addEventListener('click', function (event) {
        if (event.ctrlKey || event.metaKey) {
          legend.dataset.soloMode = 'true';
          chart.data.datasets.forEach(function (dataset, index) {
            chart.setDatasetVisibility(index, seriesName(dataset.label) === type);
          });
          chart.update('none');
          updateLegendPressed(legend, chart);
          return;
        }
        if (legend.dataset.soloMode === 'true') {
          resetLegendVisibility(legend, chart);
          return;
        }
        const entries = matching();
        const visible = entries.some(function (entry) {
          return chart.isDatasetVisible(entry.index);
        });
        entries.forEach(function (entry) {
          chart.setDatasetVisibility(entry.index, !visible);
        });
        chart.update('none');
        updateLegendPressed(legend, chart);
      });
      legend.appendChild(button);
      update();
    });
    if (chart) {
      const podGroup = document.createElement('span');
      podGroup.className = 'jvm-pod-legend-group';
      podButtons.forEach(function (button) {
        podGroup.appendChild(button);
      });
      legend.appendChild(podGroup);
      window.DashboardCharts.renderSeriesLegendLabelToggle(legend);
    }
  };

  const createGcChart = function (canvas, legend, data, tooltipId, unit, axisTitle) {
    if (!data.labels || data.labels.length === 0 || data.datasets.length === 0) {
      renderGcLegend(legend, data, null);
      return null;
    }
    const chart = new Chart(canvas.getContext('2d'), {
      type: 'bar',
      data: {labels: data.labels, datasets: gcSeries(data)},
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        interaction: {mode: 'nearest', intersect: false},
        scales: {
          x: Object.assign(
            window.DashboardCharts.buildXScale(data.labels, data.bucketMinutes),
            {stacked: true}
          ),
          y: {
            stacked: true,
            beginAtZero: true,
            ticks: {precision: 0},
            title: {display: true, text: axisTitle}
          }
        },
        plugins: {
          legend: {display: false},
          tooltip: jvmTooltip(data.labels, tooltipId, unit, 'GC', 0),
          sharedCrosshair: window.DashboardCharts.crosshair(data)
        }
      }
    });
    renderGcLegend(legend, data, chart);
    return chart;
  };

  const render = function () {
    [memoryChart, cpuChart, gcTimeChart, gcCountChart].forEach(function (chart) {
      if (chart) {
        chart.destroy();
      }
    });
    memoryChart = null;
    cpuChart = null;
    gcTimeChart = null;
    gcCountChart = null;
    ['jvm-memory-tooltip', 'jvm-cpu-tooltip', 'jvm-gc-time-tooltip', 'jvm-gc-count-tooltip']
      .forEach(window.DashboardCharts.hideHtmlTooltip);
    if (memory.labels && memory.labels.length > 0) {
      memoryChart = new Chart(memoryCanvas.getContext('2d'), {
        type: 'line',
        data: {labels: memory.labels, datasets: series(memory)},
        options: {
          responsive: true,
          maintainAspectRatio: false,
          animation: false,
          interaction: {mode: 'nearest', intersect: false},
          scales: {
            x: window.DashboardCharts.buildXScale(memory.labels, memory.bucketMinutes),
            y: {beginAtZero: true, title: {display: true, text: 'MB'}}
          },
          plugins: {
            legend: {display: false},
            tooltip: jvmTooltip(memory.labels, 'jvm-memory-tooltip', ' MB', 'RSS', 1),
            sharedCrosshair: window.DashboardCharts.crosshair(memory)
          }
        }
      });
      renderMemoryLegend();
    } else {
      memoryLegend.replaceChildren();
    }
    if (cpu.labels && cpu.labels.length > 0 && cpu.datasets.length > 0) {
      cpuChart = new Chart(cpuCanvas.getContext('2d'), {
        type: 'bar',
        data: {labels: cpu.labels, datasets: cpuSeries()},
        options: {
          responsive: true,
          maintainAspectRatio: false,
          animation: false,
          interaction: {mode: 'nearest', intersect: false},
          scales: {
            x: Object.assign(
              window.DashboardCharts.buildXScale(cpu.labels, cpu.bucketMinutes),
              {stacked: true}
            ),
            y: {
              stacked: true,
              beginAtZero: true,
              title: {display: true, text: 'CPU cores'}
            }
          },
          plugins: {
            legend: {display: false},
            tooltip: jvmTooltip(cpu.labels, 'jvm-cpu-tooltip', ' cores', 'CPU', 2),
            sharedCrosshair: window.DashboardCharts.crosshair(cpu)
          }
        }
      });
      renderCpuLegend();
    } else {
      cpuLegend.replaceChildren();
    }
    gcTimeChart = createGcChart(gcTimeCanvas, gcTimeLegend, gcTime,
      'jvm-gc-time-tooltip', ' ms', 'GC time (ms)');
    gcCountChart = createGcChart(gcCountCanvas, gcCountLegend, gcCount,
      'jvm-gc-count-tooltip', ' collections', 'Collections');
    window.DashboardCharts.attachRangeSelection(memoryCanvas, function () {
      return memoryChart;
    }, function () {
      return memory;
    });
    window.DashboardCharts.attachRangeSelection(cpuCanvas, function () {
      return cpuChart;
    }, function () {
      return cpu;
    });
    window.DashboardCharts.attachRangeSelection(gcTimeCanvas, function () {
      return gcTimeChart;
    }, function () {
      return gcTime;
    });
    window.DashboardCharts.attachRangeSelection(gcCountCanvas, function () {
      return gcCountChart;
    }, function () {
      return gcCount;
    });
  };

  window.addEventListener('insight-top-data', function (event) {
    const data = event.detail;
    if (!data.jvmDashboard || !data.jvmMemory || !data.jvmCpu
      || !data.jvmGcTime || !data.jvmGcCount) {
      return;
    }
    const emptyMemory = !data.jvmMemory.labels.length && data.timeRange;
    const emptyCpu = !data.jvmCpu.labels.length && data.timeRange;
    const emptyGcTime = !data.jvmGcTime.labels.length && data.timeRange;
    const emptyGcCount = !data.jvmGcCount.labels.length && data.timeRange;
    memory = window.DashboardCharts.localize(emptyMemory
      ? window.DashboardCharts.emptyDataForRange(memory, data.timeRange) : data.jvmMemory);
    cpu = window.DashboardCharts.localize(emptyCpu
      ? window.DashboardCharts.emptyDataForRange(cpu, data.timeRange) : data.jvmCpu);
    gcTime = window.DashboardCharts.localize(emptyGcTime
      ? window.DashboardCharts.emptyDataForRange(gcTime, data.timeRange) : data.jvmGcTime);
    gcCount = window.DashboardCharts.localize(emptyGcCount
      ? window.DashboardCharts.emptyDataForRange(gcCount, data.timeRange) : data.jvmGcCount);
    render();
  });

  window.addEventListener('insight-theme-change', render);
  render();
})();
