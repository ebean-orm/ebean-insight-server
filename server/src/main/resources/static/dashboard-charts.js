/*
 * Shared Chart.js helpers for the dashboard pages (query-total, metric-detail).
 * Centralises the Datadog-style x-axis tick reduction (fixed interval, aligned
 * to real clock-time boundaries rather than bucket index) and the tooltip
 * styling (solid background, larger font) so both pages stay consistent.
 */
window.DashboardCharts = (function () {

  // Bucket labels are pre-formatted server-side as fixed-width "MM-dd HH:mm"
  // (see BUCKET_LABEL_FORMAT in the UI controllers), so the time-only portion
  // is always the last 5 characters.
  function timeOnly(label) {
    return label.slice(-5);
  }

  function minutesOfDay(label) {
    const [hh, mm] = timeOnly(label).split(':');
    return Number(hh) * 60 + Number(mm);
  }

  // A small, fixed number of evenly-spaced tick labels rather than one label
  // per bucket. Interval widens with the selected range (e.g. every 15min for
  // a 1h window, every 30min for up to 6h, etc.).
  function pickIntervalMinutes(totalMinutes) {
    const steps = [[60, 15], [360, 30], [720, 60], [1440, 120], [2880, 240], [10080, 720]];
    for (const [maxMinutes, interval] of steps) {
      if (totalMinutes <= maxMinutes) {
        return interval;
      }
    }
    return 1440;
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
      ticks: {
        autoSkip: false,
        maxRotation: 0,
        minRotation: 0,
        callback: function (value) {
          const label = labels[value];
          return showDate ? label : timeOnly(label);
        }
      }
    };
  }

  /** Solid (non-transparent), ~20% larger-font tooltip styling shared by all charts. */
  function tooltipOptions(labels, extraCallbacks) {
    return {
      backgroundColor: '#1a1a1a',
      titleFont: {size: 17},
      bodyFont: {size: 14},
      footerFont: {size: 14},
      padding: 10,
      callbacks: Object.assign({
        title: function (items) {
          return items.length ? labels[items[0].dataIndex] : '';
        }
      }, extraCallbacks || {})
    };
  }

  /** Sets a pointer cursor while hovering a clickable chart element. */
  function pointerOnHover(evt, elements) {
    if (evt.native && evt.native.target) {
      evt.native.target.style.cursor = elements.length ? 'pointer' : 'default';
    }
  }

  return {timeOnly, minutesOfDay, pickIntervalMinutes, buildXScale, tooltipOptions, pointerOnHover};
})();
