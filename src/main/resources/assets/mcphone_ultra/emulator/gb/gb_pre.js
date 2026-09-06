// ===== jsGB 宿主环境桩（必须在 gb_core.js 之前求值）=====
var __gbRom = "";
var __gbScreen = null;
var __gbCtx = {
  createImageData: function (w, h) {
    __gbScreen = { data: new Uint8Array(w * h * 4), width: w, height: h };
    return __gbScreen;
  },
  putImageData: function (img, x, y) {
    __gbScreen = img;
  },
};
function __FakeEl() {
  this.value = "";
  this.innerHTML = "";
  this.style = {};
  this.rel = "";
  this.className = "";
  this.id = "";
  this.onclick = null;
  this.onupdate = null;
  this.getContext = function (t) { return __gbCtx; };
}
var document = {
  getElementById: function (id) { return new __FakeEl(); },
  createElement: function (tag) { return new __FakeEl(); },
  write: function (s) {},
  getElementsByTagName: function (t) { return []; },
};
var window = { onload: null, onkeydown: null, onkeyup: null };
var setInterval = function (f, ms) { return 0; };
var clearInterval = function (id) {};
var XMLHttpRequest = function () {
  this.status = 200;
  this.responseText = "";
  this.open = function (m, u, a) {};
  this.overrideMimeType = function (t) {};
  this.send = function (d) { this.responseText = __gbRom; };
};
var navigator = {};
