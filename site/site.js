(() => {
  "use strict";

  const root = document.documentElement;
  root.classList.add("js");
  const button = document.querySelector("[data-theme-toggle]");
  const modes = ["system", "light", "dark"];
  let mode = "system";

  try {
    const saved = localStorage.getItem("beancount-site-theme");
    if (modes.includes(saved)) mode = saved;
  } catch (_) {
    /* System theme remains available when storage is blocked. */
  }

  // Said in full rather than as one word. The control used to read "Theme: System", which
  // is only meaningful if you already know what it means; now the icon carries the state and
  // the words explain it, in the tooltip, to a screen reader, and in the hidden label.
  const described = {
    system: "Theme: follows your device",
    light: "Theme: always light",
    dark: "Theme: always dark",
  };

  const applyTheme = (nextMode) => {
    mode = nextMode;
    if (mode === "system") root.removeAttribute("data-theme");
    else root.dataset.theme = mode;

    if (button) {
      // Which of the three icons is showing is decided in CSS from this attribute, so the
      // markup stays declarative and there is one place that knows the mapping.
      button.dataset.themeMode = mode;
      button.title = described[mode];
      button.setAttribute("aria-label", `${described[mode]}. Activate to change.`);
      const text = button.querySelector("[data-theme-text]");
      if (text) text.textContent = described[mode];
    }
  };

  applyTheme(mode);

  if (button) {
    button.addEventListener("click", () => {
      const next = modes[(modes.indexOf(mode) + 1) % modes.length];
      applyTheme(next);
      try { localStorage.setItem("beancount-site-theme", next); } catch (_) {}
    });
  }

  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const revealItems = document.querySelectorAll("[data-reveal]");

  if (reduceMotion.matches || !("IntersectionObserver" in window)) {
    revealItems.forEach((item) => item.classList.add("is-visible"));
    return;
  }

  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      entry.target.classList.add("is-visible");
      observer.unobserve(entry.target);
    });
  }, { rootMargin: "0px 0px -8%", threshold: 0.12 });

  revealItems.forEach((item) => observer.observe(item));
})();
