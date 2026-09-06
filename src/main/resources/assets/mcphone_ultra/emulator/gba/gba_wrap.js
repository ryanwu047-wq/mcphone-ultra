// ===== gbajs2 宿主环境桩 + 主循环桥接（在 gba_core.js 之后求值）=====
var __b64map = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
function __b64decode(s) {
  var out = "";
  var buf = 0, bits = 0;
  for (var i = 0; i < s.length; i++) {
    var c = s.charAt(i);
    if (c === "=" || c === "\n" || c === "\r") continue;
    var v = __b64map.indexOf(c);
    if (v < 0) continue;
    buf = (buf << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out += String.fromCharCode((buf >> bits) & 0xff);
    }
  }
  return out;
}

function __b64ToAB(b64) {
  var s = __b64decode(b64);
  var u = new Uint8Array(s.length);
  for (var i = 0; i < s.length; i++) u[i] = s.charCodeAt(i) & 0xff;
  return u.buffer;
}

var __pending = [];
var window = {
  setTimeout: function (f, ms) { __pending.push(f); return __pending.length; },
  clearTimeout: function (id) { if (id && id > 0) __pending[id - 1] = null; },
  addEventListener: function (t, f, c) {},
  removeEventListener: function (t, f, c) {},
  queueFrame: function (f) { __pending.push(f); },
  URL: null,
  localStorage: {},
  AudioContext: undefined,
  webkitAudioContext: undefined,
};
var navigator = {};
var document = {
  createElement: function (t) {
    return { getContext: function () { return null; }, width: 0, height: 0 };
  },
};
queueFrame = function (f) { __pending.push(f); };

var __gbaScreen = null;
var __gbaCtx = {
  createImageData: function (w, h) {
    __gbaScreen = { data: new Uint8Array(w * h * 4), width: w, height: h };
    return __gbaScreen;
  },
  putImageData: function (img, x, y) { __gbaScreen = img; },
};
var __gbaCanvas = { getContext: function (t) { return __gbaCtx; } };

var gba = null;

function __gbaInit() {
  gba = new GameBoyAdvance();
  gba.setCanvas(__gbaCanvas);
  gba.setLogger(function (level, message) {});
  gba.ERROR = function (msg) {};
  gba.WARN = function (msg) {};
  gba.logStackTrace = function (lines) {};
}

function __gbaLoad(romB64, biosB64) {
  __gbaInit();
  if (biosB64 && biosB64.length > 0) {
    gba.setBios(__b64ToAB(biosB64), true);
  }
  gba.setRom(__b64ToAB(romB64));
  gba.runStable();
}

function __gbaStep() {
  var a = __pending;
  __pending = [];
  for (var i = 0; i < a.length; i++) {
    if (a[i]) {
      try { a[i](); } catch (e) {}
    }
  }
}

function __gbaKey(code, down) {
  if (!gba) return;
  gba.keypad.keyboardHandler({ type: down ? "keydown" : "keyup", keyCode: code });
}

function __gbaBuffer() {
  return __gbaScreen ? __gbaScreen.data : null;
}
