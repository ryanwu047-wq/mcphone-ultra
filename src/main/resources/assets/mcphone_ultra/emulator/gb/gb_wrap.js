// ===== jsGB 主循环与桥接（在 gb_core.js 之后求值）=====
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

jsGB = {
  frame: function () {
    var fclock = Z80._clock.m + 17556;
    do {
      if (Z80._halt) {
        Z80._r.m = 1;
      } else {
        Z80._map[MMU.rb(Z80._r.pc++)]();
        Z80._r.pc &= 65535;
      }
      if (Z80._r.ime && MMU._ie && MMU._if) {
        Z80._halt = 0;
        Z80._r.ime = 0;
        var ifired = MMU._ie & MMU._if;
        if (ifired & 1) { MMU._if &= 0xFE; Z80._ops.RST40(); }
        else if (ifired & 2) { MMU._if &= 0xFD; Z80._ops.RST48(); }
        else if (ifired & 4) { MMU._if &= 0xFB; Z80._ops.RST50(); }
        else if (ifired & 8) { MMU._if &= 0xF7; Z80._ops.RST58(); }
        else if (ifired & 16) { MMU._if &= 0xEF; Z80._ops.RST60(); }
        else { Z80._r.ime = 1; }
      }
      Z80._clock.m += Z80._r.m;
      GPU.checkline();
      TIMER.inc();
      if (Z80._stop) {
        jsGB.pause();
        break;
      }
    } while (Z80._clock.m < fclock);
  },

  reset: function () {
    LOG.reset(); GPU.reset(); MMU.reset(); Z80.reset(); KEY.reset(); TIMER.reset();
    Z80._r.pc = 0x100;
    MMU._inbios = 0;
    Z80._r.sp = 0xFFFE;
    Z80._r.hl = 0x014D;
    Z80._r.c = 0x13;
    Z80._r.e = 0xD8;
    Z80._r.a = 1;
    MMU.load("");
    jsGB.pause();
  },

  pause: function () {
    Z80._stop = 1;
  },

  run: function () {
    Z80._stop = 0;
  },
};

function __gbLoad(romB64) {
  __gbRom = __b64decode(romB64);
  jsGB.reset();
}

function __gbFrame() {
  if (Z80._stop) jsGB.run();
  jsGB.frame();
}

function __gbKey(code, down) {
  var e = { keyCode: code };
  if (down) KEY.keydown(e); else KEY.keyup(e);
}

function __gbBuffer() {
  return GPU._scrn ? GPU._scrn.data : null;
}
