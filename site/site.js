(() => {
  "use strict";

  const root = document.documentElement;
  root.classList.add("js");

  /* ---------------------------------------------------------------- theme */

  const button = document.querySelector("[data-theme-toggle]");
  const modes = ["system", "light", "dark"];
  let mode = "system";
  try {
    const saved = localStorage.getItem("beancount-site-theme");
    if (modes.includes(saved)) mode = saved;
  } catch (_) {
    /* System theme remains available when storage is blocked. */
  }

  const described = {
    system: "Theme: follows your device",
    light: "Theme: always light",
    dark: "Theme: always dark",
  };

  const applyTheme = (nextMode) => {
    mode = nextMode;
    if (mode === "system") root.removeAttribute("data-theme");
    else root.dataset.theme = mode;
    if (!button) return;
    button.dataset.themeMode = mode;
    button.title = described[mode];
    button.setAttribute("aria-label", `${described[mode]}. Activate to change.`);
    const text = button.querySelector("[data-theme-text]");
    if (text) text.textContent = described[mode];
  };
  applyTheme(mode);

  if (button) {
    button.addEventListener("click", () => {
      const next = modes[(modes.indexOf(mode) + 1) % modes.length];
      applyTheme(next);
      try { localStorage.setItem("beancount-site-theme", next); } catch (_) {}
    });
  }

  /* ---------------------------------------------------------------- motion */

  // Everything below only decorates content that is already complete in the HTML. With
  // reduced motion requested, nothing is touched, so every element keeps its final state.
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

  // Headline words rise out of their own line. The text nodes stay in the heading.
  document.querySelectorAll("[data-split]").forEach((heading) => {
    let i = 0;
    const words = heading.textContent.trim().split(/\s+/);
    heading.textContent = "";
    words.forEach((word, n) => {
      const outer = document.createElement("span");
      outer.className = "w";
      const inner = document.createElement("span");
      inner.style.setProperty("--i", i++);
      inner.textContent = word;
      outer.appendChild(inner);
      heading.appendChild(outer);
      if (n < words.length - 1) heading.appendChild(document.createTextNode(" "));
    });
  });

  // Odometer figures. The real text is kept for assistive technology; the reel is decoration.
  document.querySelectorAll("[data-roll]").forEach((el, index) => {
    const text = el.textContent;
    const label = document.createElement("span");
    label.className = "visually-hidden";
    label.textContent = text;
    const reel = document.createElement("span");
    reel.setAttribute("aria-hidden", "true");
    reel.style.display = "inline-flex";
    const cols = [];
    [...text].forEach((ch) => {
      if (!/\d/.test(ch)) {
        const plain = document.createElement("span");
        plain.textContent = ch;
        reel.appendChild(plain);
        return;
      }
      const digit = Number(ch);
      const box = document.createElement("span");
      box.className = "reel";
      const col = document.createElement("span");
      col.className = "reel-col";
      col.style.setProperty("--i", index);
      for (let k = 0; k <= 10 + digit; k++) {
        const s = document.createElement("span");
        s.textContent = String(k % 10);
        col.appendChild(s);
      }
      box.appendChild(col);
      reel.appendChild(box);
      cols.push([col, 10 + digit]);
    });
    el.textContent = "";
    el.append(label, reel);
    el.dataset.in = "";
    el.rollTo = () => cols.forEach(([col, stop]) => { col.style.transform = `translateY(${-stop * 0.9}em)`; });
  });

  // One observer marks things as they arrive on screen; each styles its own arrival in CSS.
  const arrive = (el) => {
    el.classList.add("is-in");
    if (el.rollTo) el.rollTo();
  };
  const watched = document.querySelectorAll("[data-in]");
  if ("IntersectionObserver" in window) {
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        arrive(entry.target);
        observer.unobserve(entry.target);
      });
    }, { rootMargin: "0px 0px -10%", threshold: 0.25 });
    watched.forEach((el) => observer.observe(el));
  } else {
    watched.forEach(arrive);
  }

  // The slip on the home page: type the transaction, keep a running sum, stamp it balanced.
  const slip = document.querySelector("[data-slip]");
  if (slip) {
    const lines = [...slip.querySelectorAll(".ln")];
    const tally = slip.querySelector(".tally-value");
    const finalText = tally ? tally.textContent : "";
    let delay = 1100;
    lines.forEach((line) => {
      const n = Math.max(1, line.textContent.length);
      line.style.setProperty("--n", n);
      line.style.setProperty("--d", `${delay}ms`);
      delay += n * 32 + 180;
    });
    const running = { first: slip.dataset.first, final: finalText };
    if (tally) { tally.textContent = "—"; tally.classList.add("is-pending"); }
    slip.classList.add("is-typing");

    let settled = false;
    const settle = () => {
      if (settled) return;
      settled = true;
      if (tally) { tally.textContent = running.final; tally.classList.remove("is-pending"); }
      slip.classList.remove("is-typing");
      slip.classList.add("is-balanced");
    };
    slip.settle = settle;
    lines.forEach((line) => {
      line.addEventListener("animationend", () => {
        if (line.dataset.posting === "1" && tally && !settled) tally.textContent = running.first;
        if (line.dataset.posting === "2") settle();
      });
    });
    // If an animation event never arrives (a throttled tab, a browser quirk), the entry
    // still settles on the balanced figure the HTML states.
    setTimeout(settle, delay + 600);
  }
})();
