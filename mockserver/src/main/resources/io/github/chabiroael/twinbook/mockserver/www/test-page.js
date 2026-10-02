// twinBook test page script. Plays the part of a site's own JavaScript application:
// requests the mock GraphQL endpoint through XMLHttpRequest (progress events) and fetch
// (stream reader), records when each line arrives, verifies that nothing in its realm was
// touched, and posts everything to /report. Defines no globals.
//
// Query parameters:
//   run=<id>              report key (required)
//   scenario=<name>       mock scenario for /api/graphql/
//   transports=xhr,fetch  which transports to use, in order (default: none)
//   integrity=1           run the integrity checks
//   timers=<ms>           run a 100 ms interval for <ms> and count ticks
//   summary=1             report digests and counts instead of the full text
(function () {
  "use strict";

  // References taken before anything else runs; compared again at the end.
  var startRefs = captureNatives(window);
  var startElements = elementSignature();
  var params = new URLSearchParams(location.search);
  var run = params.get("run") || "";
  var scenario = params.get("scenario") || "";
  var transports = (params.get("transports") || "").split(",").filter(Boolean);
  var summary = params.get("summary") === "1";
  var reqCounter = 0;

  function setStatus(text) {
    document.getElementById("status").textContent = text;
  }

  function formBody(transport) {
    reqCounter++;
    var fields = [
      ["av", "0"],
      ["__user", "0"],
      ["__a", "1"],
      ["__req", reqCounter.toString(36)],
      ["__rev", "1000001"],
      ["fb_dtsg", "MOCK_DTSG:" + run],
      ["jazoest", "21000"],
      ["lsd", "MOCK_LSD_" + transport],
      ["fb_api_caller_class", "RelayModern"],
      ["fb_api_req_friendly_name", "MockFeedQuery"],
      ["variables", JSON.stringify({ count: 3, cursor: null, scenario: scenario, text: "é😀" })],
      ["server_timestamps", "true"],
      ["doc_id", "1234567890123456"],
    ];
    return fields.map(function (f) { return encodeURIComponent(f[0]) + "=" + encodeURIComponent(f[1]); }).join("&");
  }

  function graphqlUrl(transport) {
    return "/api/graphql/?scenario=" + encodeURIComponent(scenario) + "&run=" + encodeURIComponent(run) +
      "&transport=" + transport;
  }

  /** Splits arriving text into lines and timestamps each complete line. */
  function LineCollector() {
    this.buffer = "";
    this.lines = [];
    this.times = [];
  }
  LineCollector.prototype.add = function (text, final) {
    this.buffer += text;
    var now = Date.now();
    var i;
    while ((i = this.buffer.indexOf("\n")) >= 0) {
      this.lines.push(this.buffer.slice(0, i));
      this.times.push(now);
      this.buffer = this.buffer.slice(i + 1);
    }
    if (final && this.buffer.length > 0) {
      this.lines.push(this.buffer);
      this.times.push(now);
      this.buffer = "";
    }
  };

  function viaXhr() {
    return new Promise(function (resolve) {
      var xhr = new XMLHttpRequest();
      var collector = new LineCollector();
      var seen = 0;
      var progressEvents = 0;
      var firstByteAt = 0;
      var startedAt = Date.now();
      function take(final) {
        var text = xhr.responseText;
        if (text.length > seen && firstByteAt === 0) firstByteAt = Date.now();
        collector.add(text.slice(seen), final);
        seen = text.length;
      }
      xhr.onprogress = function () { progressEvents++; take(false); };
      xhr.onload = function () {
        take(true);
        resolve(finish(xhr.status, null, collector, xhr.responseText, startedAt, firstByteAt, progressEvents));
      };
      xhr.onerror = function () { resolve(finish(0, "xhr error", collector, "", startedAt, firstByteAt, progressEvents)); };
      xhr.open("POST", graphqlUrl("xhr"));
      xhr.setRequestHeader("Content-Type", "application/x-www-form-urlencoded");
      xhr.send(formBody("xhr"));
    });
  }

  function viaFetch() {
    var startedAt = Date.now();
    var collector = new LineCollector();
    var decoder = new TextDecoder();
    var full = "";
    var reads = 0;
    var firstByteAt = 0;
    return fetch(graphqlUrl("fetch"), {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: formBody("fetch"),
    }).then(function (response) {
      var reader = response.body.getReader();
      function pump() {
        return reader.read().then(function (r) {
          if (r.done) {
            var tail = decoder.decode();
            full += tail;
            collector.add(tail, true);
            return finish(response.status, null, collector, full, startedAt, firstByteAt, reads);
          }
          reads++;
          if (firstByteAt === 0) firstByteAt = Date.now();
          var text = decoder.decode(r.value, { stream: true });
          full += text;
          collector.add(text, false);
          return pump();
        });
      }
      return pump();
    }).catch(function (e) {
      return finish(0, String(e), collector, full, startedAt, firstByteAt, reads);
    });
  }

  function finish(status, error, collector, text, startedAt, firstByteAt, events) {
    return sha256(text).then(function (digest) {
      var r = {
        ok: error === null,
        status: status,
        error: error,
        startedAt: startedAt,
        firstByteAt: firstByteAt,
        finishedAt: Date.now(),
        events: events,
        lineCount: collector.lines.length,
        length: text.length,
        sha256: digest,
        firstLineAt: collector.times.length > 0 ? collector.times[0] : 0,
        lastLineAt: collector.times.length > 0 ? collector.times[collector.times.length - 1] : 0,
      };
      if (!summary) {
        r.text = text;
        r.lineTimes = collector.times;
      } else {
        r.firstLine = collector.lines.length > 0 ? collector.lines[0].slice(0, 300) : "";
        r.lastLine = collector.lines.length > 0 ? collector.lines[collector.lines.length - 1].slice(0, 300) : "";
      }
      return r;
    });
  }

  function sha256(text) {
    return crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)).then(function (buf) {
      return Array.prototype.map.call(new Uint8Array(buf), function (b) { return (b + 256).toString(16).slice(1); }).join("");
    });
  }

  // ---- Integrity checks ------------------------------------------------------------------

  // [name, holder(window), property key, getter?] for every function the page relies on.
  var TARGETS = [
    ["XMLHttpRequest", function (w) { return w; }, "XMLHttpRequest", false],
    ["XMLHttpRequest.prototype.open", function (w) { return w.XMLHttpRequest.prototype; }, "open", false],
    ["XMLHttpRequest.prototype.send", function (w) { return w.XMLHttpRequest.prototype; }, "send", false],
    ["XMLHttpRequest.prototype.setRequestHeader", function (w) { return w.XMLHttpRequest.prototype; }, "setRequestHeader", false],
    ["XMLHttpRequest.prototype.responseText (getter)", function (w) { return w.XMLHttpRequest.prototype; }, "responseText", true],
    ["fetch", function (w) { return w; }, "fetch", false],
    ["JSON.parse", function (w) { return w.JSON; }, "parse", false],
    ["JSON.stringify", function (w) { return w.JSON; }, "stringify", false],
    ["Response.prototype.json", function (w) { return w.Response.prototype; }, "json", false],
    ["Response.prototype.text", function (w) { return w.Response.prototype; }, "text", false],
    ["ReadableStreamDefaultReader.prototype.read", function (w) { return w.ReadableStreamDefaultReader.prototype; }, "read", false],
    ["TextDecoder.prototype.decode", function (w) { return w.TextDecoder.prototype; }, "decode", false],
    ["EventTarget.prototype.addEventListener", function (w) { return w.EventTarget.prototype; }, "addEventListener", false],
    ["Function.prototype.toString", function (w) { return w.Function.prototype; }, "toString", false],
  ];

  var NATIVE_SOURCE = /^function [\w$ ]*\(\) \{\s*\[native code\]\s*\}$/;

  function lookup(w, t) {
    var desc = Object.getOwnPropertyDescriptor(t[1](w), t[2]);
    if (!desc) return { desc: null, fn: undefined };
    return { desc: desc, fn: t[3] ? desc.get : desc.value };
  }

  function captureNatives(w) {
    var refs = {};
    TARGETS.forEach(function (t) { refs[t[0]] = lookup(w, t).fn; });
    return refs;
  }

  function elementSignature() {
    return Array.prototype.map.call(document.querySelectorAll("*"), function (e) {
      return e.tagName + (e.id ? "#" + e.id : "") + (e.getAttribute("src") ? "[src=" + e.getAttribute("src") + "]" : "");
    });
  }

  function descShape(desc) {
    if (!desc) return "missing";
    return [
      "w:" + desc.writable, "e:" + desc.enumerable, "c:" + desc.configurable,
      "get:" + typeof desc.get, "set:" + typeof desc.set, "value:" + typeof desc.value,
    ].join(",");
  }

  function loadIframe() {
    return new Promise(function (resolve, reject) {
      var frame = document.createElement("iframe");
      frame.style.display = "none";
      frame.onload = function () { resolve(frame); };
      frame.onerror = reject;
      frame.src = "/blank.html";
      document.body.appendChild(frame);
    });
  }

  function integrity() {
    var checks = [];
    function check(name, ok, detail) {
      checks.push({ name: name, ok: !!ok, detail: detail === undefined ? "" : String(detail) });
    }
    var toStr = Function.prototype.toString;
    return loadIframe().then(function (frame) {
      var other = frame.contentWindow;
      var otherToStr = other.Function.prototype.toString;
      TARGETS.forEach(function (t) {
        var mine = lookup(window, t);
        var theirs = lookup(other, t);
        var src = mine.fn === undefined ? "missing" : toStr.call(mine.fn);
        check("native: " + t[0], NATIVE_SOURCE.test(src), src);
        var cross = mine.fn === undefined ? "missing" : otherToStr.call(mine.fn);
        check("native via iframe realm: " + t[0], NATIVE_SOURCE.test(cross), cross);
        check("iframe copy native: " + t[0], theirs.fn !== undefined && NATIVE_SOURCE.test(otherToStr.call(theirs.fn)));
        check("unchanged since start: " + t[0], startRefs[t[0]] === mine.fn);
        check("descriptor matches iframe: " + t[0], descShape(mine.desc) === descShape(theirs.desc),
          descShape(mine.desc) + " vs " + descShape(theirs.desc));
        check("name and length match iframe: " + t[0],
          mine.fn !== undefined && theirs.fn !== undefined && mine.fn.name === theirs.fn.name && mine.fn.length === theirs.fn.length,
          (mine.fn && mine.fn.name) + "/" + (mine.fn && mine.fn.length));
      });

      check("Function.prototype.toString.toString === Function.prototype.toString",
        Function.prototype.toString.toString === Function.prototype.toString);
      check("toString of toString is native", NATIVE_SOURCE.test(toStr.call(toStr)), toStr.call(toStr));
      check("toString own property identity",
        Object.getOwnPropertyDescriptor(Function.prototype, "toString").value === Function.prototype.toString);
      check("JSON.parse behaves like the iframe's",
        JSON.stringify(JSON.parse('{"a":[1,"é"]}')) === other.JSON.stringify(other.JSON.parse('{"a":[1,"é"]}')));
      check("XMLHttpRequest prototype chain matches iframe",
        Object.getPrototypeOf(XMLHttpRequest.prototype).constructor.name ===
          Object.getPrototypeOf(other.XMLHttpRequest.prototype).constructor.name);

      var mineNames = Object.getOwnPropertyNames(window);
      var theirNames = Object.getOwnPropertyNames(other);
      var theirSet = new Set(theirNames);
      var mineSet = new Set(mineNames);
      var extra = mineNames.filter(function (n) { return !theirSet.has(n) && !/^\d+$/.test(n); });
      var missing = theirNames.filter(function (n) { return !mineSet.has(n) && !/^\d+$/.test(n); });
      check("no unexpected globals", extra.length === 0, extra.join(","));
      check("no missing globals", missing.length === 0, missing.join(","));

      frame.remove();
      var nowElements = elementSignature();
      check("no injected elements", JSON.stringify(nowElements) === JSON.stringify(startElements),
        nowElements.join(" "));
      check("only the page's own script element", document.scripts.length === 1 &&
        document.scripts[0].getAttribute("src") === "/static/test-page.js", document.scripts.length);
      var html = document.documentElement.outerHTML;
      check("no extension URL in the DOM", html.indexOf("moz-extension:") < 0 && html.indexOf("chrome-extension:") < 0);
      var foreign = performance.getEntriesByType("resource").map(function (e) { return e.name; })
        .filter(function (n) { return n.indexOf(location.origin + "/") !== 0; });
      check("no foreign resources loaded", foreign.length === 0, foreign.join(","));
      var allOk = checks.every(function (c) { return c.ok; });
      return { ok: allOk, count: checks.length, failed: checks.filter(function (c) { return !c.ok; }), checks: checks };
    });
  }

  function timers(ms) {
    return new Promise(function (resolve) {
      var ticks = 0;
      var last = performance.now();
      var maxGap = 0;
      var started = performance.now();
      var handle = setInterval(function () {
        var now = performance.now();
        maxGap = Math.max(maxGap, now - last);
        last = now;
        ticks++;
      }, 100);
      setTimeout(function () {
        clearInterval(handle);
        resolve({ requestedMs: ms, intervalMs: 100, ticks: ticks, maxGapMs: Math.round(maxGap), elapsedMs: Math.round(performance.now() - started) });
      }, ms);
    });
  }

  function report(body) {
    return new Promise(function (resolve) {
      var xhr = new XMLHttpRequest();
      xhr.onloadend = resolve;
      xhr.open("POST", "/report?run=" + encodeURIComponent(run));
      xhr.setRequestHeader("Content-Type", "application/json");
      xhr.send(JSON.stringify(body));
    });
  }

  var result = { run: run, kind: "test-page", scenario: scenario, userAgent: navigator.userAgent, startedAt: Date.now(), results: {} };
  var chain = Promise.resolve();
  transports.forEach(function (t) {
    chain = chain.then(function () {
      setStatus("requesting via " + t);
      return (t === "xhr" ? viaXhr() : viaFetch()).then(function (r) { result.results[t] = r; });
    });
  });
  var timerMs = Number(params.get("timers") || 0);
  if (timerMs > 0) chain = chain.then(function () { return timers(timerMs).then(function (r) { result.timers = r; }); });
  if (params.get("integrity") === "1") chain = chain.then(function () { return integrity().then(function (r) { result.integrity = r; }); });
  chain.catch(function (e) {
    result.error = String(e && e.stack || e);
  }).then(function () {
    result.finishedAt = Date.now();
    setStatus("done");
    return report(result);
  }).then(function () {
    setStatus("reported");
  });
})();
