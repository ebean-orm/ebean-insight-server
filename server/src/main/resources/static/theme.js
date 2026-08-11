/*
 * Applies and persists the dashboard color theme. This script is loaded in
 * the document head so the saved theme is applied before Pico.css paints.
 */
(function () {
  const storageKey = 'ebean-insight-theme';
  const root = document.documentElement;

  const preferredTheme = function () {
    const saved = window.localStorage.getItem(storageKey);
    if (saved === 'light' || saved === 'dark') {
      return saved;
    }
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  };

  const updateButton = function (theme) {
    const button = document.getElementById('theme-toggle');
    const label = document.getElementById('theme-toggle-label');
    if (!button || !label) {
      return;
    }
    const dark = theme === 'dark';
    button.setAttribute('aria-label', dark ? 'Switch to light mode' : 'Switch to dark mode');
    button.setAttribute('aria-pressed', String(dark));
    button.title = dark ? 'Switch to light mode' : 'Switch to dark mode';
    label.textContent = dark ? 'Light mode' : 'Dark mode';
  };

  const setTheme = function (theme, persist) {
    root.dataset.theme = theme;
    root.style.colorScheme = theme;
    if (persist) {
      window.localStorage.setItem(storageKey, theme);
    }
    updateButton(theme);
    window.dispatchEvent(new CustomEvent('insight-theme-change', {detail: {theme: theme}}));
  };

  setTheme(preferredTheme(), false);

  const init = function () {
    const button = document.getElementById('theme-toggle');
    if (!button) {
      return;
    }
    updateButton(root.dataset.theme);
    button.addEventListener('click', function () {
      setTheme(root.dataset.theme === 'dark' ? 'light' : 'dark', true);
    });
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
