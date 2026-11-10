// Front-page code switcher: shows the generator's real output (samples.js, made by
// scripts/build-site.py) for the chosen language, flow and values.
(function () {
  var samples = window.EVIA_SAMPLES || {};
  var state = { lang: "c++20", flow: "structured", values: "safe" };
  var codeEl = document.getElementById("sample");
  var outEl = document.getElementById("output");
  var noteEl = document.getElementById("sample-note");

  var PY_KEYWORDS = /\b(def|class|return|if|elif|else|while|for|in|not|and|or|break|pass|global|raise|try|except|True|False|None|self)\b/g;
  var KEYWORDS = /\b(void|int|double|static|return|if|else|for|while|do|switch|case|break|default|struct|const|goto|throw|try|catch|namespace|using|typedef|char|unsigned|sizeof|auto|new|delete|nullptr|fun|val|var|null|true|false|is|when|final|class)\b/g;

  function esc(s) {
    return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  // Comments, strings and keywords; labels of goto flow ("L3:") stand out too.
  function highlight(src, py) {
    var keywords = py ? PY_KEYWORDS : KEYWORDS;
    return src.split("\n").map(function (line) {
      var m = py ? line.match(/^(.*?)(#.*)$/) : line.match(/^(.*?)(\/\/.*)$/);
      var code = m ? m[1] : line, comment = m ? m[2] : "";
      if (code.indexOf('"') >= 0 && comment) {   // a // inside a string literal is not a comment
        var quotes = (code.match(/"/g) || []).length;
        if (quotes % 2 === 1) { code = line; comment = ""; }
      }
      var parts = code.split(/("(?:[^"\\]|\\.)*")/);
      var html = parts.map(function (p, i) {
        if (i % 2 === 1) return '<span class="tok-s">' + esc(p) + "</span>";
        var e = esc(p).replace(keywords, '<span class="tok-k">$1</span>');
        return e.replace(/^(L\d+(?:_\w+)?):$/, '<span class="tok-l">$1</span>:');
      }).join("");
      return html + (comment ? '<span class="tok-c">' + esc(comment) + "</span>" : "");
    }).join("\n");
  }

  function show() {
    var key = state.lang + "/" + state.flow + "/" + state.values;
    var s = samples[key];
    if (!s) return;
    var py = state.lang === "python";
    codeEl.innerHTML = highlight(s.code, py);
    var jvm = state.lang === "java" || state.lang === "kotlin";
    var fn = py || jvm ? "Program.run()" : state.lang.indexOf("c++") === 0 ? "Program::run()" : "run_program()";
    var label = document.querySelector('[data-lang="' + state.lang + '"]').textContent;
    noteEl.textContent = (noteEl.dataset.note || "{lang}: {fn}, {lines}")   // the page's language
      .replace("{lang}", label).replace("{fn}", fn).replace("{lines}", s.lines);
    document.querySelectorAll("[data-lang]").forEach(function (b) {
      b.setAttribute("aria-selected", b.dataset.lang === state.lang ? "true" : "false");
      b.tabIndex = b.dataset.lang === state.lang ? 0 : -1;
    });
    document.querySelectorAll("[data-flow]").forEach(function (b) {
      b.setAttribute("aria-checked", b.dataset.flow === state.flow ? "true" : "false");
    });
    document.querySelectorAll("[data-values]").forEach(function (b) {
      b.setAttribute("aria-checked", b.dataset.values === state.values ? "true" : "false");
    });
  }

  document.querySelectorAll("[data-lang]").forEach(function (b) {
    b.addEventListener("click", function () { state.lang = b.dataset.lang; show(); });
    b.addEventListener("keydown", function (e) {   // arrow keys move between the language tabs
      if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
      var all = Array.prototype.slice.call(document.querySelectorAll("[data-lang]"));
      var i = all.indexOf(b) + (e.key === "ArrowRight" ? 1 : -1);
      var next = all[(i + all.length) % all.length];
      state.lang = next.dataset.lang;
      show();
      next.focus();
    });
  });
  document.querySelectorAll("[data-flow]").forEach(function (b) {
    b.addEventListener("click", function () { state.flow = b.dataset.flow; show(); });
  });
  document.querySelectorAll("[data-values]").forEach(function (b) {
    b.addEventListener("click", function () { state.values = b.dataset.values; show(); });
  });

  if (outEl) outEl.textContent = (window.EVIA_OUTPUT || "").trim();
  show();
})();

// Light / Dark buttons: remember the choice; until one is chosen the page follows the system setting.
(function () {
  var buttons = document.querySelectorAll("[data-theme-choice]");
  if (!buttons.length) return;
  var media = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;
  function current() {
    var t = document.documentElement.dataset.theme;
    return t || (media && media.matches ? "dark" : "light");
  }
  function sync() {
    var t = current();
    buttons.forEach(function (b) { b.setAttribute("aria-pressed", b.dataset.themeChoice === t ? "true" : "false"); });
  }
  buttons.forEach(function (b) {
    b.addEventListener("click", function () {
      var t = b.dataset.themeChoice;
      document.documentElement.dataset.theme = t;
      try { localStorage.setItem("evia-theme", t); } catch (e) {}
      sync();
    });
  });
  if (media && media.addEventListener) media.addEventListener("change", sync);
  sync();
})();
