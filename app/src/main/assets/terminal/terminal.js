// Glue between xterm.js and XtermWebViewRenderer. Output arrives as Base64 and is written to
// xterm as raw bytes, so xterm itself decodes UTF-8 across writes.
(function () {
  'use strict';
  var bridge = window.TroupeBridge;
  var theme = JSON.parse(bridge.theme());
  document.body.style.background = theme.background;

  var term = new Terminal({
    fontFamily: '"Paper Mono", monospace',
    fontSize: 14,
    lineHeight: 1.2,
    cursorBlink: true,
    cursorStyle: 'block',
    scrollback: 5000,
    theme: theme
  });
  var fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  term.onData(function (data) { bridge.onData(data); });
  term.onResize(function (size) { bridge.onResize(size.cols, size.rows); });

  function bytes(base64) {
    var binary = atob(base64);
    var out = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
    return out;
  }

  function start() {
    var container = document.getElementById('terminal');
    term.open(container);
    fit.fit();
    bridge.onResize(term.cols, term.rows);
    new ResizeObserver(function () { fit.fit(); }).observe(container);
    window.troupe = {
      term: term,
      write: function (base64) { term.write(bytes(base64)); },
      reset: function () { term.reset(); },
      focus: function () { term.focus(); }
    };
    bridge.onReady();
  }

  // Measure cells with the real font, not a fallback.
  document.fonts.load('14px "Paper Mono"').then(start, start);
})();
