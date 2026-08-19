(function () {
  const memoryElement = document.getElementById('jvm-memory-data');
  const cpuElement = document.getElementById('jvm-cpu-data');
  const memoryCanvas = document.getElementById('jvm-memory-chart');
  const cpuCanvas = document.getElementById('jvm-cpu-chart');
  const memoryLegend = document.getElementById('jvm-memory-legend');
  const cpuLegend = document.getElementById('jvm-cpu-legend');
  if (!memoryElement || !cpuElement || !memoryCanvas || !cpuCanvas
    || typeof Chart === 'undefined' || !window.DashboardCharts) {
    return;
  }

  let memory = window.DashboardCharts.localize(JSON.parse(memoryElement.textContent));
  let cpu = window.DashboardCharts.localize(JSON.parse(cpuElement.textContent));
  let memoryChart = null;
  let cpuChart = null;

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

  const renderPodLegend = function (legend, data, chart, colorForPod) {
    if (!legend || !chart) {
      return;
    }
    legend.replaceChildren();
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
      button.title = pod;
      button.setAttribute('aria-label', 'Toggle ' + pod);
      button.dataset.pod = pod;
      const swatch = document.createElement('span');
      swatch.className = 'legend-swatch';
      swatch.style.backgroundColor = colorForPod ? colorForPod(pod) || dataset.backgroundColor : dataset.backgroundColor;
      button.appendChild(swatch);

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
          const visible = entries.some(function (entry) {
            return chart.isDatasetVisible(entry.index);
          });
          entries.forEach(function (entry) {
            chart.setDatasetVisibility(entry.index, !visible);
          });
        } else {
          const onlyThisPod = chart.data.datasets.every(function (_, index) {
            const selected = entries.some(function (entry) {
              return entry.index === index;
            });
            return selected ? chart.isDatasetVisible(index) : !chart.isDatasetVisible(index);
          });
          chart.data.datasets.forEach(function (_, index) {
            chart.setDatasetVisibility(index, onlyThisPod || entries.some(function (entry) {
              return entry.index === index;
            }));
          });
        }
        chart.update('none');
        legend.querySelectorAll('.legend-series-toggle').forEach(function (item) {
          const itemPod = item.dataset.pod;
          const itemEntries = chart.data.datasets
            .map(function (dataset, index) {
              return {dataset: dataset, index: index};
            })
            .filter(function (entry) {
              return podName(entry.dataset.label) === itemPod;
            });
          const itemVisible = itemEntries.some(function (entry) {
            return chart.isDatasetVisible(entry.index);
          });
          item.setAttribute('aria-pressed', String(itemVisible));
        });
      });
      legend.appendChild(button);
      updatePressed();
    });
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

  const render = function () {
    if (memoryChart) {
      memoryChart.destroy();
      memoryChart = null;
    }
    if (cpuChart) {
      cpuChart.destroy();
      cpuChart = null;
    }
    window.DashboardCharts.hideHtmlTooltip('jvm-memory-tooltip');
    window.DashboardCharts.hideHtmlTooltip('jvm-cpu-tooltip');
    if (!memory.labels || memory.labels.length === 0) {
      memoryLegend.replaceChildren();
      cpuLegend.replaceChildren();
      return;
    }
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
    if (cpu.labels && cpu.labels.length > 0) {
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
    }
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
  };

  window.addEventListener('insight-top-data', function (event) {
    const data = event.detail;
    if (!data.jvmDashboard || !data.jvmMemory || !data.jvmCpu) {
      return;
    }
    const emptyJvmData = !data.jvmMemory.labels.length && data.timeRange;
    memory = window.DashboardCharts.localize(emptyJvmData
      ? window.DashboardCharts.emptyDataForRange(memory, data.timeRange) : data.jvmMemory);
    cpu = window.DashboardCharts.localize(emptyJvmData
      ? window.DashboardCharts.emptyDataForRange(cpu, data.timeRange) : data.jvmCpu);
    render();
  });

  window.addEventListener('insight-theme-change', render);
  render();
})();
