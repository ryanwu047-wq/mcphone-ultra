// ===== jsnes 桥接（在 nes.js 之后求值）=====
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

var __frame = null;
var __nes = null;

function __nesLoad(romB64) {
  __nes = new jsnes.NES({
    onFrame: function (buf) {
      var n = buf.length;
      var out = new Uint8Array(n * 4);
      var j = 0;
      for (var i = 0; i < n; i++) {
        var p = buf[i] | 0;
        out[j++] = p & 0xff;
        out[j++] = (p >>> 8) & 0xff;
        out[j++] = (p >>> 16) & 0xff;
        out[j++] = 0xff;
      }
      __frame = out;
    },
    onAudioSample: function (l, r) {},
  });
  __nes.loadROM(__b64decode(romB64));
}

function __nesFrame() {
  __nes.frame();
}

function __nesButton(controller, button, down) {
  if (down) __nes.buttonDown(controller, button);
  else __nes.buttonUp(controller, button);
}

function __nesBuffer() {
  return __frame;
}
