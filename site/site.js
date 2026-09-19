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
    document.dispatchEvent(new CustomEvent("sitetheme"));
  };
  applyTheme(mode);

  if (button) {
    button.addEventListener("click", () => {
      const next = modes[(modes.indexOf(mode) + 1) % modes.length];
      applyTheme(next);
      try { localStorage.setItem("beancount-site-theme", next); } catch (_) {}
    });
  }

  /* ----------------------------------------------------------- screenshots */

  // The screenshot gallery on the home page: real captures of the app, each in a light and a
  // dark version of the same state. It opens in the page's own theme (the inline script after
  // the gallery already set that before first paint), turns over once to show the other theme,
  // and opens each screen in a dialog with notes. Everything here enhances plain links to
  // full-size images, which is what a visitor without JavaScript gets.
  (() => {
    const list = document.querySelector("[data-shots]");
    const dialog = document.querySelector("[data-shot-dialog]");
    if (!list) return;

    const items = [...list.querySelectorAll(".shot")];
    const imgs = items.map((li) => li.querySelector("img"));
    const links = items.map((li) => li.querySelector(".shot-open"));
    const control = document.querySelector(".shots-theme");
    const buttons = control ? [...control.querySelectorAll("[data-shots-theme]")] : [];
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
    const darkScheme = window.matchMedia("(prefers-color-scheme: dark)");
    const src = (key, t, size) => `assets/screenshots/${key}-${t}-${size}.webp`;
    const siteTheme = () => root.dataset.theme || (darkScheme.matches ? "dark" : "light");

    const WAIT = 4700;     // how long the first set is shown before it turns over
    const STAGGER = 140;   // between one screenshot turning and the next
    const SESSION = "beanbook-shots-turned";

    let theme = list.dataset.theme || siteTheme();
    let manual = false;
    let flipping = false;
    let done = false;
    try { done = sessionStorage.getItem(SESSION) === "1"; } catch (_) { /* no storage: may run */ }

    const reflect = () => {
      list.dataset.theme = theme;
      buttons.forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.shotsTheme === theme)));
    };
    const show = (t) => {
      theme = t;
      items.forEach((li, i) => { imgs[i].src = src(li.dataset.shot, t, 540); });
      reflect();
    };
    if (control) control.hidden = false;
    reflect();

    /* -- the one turn-over ------------------------------------------------ */

    let timer = 0;
    let armed = false;
    let observer = null;
    const remember = () => { try { sessionStorage.setItem(SESSION, "1"); } catch (_) { /* fine */ } };
    const cancel = () => {
      if (done) return;
      done = true;
      armed = false;
      clearTimeout(timer);
      if (observer) observer.disconnect();
      remember();
    };

    const turnOver = () => {
      if (done || document.hidden || reduce.matches) return;
      const next = theme === "light" ? "dark" : "light";
      // The other set is decoded first, so no card turns to a blank face.
      Promise.all(items.map((li) => {
        const image = new Image();
        image.src = src(li.dataset.shot, next, 540);
        return image.decode();
      })).then(() => {
        if (done || document.hidden || reduce.matches) return;
        done = true;
        armed = false;
        remember();
        flipping = true;
        theme = next;
        items.forEach((li, i) => {
          const card = li.querySelector(".shot-card");
          window.setTimeout(() => {
            card.addEventListener("animationend", () => {
              // The face changes at the edge-on midpoint, never while it is visible.
              imgs[i].src = src(li.dataset.shot, theme, 540);
              card.classList.remove("is-turning-out");
              card.classList.add("is-turning-in");
              card.addEventListener("animationend", () => {
                card.classList.remove("is-turning-in");
                // The control changes with the last card, so it never names a set not yet shown.
                if (i === items.length - 1) { flipping = false; reflect(); }
              }, { once: true });
            }, { once: true });
            card.classList.add("is-turning-out");
          }, i * STAGGER);
        });
      }, () => { /* the other set did not load: leave the gallery as it is */ });
    };

    const arm = () => {
      armed = true;
      clearTimeout(timer);
      timer = window.setTimeout(turnOver, WAIT);
    };

    if (!done && !reduce.matches && "IntersectionObserver" in window) {
      observer = new IntersectionObserver((entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return;
        observer.disconnect();
        observer = null;
        // Lazy images outside the viewport (the swipeable row on a phone) never load on their
        // own, and waiting for them held the turn-over back forever. Load them all now.
        imgs.forEach((image) => { image.loading = "eager"; });
        Promise.all(imgs.map((image) => image.decode())).then(arm, () => {});
      }, { threshold: 0.35 });
      observer.observe(list);
      document.addEventListener("visibilitychange", () => {
        if (!armed || done) return;
        if (document.hidden) clearTimeout(timer);
        else arm();
      });
    }

    // Any attention paid to the gallery ends the surprise for the rest of the visit.
    // A real hover, not a screenshot scrolling under a cursor that has not moved: scrolling
    // past the gallery with the mouse resting on the page used to cancel it every time.
    items.forEach((li) => {
      li.addEventListener("pointermove", (event) => {
        if (event.pointerType === "mouse" && (event.movementX || event.movementY)) cancel();
      });
    });
    list.addEventListener("focusin", cancel);
    list.addEventListener("keydown", cancel);
    reduce.addEventListener("change", () => { if (reduce.matches) cancel(); });

    buttons.forEach((b) => {
      b.addEventListener("click", () => {
        cancel();
        manual = true;
        show(b.dataset.shotsTheme);
      });
    });

    // Follow the page's theme when it changes, unless the visitor chose a set themselves.
    const follow = () => { if (!manual && !flipping) show(siteTheme()); };
    document.addEventListener("sitetheme", follow);
    darkScheme.addEventListener("change", () => { if (!root.dataset.theme) follow(); });

    /* -- the annotated dialog -------------------------------------------- */

    if (!dialog || typeof dialog.showModal !== "function") return;

    // x and y are where each note points, as percentages of the screenshot.
    const NOTES = {
      home: {
        title: "Home",
        overview: "The first screen: where the open ledger stands this month, and anything that needs attention.",
        notes: [
          { x: 51.3, y: 7.66, label: "Switch projects", text: "The ledger you are in, its number of entries and whether Beancount found errors. Tap to open another." },
          { x: 63.8, y: 16.41, label: "Totals from your entries", text: "Net worth, income and expenses, worked out by Beancount and double-ruled like a closed column." },
          { x: 46.3, y: 38.33, via: 35.42, label: "Where the money went", text: "This month's spending by category, compared with the month before." },
          { x: 92.22, y: 79.58, label: "Add an entry", text: "Opens the entry form from any main screen." },
        ],
      },
      entry: {
        title: "New entry",
        overview: "Recording a transaction with a form. This one was copied from an earlier grocery shop.",
        notes: [
          { x: 85.83, y: 15.89, label: "Transactions and more", text: "Switch to other Beancount entries: balance checks, prices, notes, documents." },
          { x: 39.35, y: 44.22, label: "Reuse the last entry", text: "Fills in the accounts used the last time for this payee." },
          { x: 87.31, y: 61.93, label: "Tags, details and receipts", text: "Everything that belongs to the whole transaction, including a receipt to scan or attach." },
          { x: 92.59, y: 81.98, label: "Postings", text: "Each posting is an account and an amount. The form says what does not balance before you save." },
        ],
      },
      journal: {
        title: "Journal",
        overview: "Every entry in the ledger, newest first, with the accounts and amounts it moves.",
        notes: [
          { x: 91.67, y: 17.6, label: "Search", text: "Find entries by date, payee, description or account." },
          { x: 62.96, y: 25.52, label: "Open an entry", text: "Tap to see an entry and copy, edit or delete it. Hold to select several." },
          { x: 9.26, y: 43.59, via: 40.21, label: "Cleared or pending", text: "Two equal rules mean cleared; a half-drawn rule marks an entry flagged for another look." },
          { x: 55.46, y: 63.02, label: "Linked receipts", text: "An entry with a document says so, and its page opens the file." },
        ],
      },
      reports: {
        title: "Reports",
        overview: "Fava, the web interface for Beancount, running on the phone and reading the same files.",
        notes: [
          { x: 80.56, y: 15.62, label: "Fava on your device", text: "The balance sheet, income statement, journal and queries people use with Beancount on a computer." },
          { x: 69.44, y: 22.08, label: "Filters", text: "Narrow any report by time, account, tag or payee." },
          { x: 96.3, y: 37.19, label: "Chart options", text: "Stacked or single bars, at cost or at market value, by month or by year." },
          { x: 70.37, y: 51.56, label: "Charts from your entries", text: "Income and expenses by month, drawn from the ledger itself." },
        ],
      },
      documents: {
        title: "Documents",
        overview: "Receipts and statements in the project, each filed under an account or linked from an entry.",
        notes: [
          { x: 79.26, y: 16.93, label: "Add and organize", text: "Scan or attach on Add; Organize lists every document in the project." },
          { x: 57.13, y: 32.92, label: "Filed where it belongs", text: "Each document with the account it is filed under." },
          { x: 91.85, y: 37.81, label: "Actions on the file", text: "View it, share it, move it to another account, unlink it, or delete the file." },
        ],
      },
    };

    const title = dialog.querySelector("[data-shot-title]");
    const overview = dialog.querySelector("[data-shot-overview]");
    const count = dialog.querySelector("[data-shot-count]");
    const figure = dialog.querySelector("[data-shot-figure]");
    const bigImage = dialog.querySelector("[data-shot-image]");
    const notesList = dialog.querySelector("[data-shot-notes]");
    const prev = dialog.querySelector("[data-shot-prev]");
    const next = dialog.querySelector("[data-shot-next]");
    const wide = window.matchMedia("(min-width: 56rem)");
    let current = 0;
    let opener = null;

    const svgNS = "http://www.w3.org/2000/svg";
    let leaders = null;

    // On a wide screen each note sits beside the screenshot at the height it points to, joined
    // by a thin rule; notes that would collide are nudged apart. On a narrow one the notes are
    // an ordered list under the image and only the numbered markers remain on it.
    const layout = () => {
      const notes = [...notesList.children];
      if (leaders) { leaders.remove(); leaders = null; }
      notes.forEach((li) => { li.style.top = ""; });
      if (!wide.matches || !bigImage.complete) return;
      const body = figure.parentElement;
      const box = body.getBoundingClientRect();
      const img = bigImage.getBoundingClientRect();
      const listBox = notesList.getBoundingClientRect();
      let floor = 0;
      const placed = notes.map((li) => {
        const y = img.top - listBox.top + (Number(li.dataset.via) / 100) * img.height;
        const top = Math.max(y - 14, floor);
        li.style.top = `${top}px`;
        floor = top + li.offsetHeight + 12;
        return { li, y };
      });
      leaders = document.createElementNS(svgNS, "svg");
      leaders.setAttribute("class", "shot-leaders");
      leaders.setAttribute("aria-hidden", "true");
      leaders.setAttribute("width", box.width);
      leaders.setAttribute("height", box.height);
      placed.forEach(({ li, y }) => {
        const mx = img.left - box.left + (Number(li.dataset.x) / 100) * img.width;
        const my = img.top - box.top + (Number(li.dataset.y) / 100) * img.height;
        const ex = img.right - box.left + 8;
        const lx = listBox.left - box.left - 6;
        const ly = listBox.top - box.top + li.offsetTop + 14;
        const line = document.createElementNS(svgNS, "polyline");
        // From the component itself, out past the screenshot's edge, to its note. A thin line
        // hides nothing on the screen; it is drawn twice so it reads on light and dark screens.
        const vy = listBox.top - box.top + y;
        const points = `${mx},${my} ${mx},${vy} ${ex},${vy} ${ex + 14},${vy} ${lx},${ly}`;
        const halo = document.createElementNS(svgNS, "polyline");
        halo.setAttribute("class", "halo");
        halo.setAttribute("points", points);
        line.setAttribute("points", points);
        leaders.append(halo, line);
      });
      body.appendChild(leaders);
    };

    const fill = (i) => {
      current = i;
      const key = items[i].dataset.shot;
      const data = NOTES[key];
      title.textContent = data.title;
      overview.textContent = data.overview;
      count.textContent = `Screen ${i + 1} of ${items.length}, ${theme} theme`;
      bigImage.alt = imgs[i].alt;
      bigImage.src = src(key, theme, 1080);
      figure.querySelectorAll(".shot-marker").forEach((m) => m.remove());
      notesList.replaceChildren();
      data.notes.forEach((note, n) => {
        const marker = document.createElement("span");
        marker.className = "shot-marker";
        marker.setAttribute("aria-hidden", "true");
        const badge = document.createElement("span");
        badge.textContent = String(n + 1);
        marker.appendChild(badge);
        marker.style.left = `${note.x}%`;
        marker.style.top = `${note.y}%`;
        figure.appendChild(marker);
        const li = document.createElement("li");
        li.dataset.x = note.x;
        li.dataset.y = note.y;
        // Where the line leaves the component, when going straight across would strike through
        // text: first to the blank gap at this height, then out to the edge.
        li.dataset.via = note.via ?? note.y;
        const label = document.createElement("strong");
        label.textContent = note.label;
        const text = document.createElement("span");
        text.textContent = note.text;
        li.append(label, text);
        notesList.appendChild(li);
      });
      const before = items[i - 1] && NOTES[items[i - 1].dataset.shot];
      const after = items[i + 1] && NOTES[items[i + 1].dataset.shot];
      prev.disabled = !before;
      next.disabled = !after;
      prev.textContent = before ? `Previous: ${before.title}` : "Previous";
      next.textContent = after ? `Next: ${after.title}` : "Next";
      if (bigImage.complete) layout();
      else bigImage.addEventListener("load", layout, { once: true });
    };

    const open = (i) => {
      cancel();
      opener = links[i];
      fill(i);
      root.classList.add("dialog-open");
      dialog.showModal();
      dialog.querySelector("[data-shot-close]").focus();
    };

    links.forEach((link, i) => {
      link.addEventListener("click", (event) => { event.preventDefault(); open(i); });
      // A link opens with Enter; Space should too, as for any control that opens something.
      link.addEventListener("keydown", (event) => {
        if (event.key === " ") { event.preventDefault(); open(i); }
      });
    });
    prev.addEventListener("click", () => { if (current > 0) { fill(current - 1); opener = links[current]; } });
    next.addEventListener("click", () => { if (current < items.length - 1) { fill(current + 1); opener = links[current]; } });
    dialog.querySelector("[data-shot-close]").addEventListener("click", () => dialog.close());
    // A click on the dim surround, outside the panel, closes it too.
    dialog.addEventListener("click", (event) => { if (event.target === dialog) dialog.close(); });
    dialog.addEventListener("keydown", (event) => {
      if (event.key === "ArrowRight" && !next.disabled) next.click();
      if (event.key === "ArrowLeft" && !prev.disabled) prev.click();
    });
    dialog.addEventListener("close", () => {
      root.classList.remove("dialog-open");
      if (leaders) { leaders.remove(); leaders = null; }
      if (opener) opener.focus();
    });
    window.addEventListener("resize", () => { if (dialog.open) layout(); });
    wide.addEventListener("change", () => { if (dialog.open) layout(); });
  })();

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
