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
    const divisor = unit === 's' ? 1000 : unit === 'min' ? 60000 : unit === 'h' ? 3600000 : 1;
    const amount = value / divisor;
    const rounded = Math.round(amount * 10) / 10;
    return String(rounded).replace(/\.0$/, '') + ' ' + unit;
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

  applyTheme();
  window.addEventListener('insight-theme-change', applyTheme);

  /** Sets a pointer cursor while hovering a clickable chart element. */
  function pointerOnHover(evt, elements) {
    if (evt.native && evt.native.target) {
      evt.native.target.style.cursor = elements.length ? 'pointer' : 'default';
    }
  }

  return {
    timeOnly, minutesOfDay, pickIntervalMinutes, buildXScale, tooltipOptions, localize,
    pointerOnHover, durationUnitFor, compactDuration, detailedDuration,
    themeColors, applyTheme
  };
})();
