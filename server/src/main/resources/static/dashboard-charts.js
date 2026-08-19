/*
 * Shared Chart.js helpers for the dashboard pages (query-total, metric-detail).
 * Centralises the Datadog-style x-axis tick reduction (fixed interval, aligned
 * to real clock-time boundaries rather than bucket index) and the tooltip
 * styling (solid background, larger font) so both pages stay consistent.
 */
window.DashboardCharts = (function () {

  function themeColors() {
    const styles = getComputedStyle(document.documentElement);
    return {
      background: styles.getPropertyValue('--pico-background-color').trim() || '#ffffff',
      codeBackground: styles.getPropertyValue('--pico-code-background-color').trim() || '#1a1a1a',
      grid: styles.getPropertyValue('--pico-muted-border-color').trim() || 'rgba(127, 127, 127, 0.25)',
      text: styles.getPropertyValue('--pico-color').trim() || '#373c44'
    };
  }

  function applyTheme() {
    if (typeof Chart === 'undefined') {
      return;
    }
    const colors = themeColors();
    Chart.defaults.color = colors.text;
    Chart.defaults.borderColor = colors.grid;
  }

  // Bucket labels are pre-formatted server-side as fixed-width "MM-dd HH:mm"
  // (see BUCKET_LABEL_FORMAT in the UI controllers), so the time-only portion
  // is always the last 5 characters.
  function timeOnly(label) {
    return label.slice(-5);
  }

  function timeZone() {
    const select = document.getElementById('time-zone-select');
    if (select) {
      return select.value === 'utc' ? 'UTC' : undefined;
    }
    return new URL(window.location.href).searchParams.get('tz') === 'utc' ? 'UTC' : undefined;
  }

  const timeZoneSelect = document.getElementById('time-zone-select');
  if (timeZoneSelect) {
    timeZoneSelect.value = new URL(window.location.href).searchParams.get('tz') === 'utc'
      ? 'utc' : 'browser';
  }

  function localize(data) {
    if (!data || !data.timestamps || data.timestamps.length !== data.labels.length) {
      return data;
    }
    const zone = timeZone();
    const formatter = new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23'
    });
    data.labels = data.timestamps.map(function (timestamp) {
      const parts = Object.fromEntries(formatter.formatToParts(new Date(timestamp))
        .map(function (part) { return [part.type, part.value]; }));
      return parts.month + '-' + parts.day + ' ' + parts.hour + ':' + parts.minute;
    });
    return data;
  }

  function emptyDataForRange(data, range) {
    if (!range) {
      return data;
    }
    const from = Number(range.from);
    const to = Number(range.to);
    if (!Number.isFinite(from) || !Number.isFinite(to) || from >= to) {
      return data;
    }
    const preferredBucketMinutes = Math.max(1, Number(data.bucketMinutes) || 1);
    const rangeMinutes = (to - from) / 60000;
    const bucketMinutes = Math.max(preferredBucketMinutes, Math.ceil(rangeMinutes / 180));
    const bucketMillis = bucketMinutes * 60000;
    const timestamps = [];
    for (let timestamp = Math.floor(from / bucketMillis) * bucketMillis; timestamp < to;
         timestamp += bucketMillis) {
      timestamps.push(timestamp);
    }
    return {
      labels: timestamps.map(function (timestamp) { return new Date(timestamp).toISOString(); }),
      timestamps: timestamps,
      datasets: [],
      bucketMinutes: bucketMinutes
    };
  }

  function minutesOfDay(label) {
    const [hh, mm] = timeOnly(label).split(':');
    return Number(hh) * 60 + Number(mm);
  }

  // A small, fixed number of evenly-spaced tick labels rather than one label
  // per bucket. Interval widens with the selected range (e.g. every 15min for
  // a 1h window, every 30min for up to 6h, etc.).
  function pickIntervalMinutes(totalMinutes) {
    const steps = [[60, 15], [360, 30], [720, 60], [1440, 120], [2880, 360], [10080, 1440]];
    for (const [maxMinutes, interval] of steps) {
      if (totalMinutes <= maxMinutes) {
        return interval;
      }
    }
    return 1440;
  }

  function durationUnitFor(maxMs) {
    if (maxMs < 1000) {
      return 'ms';
    }
    if (maxMs < 60000) {
      return 's';
    }
    if (maxMs < 3600000) {
      return 'min';
    }
    return 'h';
  }

  function compactDuration(value, unit) {
    return durationValue(value, unit) + ' ' + unit;
  }

  function durationValue(value, unit) {
    const divisor = unit === 's' ? 1000 : unit === 'min' ? 60000 : unit === 'h' ? 3600000 : 1;
    const rounded = Math.round((value / divisor) * 10) / 10;
    return String(rounded).replace(/\.0$/, '');
  }

  function detailedDuration(value) {
    if (value < 1000) {
      return Math.round(value) + ' ms';
    }
    if (value < 60000) {
      return compactDuration(value, 's');
    }
    if (value < 3600000) {
      return Math.floor(value / 60000) + 'm ' + Math.round((value % 60000) / 1000) + 's';
    }
    return Math.floor(value / 3600000) + 'h '
      + Math.floor((value % 3600000) / 60000) + 'm';
  }

  /**
   * Builds a Chart.js category-scale x-axis config: un-rotated, time-only
   * (date included only once the range spans multiple days) tick labels
   * aligned to real clock-time boundaries (e.g. :00/:15/:30/:45) rather than
   * an arbitrary offset from the window's start.
   */
  function buildXScale(labels, bucketMinutes) {
    const totalRangeMinutes = labels.length * (bucketMinutes || 1);
    const tickIntervalMinutes = pickIntervalMinutes(totalRangeMinutes);
    const showDate = totalRangeMinutes > 1440;
    return {
      afterBuildTicks: function (scale) {
        scale.ticks = scale.ticks.filter(function (tick) {
          const label = labels[tick.value];
          return label !== undefined && minutesOfDay(label) % tickIntervalMinutes === 0;
        });
      },
      grid: {
        color: themeColors().grid
      },
      ticks: {
        autoSkip: false,
        maxRotation: 0,
        minRotation: 0,
        color: themeColors().text,
        callback: function (value) {
          const label = labels[value];
          return showDate ? label : timeOnly(label);
        }
      }
    };
  }

  /** Solid (non-transparent), ~20% larger-font tooltip styling shared by all charts. */
  function tooltipOptions(labels, extraCallbacks) {
    const colors = themeColors();
    return {
      backgroundColor: colors.codeBackground,
      titleColor: colors.text,
      bodyColor: colors.text,
      footerColor: colors.text,
      titleFont: {size: 17},
      bodyFont: {size: 14},
      footerFont: {size: 14},
      padding: 10,
      caretPadding: 12,
      callbacks: Object.assign({
        title: function (items) {
          return items.length ? labels[items[0].dataIndex] : '';
        }
      }, extraCallbacks || {})
    };
  }

  function hideHtmlTooltip(tooltipId) {
    const container = document.getElementById(tooltipId);
    if (!container) {
      return;
    }
    container.classList.remove('is-visible');
    container.setAttribute('aria-hidden', 'true');
  }

  function htmlTooltip(labels, tooltipId, content) {
    return Object.assign(tooltipOptions(labels), {
      enabled: false,
      external: function (context) {
        const container = document.getElementById(tooltipId);
        const tooltip = context.tooltip;
        if (!container) {
          return;
        }
        if (tooltip.opacity === 0 || tooltip.dataPoints.length === 0) {
          hideHtmlTooltip(tooltipId);
          return;
        }

        const details = content(tooltip.dataPoints[0]);
        container.replaceChildren();

        const contextPanel = document.createElement('div');
        contextPanel.className = 'chart-tooltip-context';
        const time = document.createElement('span');
        time.className = 'chart-tooltip-time';
        time.textContent = tooltip.title && tooltip.title.length ? tooltip.title[0] : '';
        contextPanel.appendChild(time);
        const label = document.createElement('span');
        label.className = 'chart-tooltip-label';
        label.textContent = details.label;
        contextPanel.appendChild(label);
        container.appendChild(contextPanel);

        const measurement = document.createElement('div');
        measurement.className = 'chart-tooltip-measurement';
        const metric = document.createElement('span');
        metric.className = 'chart-tooltip-metric';
        metric.textContent = details.metric;
        measurement.appendChild(metric);
        const value = document.createElement('span');
        value.className = 'chart-tooltip-value';
        value.textContent = details.value;
        measurement.appendChild(value);
        container.appendChild(measurement);

        container.classList.add('is-visible');
        container.setAttribute('aria-hidden', 'false');
        const parent = container.parentElement;
        const maxLeft = Math.max(parent.clientWidth - container.offsetWidth, 0);
        const left = Math.min(Math.max(tooltip.caretX + 12, 0), maxLeft);
        const top = tooltip.caretY - container.offsetHeight - 12;
        container.style.left = left + 'px';
        container.style.top = top + 'px';
      }
    });
  }

  let sharedCrosshairTimestamp = null;

  function redrawSharedCrosshairs(source) {
    Object.values(Chart.instances).forEach(function (chart) {
      if (chart !== source && chart.options.plugins && chart.options.plugins.sharedCrosshair) {
        chart.draw();
      }
    });
  }

  function setSharedCrosshairTimestamp(timestamp, source) {
    if (sharedCrosshairTimestamp === timestamp) {
      return;
    }
    sharedCrosshairTimestamp = timestamp;
    redrawSharedCrosshairs(source);
  }

  const sharedCrosshair = {
    id: 'sharedCrosshair',
    afterEvent: function (chart, args) {
      const options = chart.options.plugins && chart.options.plugins.sharedCrosshair;
      if (!options) {
        return;
      }
      if (args.event.type === 'mouseout') {
        setSharedCrosshairTimestamp(null, chart);
        args.changed = true;
        return;
      }
      if (args.event.type !== 'mousemove' && args.event.type !== 'touchmove') {
        return;
      }
      const active = chart.getActiveElements();
      const timestamps = Array.from(options.timestamps || []);
      const timestamp = active.length ? timestamps[active[0].index] : null;
      setSharedCrosshairTimestamp(timestamp || null, chart);
      args.changed = true;
    },
    afterDraw: function (chart, _, options) {
      if (sharedCrosshairTimestamp === null || !chart.scales.x || !chart.chartArea) {
        return;
      }
      const timestamps = Array.from(options.timestamps || []);
      const index = timestamps.indexOf(sharedCrosshairTimestamp);
      if (index < 0) {
        return;
      }
      const x = chart.scales.x.getPixelForValue(index);
      if (x < chart.chartArea.left || x > chart.chartArea.right) {
        return;
      }
      const context = chart.ctx;
      context.save();
      context.globalAlpha = 0.6;
      context.strokeStyle = themeColors().text;
      context.lineWidth = 2;
      context.beginPath();
      context.moveTo(x, chart.chartArea.top);
      context.lineTo(x, chart.chartArea.bottom);
      context.stroke();
      context.restore();
    }
  };

  const rangeSelection = {
    id: 'rangeSelection',
    beforeDatasetsDraw: function (chart, _, options) {
      if (!options || options.start === undefined || options.end === undefined
        || !chart.scales.x || !chart.chartArea) {
        return;
      }
      const start = chart.scales.x.getPixelForValue(options.start);
      const end = chart.scales.x.getPixelForValue(options.end);
      const step = options.end > options.start
        ? (end - start) / (options.end - options.start)
        : chart.chartArea.width / Math.max(1, chart.data.labels.length);
      const left = Math.max(chart.chartArea.left, Math.min(start, end) - step / 2);
      const right = Math.min(chart.chartArea.right, Math.max(start, end) + step / 2);
      const context = chart.ctx;
      context.save();
      context.fillStyle = 'rgba(40, 80, 120, 0.12)';
      context.fillRect(left, chart.chartArea.top, right - left, chart.chartArea.height);
      context.restore();
    }
  };

  function crosshair(data) {
    return {timestamps: data.timestamps || []};
  }

  function renderSeriesLegend(container, chart) {
    if (!container || !chart) {
      return;
    }
    container.replaceChildren();
    const updateButtons = function () {
      container.querySelectorAll('.legend-series-toggle').forEach(function (button) {
        button.setAttribute('aria-pressed', String(chart.isDatasetVisible(Number(button.dataset.index))));
      });
    };
    chart.data.datasets.forEach(function (dataset, index) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'legend-series-toggle';
      button.title = dataset.label;
      button.setAttribute('aria-label', 'Toggle ' + dataset.label);
      button.dataset.index = String(index);
      const swatch = document.createElement('span');
      swatch.className = 'legend-swatch';
      swatch.style.backgroundColor = dataset.backgroundColor;
      button.appendChild(swatch);
      button.addEventListener('click', function (event) {
        if (event.ctrlKey || event.metaKey) {
          chart.setDatasetVisibility(index, !chart.isDatasetVisible(index));
        } else {
          const onlyThisSeries = chart.data.datasets.every(function (_, datasetIndex) {
            return datasetIndex === index
              ? chart.isDatasetVisible(datasetIndex)
              : !chart.isDatasetVisible(datasetIndex);
          });
          chart.data.datasets.forEach(function (_, datasetIndex) {
            chart.setDatasetVisibility(datasetIndex, onlyThisSeries || datasetIndex === index);
          });
        }
        chart.update('none');
        updateButtons();
      });
      container.appendChild(button);
    });
    updateButtons();
  }

  function attachRangeSelection(canvas, chartSupplier, dataSupplier) {
    if (!canvas || canvas.dataset.rangeSelectionAttached === 'true') {
      return;
    }
    canvas.dataset.rangeSelectionAttached = 'true';
    canvas.style.touchAction = 'none';
    let dragging = false;
    let startIndex = null;

    function indexAt(event, chart, data) {
      if (!chart || !chart.scales.x || !chart.chartArea || !data.timestamps.length) {
        return null;
      }
      const position = Chart.helpers.getRelativePosition(event, chart);
      if (position.x < chart.chartArea.left || position.x > chart.chartArea.right
        || position.y < chart.chartArea.top || position.y > chart.chartArea.bottom) {
        return null;
      }
      return Math.max(0, Math.min(data.timestamps.length - 1,
        Math.round(chart.scales.x.getValueForPixel(position.x))));
    }

    function finish(event) {
      if (!dragging) {
        return;
      }
      const chart = chartSupplier();
      const data = dataSupplier();
      const endIndex = indexAt(event, chart, data);
      dragging = false;
      if (canvas.hasPointerCapture(event.pointerId)) {
        canvas.releasePointerCapture(event.pointerId);
      }
      if (endIndex === null || endIndex === startIndex) {
        setRangeSelection(chart, null, null);
        startIndex = null;
        return;
      }
      const start = Math.min(startIndex, endIndex);
      const end = Math.max(startIndex, endIndex);
      const bucketMillis = (data.bucketMinutes || 1) * 60 * 1000;
      const current = new URLSearchParams(window.location.search);
      current.set('from', new Date(data.timestamps[start] - 1).toISOString());
      current.set('to', new Date(data.timestamps[end] + bucketMillis - 1).toISOString());
      window.location.href = window.location.pathname + '?' + current.toString();
      startIndex = null;
    }

    canvas.addEventListener('pointerdown', function (event) {
      if (event.button !== 0) {
        return;
      }
      const chart = chartSupplier();
      const data = dataSupplier();
      const index = indexAt(event, chart, data);
      if (index === null) {
        return;
      }
      dragging = true;
      startIndex = index;
      setRangeSelection(chart, index, index);
      canvas.setPointerCapture(event.pointerId);
      event.preventDefault();
    });
    canvas.addEventListener('pointermove', function (event) {
      if (dragging) {
        const chart = chartSupplier();
        const data = dataSupplier();
        const index = indexAt(event, chart, data);
        if (index !== null) {
          setRangeSelection(chart, startIndex, index);
        }
        event.preventDefault();
      }
    });
    canvas.addEventListener('pointerup', finish);
    canvas.addEventListener('pointercancel', function (event) {
      if (dragging) {
        dragging = false;
        startIndex = null;
        setRangeSelection(chartSupplier(), null, null);
        if (canvas.hasPointerCapture(event.pointerId)) {
          canvas.releasePointerCapture(event.pointerId);
        }
      }
    });
  }

  function setRangeSelection(chart, start, end) {
    if (!chart) {
      return;
    }
    chart.options.plugins.rangeSelection = start === null ? null : {start, end};
    chart.update('none');
  }

  applyTheme();
  if (typeof Chart !== 'undefined') {
    Chart.register(sharedCrosshair);
    Chart.register(rangeSelection);
  }
  window.addEventListener('insight-theme-change', applyTheme);

  /** Sets a pointer cursor while hovering a clickable chart element. */
  function pointerOnHover(evt, elements) {
    if (evt.native && evt.native.target) {
      evt.native.target.style.cursor = elements.length ? 'pointer' : 'default';
    }
  }

  return {
    timeOnly, minutesOfDay, pickIntervalMinutes, buildXScale, tooltipOptions, htmlTooltip,
    hideHtmlTooltip, localize, emptyDataForRange,
    pointerOnHover, durationUnitFor, compactDuration, detailedDuration,
    durationValue, attachRangeSelection,
    themeColors, applyTheme, crosshair, setSharedCrosshairTimestamp, renderSeriesLegend
  };
})();
