function hex(number, leading, usePrefix) {
	if (typeof usePrefix === "undefined") {
		usePrefix = true;
	}
	if (typeof leading === "undefined") {
		leading = 8;
	}
	var string = (number >>> 0).toString(16).toUpperCase();
	leading -= string.length;
	if (leading < 0) return string;
	return (usePrefix ? "0x" : "") + new Array(leading + 1).join("0") + string;
}

class Pointer {
	constructor() {
		this.index = 0;
		this.top = 0;
		this.stack = [];
	}
	advance(amount) {
		var index = this.index;
		this.index += amount;
		return index;
	}
	mark() {
		return this.index - this.top;
	}
	push() {
		this.stack.push(this.top);
		this.top = this.index;
	}
	pop() {
		this.top = this.stack.pop();
	}
	readString(view) {
		var length = view.getUint32(this.advance(4), true);
		var bytes = [];
		for (var i = 0; i < length; ++i) {
			bytes.push(String.fromCharCode(view.getUint8(this.advance(1))));
		}
		return bytes.join("");
	}
}

class Serializer {
	TAG_INT = 1;
	TAG_STRING = 2;
	TAG_STRUCT = 3;
	TAG_BLOB = 4;
	TAG_BOOLEAN = 5;
	TYPE = "application/octet-stream";

	pack(value) {
		var object = new DataView(new ArrayBuffer(4));
		object.setUint32(0, value, true);
		return object.buffer;
	}

	pack8(value) {
		var object = new DataView(new ArrayBuffer(1));
		object.setUint8(0, value, true);
		return object.buffer;
	}

	prefix(value) {
		return new Blob(
			[
				Serializer.pack(value.size || value.length || value.byteLength),
				value
			],
			{ type: Serializer.TYPE }
		);
	}

	serialize(stream) {
		var parts = [];
		var size = 4;
		for (i in stream) {
			if (stream.hasOwnProperty(i)) {
				var tag;
				var head = Serializer.prefix(i);
				var body;
				switch (typeof stream[i]) {
					case "number":
						tag = Serializer.TAG_INT;
						body = Serializer.pack(stream[i]);
						break;
					case "string":
						tag = Serializer.TAG_STRING;
						body = Serializer.prefix(stream[i]);
						break;
					case "object":
						if (stream[i].type == Serializer.TYPE) {
							tag = Serializer.TAG_BLOB;
							body = stream[i];
						} else {
							tag = Serializer.TAG_STRUCT;
							body = Serializer.serialize(stream[i]);
						}
						break;
					case "boolean":
						tag = Serializer.TAG_BOOLEAN;
						body = Serializer.pack8(stream[i]);
						break;
					default:
						console.log(stream[i]);
						break;
				}
				size +=
					1 +
					head.size +
					(body.size || body.byteLength || body.length);
				parts.push(Serializer.pack8(tag));
				parts.push(head);
				parts.push(body);
			}
		}
		parts.unshift(Serializer.pack(size));
		return new Blob(parts);
	}

	deserialize(blob, callback) {
		var reader = new FileReader();
		reader.onload = function (data) {
			callback(
				Serializer.deserealizeStream(
					new DataView(data.target.result),
					new Pointer()
				)
			);
		};
		reader.readAsArrayBuffer(blob);
	}

	deserealizeStream(view, pointer) {
		pointer.push();
		var object = {};
		var remaining = view.getUint32(pointer.advance(4), true);
		while (pointer.mark() < remaining) {
			var tag = view.getUint8(pointer.advance(1));
			var head = pointer.readString(view);
			var body;
			switch (tag) {
				case Serializer.TAG_INT:
					body = view.getUint32(pointer.advance(4), true);
					break;
				case Serializer.TAG_STRING:
					body = pointer.readString(view);
					break;
				case Serializer.TAG_STRUCT:
					body = Serializer.deserealizeStream(view, pointer);
					break;
				case Serializer.TAG_BLOB:
					var size = view.getUint32(pointer.advance(4), true);
					body = view.buffer.slice(
						pointer.advance(size),
						pointer.advance(0)
					);
					break;
				case Serializer.TAG_BOOLEAN:
					body = !!view.getUint8(pointer.advance(1));
					break;
			}
			object[head] = body;
		}
		if (pointer.mark() > remaining) {
			throw "Size of serialized data exceeded";
		}
		pointer.pop();
		return object;
	}

	serializePNG(blob, base, callback) {
		var canvas = document.createElement("canvas");
		var context = canvas.getContext("2d");
		var pixels = base
			.getContext("2d")
			.getImageData(0, 0, base.width, base.height);
		var transparent = 0;
		for (var y = 0; y < base.height; ++y) {
			for (var x = 0; x < base.width; ++x) {
				if (!pixels.data[(x + y * base.width) * 4 + 3]) {
					++transparent;
				}
			}
		}
		var bytesInCanvas =
			transparent * 3 + (base.width * base.height - transparent);
		for (
			var multiplier = 1;
			bytesInCanvas * multiplier * multiplier < blob.size;
			++multiplier
		);
		var edges = bytesInCanvas * multiplier * multiplier - blob.size;
		var padding = Math.ceil(edges / (base.width * multiplier));
		canvas.setAttribute("width", base.width * multiplier);
		canvas.setAttribute("height", base.height * multiplier + padding);

		var reader = new FileReader();
		reader.onload = function (data) {
			var view = new Uint8Array(data.target.result);
			var pointer = 0;
			var pixelPointer = 0;
			var newPixels = context.createImageData(
				canvas.width,
				canvas.height + padding
			);
			for (var y = 0; y < canvas.height; ++y) {
				for (var x = 0; x < canvas.width; ++x) {
					var oldY = (y / multiplier) | 0;
					var oldX = (x / multiplier) | 0;
					if (
						oldY > base.height ||
						!pixels.data[(oldX + oldY * base.width) * 4 + 3]
					) {
						newPixels.data[pixelPointer++] = view[pointer++];
						newPixels.data[pixelPointer++] = view[pointer++];
						newPixels.data[pixelPointer++] = view[pointer++];
						newPixels.data[pixelPointer++] = 0;
					} else {
						var byte = view[pointer++];
						newPixels.data[pixelPointer++] =
							pixels.data[(oldX + oldY * base.width) * 4 + 0] |
							(byte & 7);
						newPixels.data[pixelPointer++] =
							pixels.data[(oldX + oldY * base.width) * 4 + 1] |
							((byte >> 3) & 7);
						newPixels.data[pixelPointer++] =
							pixels.data[(oldX + oldY * base.width) * 4 + 2] |
							((byte >> 6) & 7);
						newPixels.data[pixelPointer++] =
							pixels.data[(oldX + oldY * base.width) * 4 + 3];
					}
				}
			}
			context.putImageData(newPixels, 0, 0);
			callback(canvas.toDataURL("image/png"));
		};
		reader.readAsArrayBuffer(blob);
		return canvas;
	}

	deserializePNG(blob, callback) {
		var reader = new FileReader();
		reader.onload = function (data) {
			var image = document.createElement("img");
			image.setAttribute("src", data.target.result);
			var canvas = document.createElement("canvas");
			canvas.setAttribute("height", image.height);
			canvas.setAttribute("width", image.width);
			var context = canvas.getContext("2d");
			context.drawImage(image, 0, 0);
			var pixels = context.getImageData(
				0,
				0,
				canvas.width,
				canvas.height
			);
			var data = [];
			for (var y = 0; y < canvas.height; ++y) {
				for (var x = 0; x < canvas.width; ++x) {
					if (!pixels.data[(x + y * canvas.width) * 4 + 3]) {
						data.push(pixels.data[(x + y * canvas.width) * 4 + 0]);
						data.push(pixels.data[(x + y * canvas.width) * 4 + 1]);
						data.push(pixels.data[(x + y * canvas.width) * 4 + 2]);
					} else {
						var byte = 0;
						byte |= pixels.data[(x + y * canvas.width) * 4 + 0] & 7;
						byte |=
							(pixels.data[(x + y * canvas.width) * 4 + 1] & 7) <<
							3;
						byte |=
							(pixels.data[(x + y * canvas.width) * 4 + 2] & 7) <<
							6;
						data.push(byte);
					}
				}
			}
			newBlob = new Blob(
				data.map(function (byte) {
					var array = new Uint8Array(1);
					array[0] = byte;
					return array;
				}),
				{ type: Serializer.TYPE }
			);
			Serializer.deserialize(newBlob, callback);
		};
		reader.readAsDataURL(blob);
	}
}

class ARMCore {
	constructor() {
		// this.inherit();
		this.SP = 13;
		this.LR = 14;
		this.PC = 15;

		this.MODE_ARM = 0;
		this.MODE_THUMB = 1;

		this.MODE_USER = 0x10;
		this.MODE_FIQ = 0x11;
		this.MODE_IRQ = 0x12;
		this.MODE_SUPERVISOR = 0x13;
		this.MODE_ABORT = 0x17;
		this.MODE_UNDEFINED = 0x1b;
		this.MODE_SYSTEM = 0x1f;

		this.BANK_NONE = 0;
		this.BANK_FIQ = 1;
		this.BANK_IRQ = 2;
		this.BANK_SUPERVISOR = 3;
		this.BANK_ABORT = 4;
		this.BANK_UNDEFINED = 5;

		this.UNALLOC_MASK = 0x0fffff00;
		this.USER_MASK = 0xf0000000;
		this.PRIV_MASK = 0x000000cf; // This is out of spec, but it seems to be what's done in other implementations
		this.STATE_MASK = 0x00000020;

		this.WORD_SIZE_ARM = 4;
		this.WORD_SIZE_THUMB = 2;

		this.BASE_RESET = 0x00000000;
		this.BASE_UNDEF = 0x00000004;
		this.BASE_SWI = 0x00000008;
		this.BASE_PABT = 0x0000000c;
		this.BASE_DABT = 0x00000010;
		this.BASE_IRQ = 0x00000018;
		this.BASE_FIQ = 0x0000001c;

		this.armCompiler = new ARMCoreArm(this);
		this.thumbCompiler = new ARMCoreThumb(this);
		this.generateConds();

		this.gprs = new Int32Array(16);
	}
	resetCPU(startOffset) {
		for (var i = 0; i < this.PC; ++i) {
			this.gprs[i] = 0;
		}
		this.gprs[this.PC] = startOffset + this.WORD_SIZE_ARM;

		this.loadInstruction = this.loadInstructionArm;
		this.execMode = this.MODE_ARM;
		this.instructionWidth = this.WORD_SIZE_ARM;

		this.mode = this.MODE_SYSTEM;

		this.cpsrI = false;
		this.cpsrF = false;

		this.cpsrV = false;
		this.cpsrC = false;
		this.cpsrZ = false;
		this.cpsrN = false;

		this.bankedRegisters = [
			new Int32Array(7),
			new Int32Array(7),
			new Int32Array(2),
			new Int32Array(2),
			new Int32Array(2),
			new Int32Array(2)
		];
		this.spsr = 0;
		this.bankedSPSRs = new Int32Array(6);

		this.cycles = 0;

		this.shifterOperand = 0;
		this.shifterCarryOut = 0;

		this.page = null;
		this.pageId = 0;
		this.pageRegion = -1;

		this.instruction = null;

		this.irq.clear();

		var gprs = this.gprs;
		var mmu = this.mmu;
		this.step = function () {
			var instruction =
				this.instruction ||
				(this.instruction = this.loadInstruction(
					gprs[this.PC] - this.instructionWidth
				));
			gprs[this.PC] += this.instructionWidth;
			this.conditionPassed = true;
			instruction();

			if (!instruction.writesPC) {
				if (this.instruction != null) {
					// We might have gotten an interrupt from the instruction
					if (
						instruction.next == null ||
						instruction.next.page.invalid
					) {
						instruction.next = this.loadInstruction(
							gprs[this.PC] - this.instructionWidth
						);
					}
					this.instruction = instruction.next;
				}
			} else {
				if (this.conditionPassed) {
					var pc = (gprs[this.PC] &= 0xfffffffe);
					if (this.execMode == this.MODE_ARM) {
						mmu.wait32(pc);
						mmu.waitPrefetch32(pc);
					} else {
						mmu.wait(pc);
						mmu.waitPrefetch(pc);
					}
					gprs[this.PC] += this.instructionWidth;
					if (!instruction.fixedJump) {
						this.instruction = null;
					} else if (this.instruction != null) {
						if (
							instruction.next == null ||
							instruction.next.page.invalid
						) {
							instruction.next = this.loadInstruction(
								gprs[this.PC] - this.instructionWidth
							);
						}
						this.instruction = instruction.next;
					}
				} else {
					this.instruction = null;
				}
			}
			this.irq.updateTimers();
		};
	}
	freeze() {
		return {
			gprs: [
				this.gprs[0],
				this.gprs[1],
				this.gprs[2],
				this.gprs[3],
				this.gprs[4],
				this.gprs[5],
				this.gprs[6],
				this.gprs[7],
				this.gprs[8],
				this.gprs[9],
				this.gprs[10],
				this.gprs[11],
				this.gprs[12],
				this.gprs[13],
				this.gprs[14],
				this.gprs[15]
			],
			mode: this.mode,
			cpsrI: this.cpsrI,
			cpsrF: this.cpsrF,
			cpsrV: this.cpsrV,
			cpsrC: this.cpsrC,
			cpsrZ: this.cpsrZ,
			cpsrN: this.cpsrN,
			bankedRegisters: [
				[
					this.bankedRegisters[0][0],
					this.bankedRegisters[0][1],
					this.bankedRegisters[0][2],
					this.bankedRegisters[0][3],
					this.bankedRegisters[0][4],
					this.bankedRegisters[0][5],
					this.bankedRegisters[0][6]
				],
				[
					this.bankedRegisters[1][0],
					this.bankedRegisters[1][1],
					this.bankedRegisters[1][2],
					this.bankedRegisters[1][3],
					this.bankedRegisters[1][4],
					this.bankedRegisters[1][5],
					this.bankedRegisters[1][6]
				],
				[this.bankedRegisters[2][0], this.bankedRegisters[2][1]],
				[this.bankedRegisters[3][0], this.bankedRegisters[3][1]],
				[this.bankedRegisters[4][0], this.bankedRegisters[4][1]],
				[this.bankedRegisters[5][0], this.bankedRegisters[5][1]]
			],
			spsr: this.spsr,
			bankedSPSRs: [
				this.bankedSPSRs[0],
				this.bankedSPSRs[1],
				this.bankedSPSRs[2],
				this.bankedSPSRs[3],
				this.bankedSPSRs[4],
				this.bankedSPSRs[5]
			],
			cycles: this.cycles
		};
	}
	defrost(frost) {
		this.instruction = null;

		this.page = null;
		this.pageId = 0;
		this.pageRegion = -1;

		this.gprs[0] = frost.gprs[0];
		this.gprs[1] = frost.gprs[1];
		this.gprs[2] = frost.gprs[2];
		this.gprs[3] = frost.gprs[3];
		this.gprs[4] = frost.gprs[4];
		this.gprs[5] = frost.gprs[5];
		this.gprs[6] = frost.gprs[6];
		this.gprs[7] = frost.gprs[7];
		this.gprs[8] = frost.gprs[8];
		this.gprs[9] = frost.gprs[9];
		this.gprs[10] = frost.gprs[10];
		this.gprs[11] = frost.gprs[11];
		this.gprs[12] = frost.gprs[12];
		this.gprs[13] = frost.gprs[13];
		this.gprs[14] = frost.gprs[14];
		this.gprs[15] = frost.gprs[15];

		this.mode = frost.mode;
		this.cpsrI = frost.cpsrI;
		this.cpsrF = frost.cpsrF;
		this.cpsrV = frost.cpsrV;
		this.cpsrC = frost.cpsrC;
		this.cpsrZ = frost.cpsrZ;
		this.cpsrN = frost.cpsrN;

		this.bankedRegisters[0][0] = frost.bankedRegisters[0][0];
		this.bankedRegisters[0][1] = frost.bankedRegisters[0][1];
		this.bankedRegisters[0][2] = frost.bankedRegisters[0][2];
		this.bankedRegisters[0][3] = frost.bankedRegisters[0][3];
		this.bankedRegisters[0][4] = frost.bankedRegisters[0][4];
		this.bankedRegisters[0][5] = frost.bankedRegisters[0][5];
		this.bankedRegisters[0][6] = frost.bankedRegisters[0][6];

		this.bankedRegisters[1][0] = frost.bankedRegisters[1][0];
		this.bankedRegisters[1][1] = frost.bankedRegisters[1][1];
		this.bankedRegisters[1][2] = frost.bankedRegisters[1][2];
		this.bankedRegisters[1][3] = frost.bankedRegisters[1][3];
		this.bankedRegisters[1][4] = frost.bankedRegisters[1][4];
		this.bankedRegisters[1][5] = frost.bankedRegisters[1][5];
		this.bankedRegisters[1][6] = frost.bankedRegisters[1][6];

		this.bankedRegisters[2][0] = frost.bankedRegisters[2][0];
		this.bankedRegisters[2][1] = frost.bankedRegisters[2][1];

		this.bankedRegisters[3][0] = frost.bankedRegisters[3][0];
		this.bankedRegisters[3][1] = frost.bankedRegisters[3][1];

		this.bankedRegisters[4][0] = frost.bankedRegisters[4][0];
		this.bankedRegisters[4][1] = frost.bankedRegisters[4][1];

		this.bankedRegisters[5][0] = frost.bankedRegisters[5][0];
		this.bankedRegisters[5][1] = frost.bankedRegisters[5][1];

		this.spsr = frost.spsr;
		this.bankedSPSRs[0] = frost.bankedSPSRs[0];
		this.bankedSPSRs[1] = frost.bankedSPSRs[1];
		this.bankedSPSRs[2] = frost.bankedSPSRs[2];
		this.bankedSPSRs[3] = frost.bankedSPSRs[3];
		this.bankedSPSRs[4] = frost.bankedSPSRs[4];
		this.bankedSPSRs[5] = frost.bankedSPSRs[5];

		this.cycles = frost.cycles;
	}
	fetchPage(address) {
		var region = address >> this.mmu.BASE_OFFSET;
		var pageId = this.mmu.addressToPage(
			region,
			address & this.mmu.OFFSET_MASK
		);
		if (region == this.pageRegion) {
			if (pageId == this.pageId && !this.page.invalid) {
				return;
			}
			this.pageId = pageId;
		} else {
			this.pageMask = this.mmu.memory[region].PAGE_MASK;
			this.pageRegion = region;
			this.pageId = pageId;
		}

		this.page = this.mmu.accessPage(region, pageId);
	}
	loadInstructionArm(address) {
		var next = null;
		this.fetchPage(address);
		var offset = (address & this.pageMask) >> 2;
		next = this.page.arm[offset];
		if (next) {
			return next;
		}
		var instruction = this.mmu.load32(address) >>> 0;
		next = this.compileArm(instruction);
		next.next = null;
		next.page = this.page;
		next.address = address;
		next.opcode = instruction;
		this.page.arm[offset] = next;
		return next;
	}
	loadInstructionThumb(address) {
		var next = null;
		this.fetchPage(address);
		var offset = (address & this.pageMask) >> 1;
		next = this.page.thumb[offset];
		if (next) {
			return next;
		}
		var instruction = this.mmu.load16(address);
		next = this.compileThumb(instruction);
		next.next = null;
		next.page = this.page;
		next.address = address;
		next.opcode = instruction;
		this.page.thumb[offset] = next;
		return next;
	}
	selectBank(mode) {
		switch (mode) {
			case this.MODE_USER:
			case this.MODE_SYSTEM:
				// No banked registers
				return this.BANK_NONE;
			case this.MODE_FIQ:
				return this.BANK_FIQ;
			case this.MODE_IRQ:
				return this.BANK_IRQ;
			case this.MODE_SUPERVISOR:
				return this.BANK_SUPERVISOR;
			case this.MODE_ABORT:
				return this.BANK_ABORT;
			case this.MODE_UNDEFINED:
				return this.BANK_UNDEFINED;
			default:
				throw "Invalid user mode passed to selectBank";
		}
	}
	switchExecMode(newMode) {
		if (this.execMode != newMode) {
			this.execMode = newMode;
			if (newMode == this.MODE_ARM) {
				this.instructionWidth = this.WORD_SIZE_ARM;
				this.loadInstruction = this.loadInstructionArm;
			} else {
				this.instructionWidth = this.WORD_SIZE_THUMB;
				this.loadInstruction = this.loadInstructionThumb;
			}
		}
	}
	switchMode(newMode) {
		if (newMode == this.mode) {
			// Not switching modes after all
			return;
		}
		if (newMode != this.MODE_USER || newMode != this.MODE_SYSTEM) {
			// Switch banked registers
			var newBank = this.selectBank(newMode);
			var oldBank = this.selectBank(this.mode);
			if (newBank != oldBank) {
				// TODO: support FIQ
				if (newMode == this.MODE_FIQ || this.mode == this.MODE_FIQ) {
					var oldFiqBank = (oldBank == this.BANK_FIQ) + 0;
					var newFiqBank = (newBank == this.BANK_FIQ) + 0;
					this.bankedRegisters[oldFiqBank][2] = this.gprs[8];
					this.bankedRegisters[oldFiqBank][3] = this.gprs[9];
					this.bankedRegisters[oldFiqBank][4] = this.gprs[10];
					this.bankedRegisters[oldFiqBank][5] = this.gprs[11];
					this.bankedRegisters[oldFiqBank][6] = this.gprs[12];
					this.gprs[8] = this.bankedRegisters[newFiqBank][2];
					this.gprs[9] = this.bankedRegisters[newFiqBank][3];
					this.gprs[10] = this.bankedRegisters[newFiqBank][4];
					this.gprs[11] = this.bankedRegisters[newFiqBank][5];
					this.gprs[12] = this.bankedRegisters[newFiqBank][6];
				}
				this.bankedRegisters[oldBank][0] = this.gprs[this.SP];
				this.bankedRegisters[oldBank][1] = this.gprs[this.LR];
				this.gprs[this.SP] = this.bankedRegisters[newBank][0];
				this.gprs[this.LR] = this.bankedRegisters[newBank][1];

				this.bankedSPSRs[oldBank] = this.spsr;
				this.spsr = this.bankedSPSRs[newBank];
			}
		}
		this.mode = newMode;
	}
	packCPSR() {
		return (
			this.mode |
			(!!this.execMode << 5) |
			(!!this.cpsrF << 6) |
			(!!this.cpsrI << 7) |
			(!!this.cpsrN << 31) |
			(!!this.cpsrZ << 30) |
			(!!this.cpsrC << 29) |
			(!!this.cpsrV << 28)
		);
	}
	unpackCPSR(spsr) {
		this.switchMode(spsr & 0x0000001f);
		this.switchExecMode(!!(spsr & 0x00000020));
		this.cpsrF = spsr & 0x00000040;
		this.cpsrI = spsr & 0x00000080;
		this.cpsrN = spsr & 0x80000000;
		this.cpsrZ = spsr & 0x40000000;
		this.cpsrC = spsr & 0x20000000;
		this.cpsrV = spsr & 0x10000000;

		this.irq.testIRQ();
	}
	hasSPSR() {
		return this.mode != this.MODE_SYSTEM && this.mode != this.MODE_USER;
	}
	raiseIRQ() {
		if (this.cpsrI) {
			return;
		}
		var cpsr = this.packCPSR();
		var instructionWidth = this.instructionWidth;
		this.switchMode(this.MODE_IRQ);
		this.spsr = cpsr;
		this.gprs[this.LR] = this.gprs[this.PC] - instructionWidth + 4;
		this.gprs[this.PC] = this.BASE_IRQ + this.WORD_SIZE_ARM;
		this.instruction = null;
		this.switchExecMode(this.MODE_ARM);
		this.cpsrI = true;
	}
	raiseTrap() {
		var cpsr = this.packCPSR();
		var instructionWidth = this.instructionWidth;
		this.switchMode(this.MODE_SUPERVISOR);
		this.spsr = cpsr;
		this.gprs[this.LR] = this.gprs[this.PC] - instructionWidth;
		this.gprs[this.PC] = this.BASE_SWI + this.WORD_SIZE_ARM;
		this.instruction = null;
		this.switchExecMode(this.MODE_ARM);
		this.cpsrI = true;
	}
	badOp(instruction) {
		var func = function () {
			throw "Illegal instruction: 0x" + instruction.toString(16);
		};
		func.writesPC = true;
		func.fixedJump = false;
		return func;
	}
	generateConds() {
		var cpu = this;
		this.conds = [
			// EQ
			function () {
				return (cpu.conditionPassed = cpu.cpsrZ);
			},
			// NE
			function () {
				return (cpu.conditionPassed = !cpu.cpsrZ);
			},
			// CS
			function () {
				return (cpu.conditionPassed = cpu.cpsrC);
			},
			// CC
			function () {
				return (cpu.conditionPassed = !cpu.cpsrC);
			},
			// MI
			function () {
				return (cpu.conditionPassed = cpu.cpsrN);
			},
			// PL
			function () {
				return (cpu.conditionPassed = !cpu.cpsrN);
			},
			// VS
			function () {
				return (cpu.conditionPassed = cpu.cpsrV);
			},
			// VC
			function () {
				return (cpu.conditionPassed = !cpu.cpsrV);
			},
			// HI
			function () {
				return (cpu.conditionPassed = cpu.cpsrC && !cpu.cpsrZ);
			},
			// LS
			function () {
				return (cpu.conditionPassed = !cpu.cpsrC || cpu.cpsrZ);
			},
			// GE
			function () {
				return (cpu.conditionPassed = !cpu.cpsrN == !cpu.cpsrV);
			},
			// LT
			function () {
				return (cpu.conditionPassed = !cpu.cpsrN != !cpu.cpsrV);
			},
			// GT
			function () {
				return (cpu.conditionPassed =
					!cpu.cpsrZ && !cpu.cpsrN == !cpu.cpsrV);
			},
			// LE
			function () {
				return (cpu.conditionPassed =
					cpu.cpsrZ || !cpu.cpsrN != !cpu.cpsrV);
			},
			// AL
			null,
			null
		];
	}
	barrelShiftImmediate(shiftType, immediate, rm) {
		var cpu = this;
		var gprs = this.gprs;
		var shiftOp = this.badOp;
		switch (shiftType) {
			case 0x00000000:
				// LSL
				if (immediate) {
					shiftOp = function () {
						cpu.shifterOperand = gprs[rm] << immediate;
						cpu.shifterCarryOut =
							gprs[rm] & (1 << (32 - immediate));
					};
				} else {
					// This boils down to no shift
					shiftOp = function () {
						cpu.shifterOperand = gprs[rm];
						cpu.shifterCarryOut = cpu.cpsrC;
					};
				}
				break;
			case 0x00000020:
				// LSR
				if (immediate) {
					shiftOp = function () {
						cpu.shifterOperand = gprs[rm] >>> immediate;
						cpu.shifterCarryOut = gprs[rm] & (1 << (immediate - 1));
					};
				} else {
					shiftOp = function () {
						cpu.shifterOperand = 0;
						cpu.shifterCarryOut = gprs[rm] & 0x80000000;
					};
				}
				break;
			case 0x00000040:
				// ASR
				if (immediate) {
					shiftOp = function () {
						cpu.shifterOperand = gprs[rm] >> immediate;
						cpu.shifterCarryOut = gprs[rm] & (1 << (immediate - 1));
					};
				} else {
					shiftOp = function () {
						cpu.shifterCarryOut = gprs[rm] & 0x80000000;
						if (cpu.shifterCarryOut) {
							cpu.shifterOperand = 0xffffffff;
						} else {
							cpu.shifterOperand = 0;
						}
					};
				}
				break;
			case 0x00000060:
				// ROR
				if (immediate) {
					shiftOp = function () {
						cpu.shifterOperand =
							(gprs[rm] >>> immediate) |
							(gprs[rm] << (32 - immediate));
						cpu.shifterCarryOut = gprs[rm] & (1 << (immediate - 1));
					};
				} else {
					// RRX
					shiftOp = function () {
						cpu.shifterOperand =
							(!!cpu.cpsrC << 31) | (gprs[rm] >>> 1);
						cpu.shifterCarryOut = gprs[rm] & 0x00000001;
					};
				}
				break;
		}
		return shiftOp;
	}
	compileArm(instruction) {
		var op = this.badOp(instruction);
		var i = instruction & 0x0e000000;
		var cpu = this;
		var gprs = this.gprs;

		var condOp = this.conds[(instruction & 0xf0000000) >>> 28];
		if ((instruction & 0x0ffffff0) == 0x012fff10) {
			// BX
			var rm = instruction & 0xf;
			op = this.armCompiler.constructBX(rm, condOp);
			op.writesPC = true;
			op.fixedJump = false;
		} else if (
			!(instruction & 0x0c000000) &&
			(i == 0x02000000 || (instruction & 0x00000090) != 0x00000090)
		) {
			var opcode = instruction & 0x01e00000;
			var s = instruction & 0x00100000;
			var shiftsRs = false;
			if ((opcode & 0x01800000) == 0x01000000 && !s) {
				var r = instruction & 0x00400000;
				if ((instruction & 0x00b0f000) == 0x0020f000) {
					// MSR
					var rm = instruction & 0x0000000f;
					var immediate = instruction & 0x000000ff;
					var rotateImm = (instruction & 0x00000f00) >> 7;
					immediate =
						(immediate >>> rotateImm) |
						(immediate << (32 - rotateImm));
					op = this.armCompiler.constructMSR(
						rm,
						r,
						instruction,
						immediate,
						condOp
					);
					op.writesPC = false;
				} else if ((instruction & 0x00bf0000) == 0x000f0000) {
					// MRS
					var rd = (instruction & 0x0000f000) >> 12;
					op = this.armCompiler.constructMRS(rd, r, condOp);
					op.writesPC = rd == this.PC;
				}
			} else {
				// Data processing/FSR transfer
				var rn = (instruction & 0x000f0000) >> 16;
				var rd = (instruction & 0x0000f000) >> 12;

				// Parse shifter operand
				var shiftType = instruction & 0x00000060;
				var rm = instruction & 0x0000000f;
				var shiftOp = function () {
					throw "BUG: invalid barrel shifter";
				};
				if (instruction & 0x02000000) {
					var immediate = instruction & 0x000000ff;
					var rotate = (instruction & 0x00000f00) >> 7;
					if (!rotate) {
						shiftOp = this.armCompiler.constructAddressingMode1Immediate(
							immediate
						);
					} else {
						shiftOp = this.armCompiler.constructAddressingMode1ImmediateRotate(
							immediate,
							rotate
						);
					}
				} else if (instruction & 0x00000010) {
					var rs = (instruction & 0x00000f00) >> 8;
					shiftsRs = true;
					switch (shiftType) {
						case 0x00000000:
							// LSL
							shiftOp = this.armCompiler.constructAddressingMode1LSL(
								rs,
								rm
							);
							break;
						case 0x00000020:
							// LSR
							shiftOp = this.armCompiler.constructAddressingMode1LSR(
								rs,
								rm
							);
							break;
						case 0x00000040:
							// ASR
							shiftOp = this.armCompiler.constructAddressingMode1ASR(
								rs,
								rm
							);
							break;
						case 0x00000060:
							// ROR
							shiftOp = this.armCompiler.constructAddressingMode1ROR(
								rs,
								rm
							);
							break;
					}
				} else {
					var immediate = (instruction & 0x00000f80) >> 7;
					shiftOp = this.barrelShiftImmediate(
						shiftType,
						immediate,
						rm
					);
				}

				switch (opcode) {
					case 0x00000000:
						// AND
						if (s) {
							op = this.armCompiler.constructANDS(
								rd,
								rn,
								shiftOp,
								condOp
							);
						} else {
							op = this.armCompiler.constructAND(
								rd,
								rn,
								shiftOp,
								condOp
							);
						}
						break;
					case 0x00200000:
						// EOR
						if (s) {
							op = this.armCompiler.constructEORS(
								rd,
								rn,
								shiftOp,
								condOp
							);
						} else {
							op = this.armCompiler.constructEOR(
								rd,
								rn,
								shiftOp,
								condOp
							);
						}
						break;
					case 0x00400000:
						// SUB
						if (s) {
							op = this.armCompiler.constructSUBS(
								rd,
								rn,
								shiftOp,
								condOp
							);
						} else {
							op = this.armCompiler.constructSUB(
								rd,
								rn,
								shiftOp,
								condOp
							);
						}
						break;
					case 0x00600000:
						// RSB
						if (s) {
							op = this.armCompiler.constructRSBS(
								rd,
								rn,
								shiftOp,
								condOp
							);
						} else {
							op = this.armCompiler.constructRSB(
								rd,
								rn,
								shiftOp,
								condOp
							);
						}
						break;
					case 0x00800000:
						// ADD
						if (s) {
							op = this.armCompiler.constructADDS(
								rd,
								rn,
								shiftOp,
								condOp
							);
						} else {
							op = this.armCompiler.constructADD(
								rd,
								rn,
								shiftOp,
								condOp
							);
						}
						break;
					case 0x00a00000:
						// ADC
						if (s) {
							op = this.armCompiler.constructADCS(
								rd,
								rn,
								shiftOp,
								condOp
							);
						} else {
							op = this.armCompiler.constructADC(
								rd,
								rn,
								shiftOp,
								condOp
							);
						}
						break;
					case 0x00c00000:
						// SBC
						if (s) {
							op = this.armCompiler.constructSBCS(
								rd,
								rn,
								shiftOp,
								condOp
							);
						} else {
							op = this.armCompiler.constructSBC(
								rd,
								rn,
								shiftOp,
								condOp
							);
						}
						break;
					case 0x00e00000:
						// RSC
						if (s) {
							op = this.armCompiler.constructRSCS(
								rd,
								rn,
								shiftOp,
								condOp
							);
						} else {
							op = this.armCompiler.constructRSC(
								rd,
								rn,
								shiftOp,
								condOp
							);
						}
						break;
					case 0x01000000:
						// TST
						op = this.armCompiler.constructTST(
							rd,
							rn,
							shiftOp,
							condOp
						);
						break;
					case 0x01200000:
						// TEQ
						op = this.armCompiler.constructTEQ(
							rd,
							rn,
							shiftOp,
							condOp
						);
						break;
					case 0x01400000:
						// CMP
						op = this.armCompiler.constructCMP(
							rd,
							rn,
							shiftOp,
							condOp
						);
						break;
					case 0x01600000:
						// CMN
						op = this.armCompiler.constructCMN(
							rd,
							rn,
							shiftOp,
							condOp
						);
						break;
					case 0x01800000:
						// ORR
						if (s) {
							op = this.armCompiler.constructORRS(
								rd,
								rn,
								shiftOp,
								condOp
							);
						} else {
							op = this.armCompiler.constructORR(
								rd,
								rn,
								shiftOp,
								condOp
							);
						}
						break;
					case 0x01a00000:
						// MOV
						if (s) {
							op = this.armCompiler.constructMOVS(
								rd,
								rn,
								shiftOp,
								condOp
							);
						} else {
							op = this.armCompiler.constructMOV(
								rd,
								rn,
								shiftOp,
								condOp
							);
						}
						break;
					case 0x01c00000:
						// BIC
						if (s) {
							op = this.armCompiler.constructBICS(
								rd,
								rn,
								shiftOp,
								condOp
							);
						} else {
							op = this.armCompiler.constructBIC(
								rd,
								rn,
								shiftOp,
								condOp
							);
						}
						break;
					case 0x01e00000:
						// MVN
						if (s) {
							op = this.armCompiler.constructMVNS(
								rd,
								rn,
								shiftOp,
								condOp
							);
						} else {
							op = this.armCompiler.constructMVN(
								rd,
								rn,
								shiftOp,
								condOp
							);
						}
						break;
				}
				op.writesPC = rd == this.PC;
			}
		} else if ((instruction & 0x0fb00ff0) == 0x01000090) {
			// Single data swap
			var rm = instruction & 0x0000000f;
			var rd = (instruction >> 12) & 0x0000000f;
			var rn = (instruction >> 16) & 0x0000000f;
			if (instruction & 0x00400000) {
				op = this.armCompiler.constructSWPB(rd, rn, rm, condOp);
			} else {
				op = this.armCompiler.constructSWP(rd, rn, rm, condOp);
			}
			op.writesPC = rd == this.PC;
		} else {
			switch (i) {
				case 0x00000000:
					if ((instruction & 0x010000f0) == 0x00000090) {
						// Multiplies
						var rd = (instruction & 0x000f0000) >> 16;
						var rn = (instruction & 0x0000f000) >> 12;
						var rs = (instruction & 0x00000f00) >> 8;
						var rm = instruction & 0x0000000f;
						switch (instruction & 0x00f00000) {
							case 0x00000000:
								// MUL
								op = this.armCompiler.constructMUL(
									rd,
									rs,
									rm,
									condOp
								);
								break;
							case 0x00100000:
								// MULS
								op = this.armCompiler.constructMULS(
									rd,
									rs,
									rm,
									condOp
								);
								break;
							case 0x00200000:
								// MLA
								op = this.armCompiler.constructMLA(
									rd,
									rn,
									rs,
									rm,
									condOp
								);
								break;
							case 0x00300000:
								// MLAS
								op = this.armCompiler.constructMLAS(
									rd,
									rn,
									rs,
									rm,
									condOp
								);
								break;
							case 0x00800000:
								// UMULL
								op = this.armCompiler.constructUMULL(
									rd,
									rn,
									rs,
									rm,
									condOp
								);
								break;
							case 0x00900000:
								// UMULLS
								op = this.armCompiler.constructUMULLS(
									rd,
									rn,
									rs,
									rm,
									condOp
								);
								break;
							case 0x00a00000:
								// UMLAL
								op = this.armCompiler.constructUMLAL(
									rd,
									rn,
									rs,
									rm,
									condOp
								);
								break;
							case 0x00b00000:
								// UMLALS
								op = this.armCompiler.constructUMLALS(
									rd,
									rn,
									rs,
									rm,
									condOp
								);
								break;
							case 0x00c00000:
								// SMULL
								op = this.armCompiler.constructSMULL(
									rd,
									rn,
									rs,
									rm,
									condOp
								);
								break;
							case 0x00d00000:
								// SMULLS
								op = this.armCompiler.constructSMULLS(
									rd,
									rn,
									rs,
									rm,
									condOp
								);
								break;
							case 0x00e00000:
								// SMLAL
								op = this.armCompiler.constructSMLAL(
									rd,
									rn,
									rs,
									rm,
									condOp
								);
								break;
							case 0x00f00000:
								// SMLALS
								op = this.armCompiler.constructSMLALS(
									rd,
									rn,
									rs,
									rm,
									condOp
								);
								break;
						}
						op.writesPC = rd == this.PC;
					} else {
						// Halfword and signed byte data transfer
						var load = instruction & 0x00100000;
						var rd = (instruction & 0x0000f000) >> 12;
						var hiOffset = (instruction & 0x00000f00) >> 4;
						var loOffset = (rm = instruction & 0x0000000f);
						var h = instruction & 0x00000020;
						var s = instruction & 0x00000040;
						var w = instruction & 0x00200000;
						var i = instruction & 0x00400000;

						var address;
						if (i) {
							var immediate = loOffset | hiOffset;
							address = this.armCompiler.constructAddressingMode23Immediate(
								instruction,
								immediate,
								condOp
							);
						} else {
							address = this.armCompiler.constructAddressingMode23Register(
								instruction,
								rm,
								condOp
							);
						}
						address.writesPC = !!w && rn == this.PC;

						if ((instruction & 0x00000090) == 0x00000090) {
							if (load) {
								// Load [signed] halfword/byte
								if (h) {
									if (s) {
										// LDRSH
										op = this.armCompiler.constructLDRSH(
											rd,
											address,
											condOp
										);
									} else {
										// LDRH
										op = this.armCompiler.constructLDRH(
											rd,
											address,
											condOp
										);
									}
								} else {
									if (s) {
										// LDRSB
										op = this.armCompiler.constructLDRSB(
											rd,
											address,
											condOp
										);
									}
								}
							} else if (!s && h) {
								// STRH
								op = this.armCompiler.constructSTRH(
									rd,
									address,
									condOp
								);
							}
						}
						op.writesPC = rd == this.PC || address.writesPC;
					}
					break;
				case 0x04000000:
				case 0x06000000:
					// LDR/STR
					var rd = (instruction & 0x0000f000) >> 12;
					var load = instruction & 0x00100000;
					var b = instruction & 0x00400000;
					var i = instruction & 0x02000000;

					var address = function () {
						throw (
							"Unimplemented memory access: 0x" +
							instruction.toString(16)
						);
					};
					if (~instruction & 0x01000000) {
						// Clear the W bit if the P bit is clear--we don't support memory translation, so these turn into regular accesses
						instruction &= 0xffdfffff;
					}
					if (i) {
						// Register offset
						var rm = instruction & 0x0000000f;
						var shiftType = instruction & 0x00000060;
						var shiftImmediate = (instruction & 0x00000f80) >> 7;

						if (shiftType || shiftImmediate) {
							var shiftOp = this.barrelShiftImmediate(
								shiftType,
								shiftImmediate,
								rm
							);
							address = this.armCompiler.constructAddressingMode2RegisterShifted(
								instruction,
								shiftOp,
								condOp
							);
						} else {
							address = this.armCompiler.constructAddressingMode23Register(
								instruction,
								rm,
								condOp
							);
						}
					} else {
						// Immediate
						var offset = instruction & 0x00000fff;
						address = this.armCompiler.constructAddressingMode23Immediate(
							instruction,
							offset,
							condOp
						);
					}
					if (load) {
						if (b) {
							// LDRB
							op = this.armCompiler.constructLDRB(
								rd,
								address,
								condOp
							);
						} else {
							// LDR
							op = this.armCompiler.constructLDR(
								rd,
								address,
								condOp
							);
						}
					} else {
						if (b) {
							// STRB
							op = this.armCompiler.constructSTRB(
								rd,
								address,
								condOp
							);
						} else {
							// STR
							op = this.armCompiler.constructSTR(
								rd,
								address,
								condOp
							);
						}
					}
					op.writesPC = rd == this.PC || address.writesPC;
					break;
				case 0x08000000:
					// Block data transfer
					var load = instruction & 0x00100000;
					var w = instruction & 0x00200000;
					var user = instruction & 0x00400000;
					var u = instruction & 0x00800000;
					var p = instruction & 0x01000000;
					var rs = instruction & 0x0000ffff;
					var rn = (instruction & 0x000f0000) >> 16;

					var address;
					var immediate = 0;
					var offset = 0;
					var overlap = false;
					if (u) {
						if (p) {
							immediate = 4;
						}
						for (var m = 0x01, i = 0; i < 16; m <<= 1, ++i) {
							if (rs & m) {
								if (w && i == rn && !offset) {
									rs &= ~m;
									immediate += 4;
									overlap = true;
								}
								offset += 4;
							}
						}
					} else {
						if (!p) {
							immediate = 4;
						}
						for (var m = 0x01, i = 0; i < 16; m <<= 1, ++i) {
							if (rs & m) {
								if (w && i == rn && !offset) {
									rs &= ~m;
									immediate += 4;
									overlap = true;
								}
								immediate -= 4;
								offset -= 4;
							}
						}
					}
					if (w) {
						address = this.armCompiler.constructAddressingMode4Writeback(
							immediate,
							offset,
							rn,
							overlap
						);
					} else {
						address = this.armCompiler.constructAddressingMode4(
							immediate,
							rn
						);
					}
					if (load) {
						// LDM
						if (user) {
							op = this.armCompiler.constructLDMS(
								rs,
								address,
								condOp
							);
						} else {
							op = this.armCompiler.constructLDM(
								rs,
								address,
								condOp
							);
						}
						op.writesPC = !!(rs & (1 << 15));
					} else {
						// STM
						if (user) {
							op = this.armCompiler.constructSTMS(
								rs,
								address,
								condOp
							);
						} else {
							op = this.armCompiler.constructSTM(
								rs,
								address,
								condOp
							);
						}
						op.writesPC = false;
					}
					break;
				case 0x0a000000:
					// Branch
					var immediate = instruction & 0x00ffffff;
					if (immediate & 0x00800000) {
						immediate |= 0xff000000;
					}
					immediate <<= 2;
					var link = instruction & 0x01000000;
					if (link) {
						op = this.armCompiler.constructBL(immediate, condOp);
					} else {
						op = this.armCompiler.constructB(immediate, condOp);
					}
					op.writesPC = true;
					op.fixedJump = true;
					break;
				case 0x0c000000:
					// Coprocessor data transfer
					break;
				case 0x0e000000:
					// Coprocessor data operation/SWI
					if ((instruction & 0x0f000000) == 0x0f000000) {
						// SWI
						var immediate = instruction & 0x00ffffff;
						op = this.armCompiler.constructSWI(immediate, condOp);
						op.writesPC = false;
					}
					break;
				default:
					throw "Bad opcode: 0x" + instruction.toString(16);
			}
		}

		op.execMode = this.MODE_ARM;
		op.fixedJump = op.fixedJump || false;
		return op;
	}
	compileThumb(instruction) {
		var op = this.badOp(instruction & 0xffff);
		var cpu = this;
		var gprs = this.gprs;
		if ((instruction & 0xfc00) == 0x4000) {
			// Data-processing register
			var rm = (instruction & 0x0038) >> 3;
			var rd = instruction & 0x0007;
			switch (instruction & 0x03c0) {
				case 0x0000:
					// AND
					op = this.thumbCompiler.constructAND(rd, rm);
					break;
				case 0x0040:
					// EOR
					op = this.thumbCompiler.constructEOR(rd, rm);
					break;
				case 0x0080:
					// LSL(2)
					op = this.thumbCompiler.constructLSL2(rd, rm);
					break;
				case 0x00c0:
					// LSR(2)
					op = this.thumbCompiler.constructLSR2(rd, rm);
					break;
				case 0x0100:
					// ASR(2)
					op = this.thumbCompiler.constructASR2(rd, rm);
					break;
				case 0x0140:
					// ADC
					op = this.thumbCompiler.constructADC(rd, rm);
					break;
				case 0x0180:
					// SBC
					op = this.thumbCompiler.constructSBC(rd, rm);
					break;
				case 0x01c0:
					// ROR
					op = this.thumbCompiler.constructROR(rd, rm);
					break;
				case 0x0200:
					// TST
					op = this.thumbCompiler.constructTST(rd, rm);
					break;
				case 0x0240:
					// NEG
					op = this.thumbCompiler.constructNEG(rd, rm);
					break;
				case 0x0280:
					// CMP(2)
					op = this.thumbCompiler.constructCMP2(rd, rm);
					break;
				case 0x02c0:
					// CMN
					op = this.thumbCompiler.constructCMN(rd, rm);
					break;
				case 0x0300:
					// ORR
					op = this.thumbCompiler.constructORR(rd, rm);
					break;
				case 0x0340:
					// MUL
					op = this.thumbCompiler.constructMUL(rd, rm);
					break;
				case 0x0380:
					// BIC
					op = this.thumbCompiler.constructBIC(rd, rm);
					break;
				case 0x03c0:
					// MVN
					op = this.thumbCompiler.constructMVN(rd, rm);
					break;
			}
			op.writesPC = false;
		} else if ((instruction & 0xfc00) == 0x4400) {
			// Special data processing / branch/exchange instruction set
			var rm = (instruction & 0x0078) >> 3;
			var rn = instruction & 0x0007;
			var h1 = instruction & 0x0080;
			var rd = rn | (h1 >> 4);
			switch (instruction & 0x0300) {
				case 0x0000:
					// ADD(4)
					op = this.thumbCompiler.constructADD4(rd, rm);
					op.writesPC = rd == this.PC;
					break;
				case 0x0100:
					// CMP(3)
					op = this.thumbCompiler.constructCMP3(rd, rm);
					op.writesPC = false;
					break;
				case 0x0200:
					// MOV(3)
					op = this.thumbCompiler.constructMOV3(rd, rm);
					op.writesPC = rd == this.PC;
					break;
				case 0x0300:
					// BX
					op = this.thumbCompiler.constructBX(rd, rm);
					op.writesPC = true;
					op.fixedJump = false;
					break;
			}
		} else if ((instruction & 0xf800) == 0x1800) {
			// Add/subtract
			var rm = (instruction & 0x01c0) >> 6;
			var rn = (instruction & 0x0038) >> 3;
			var rd = instruction & 0x0007;
			switch (instruction & 0x0600) {
				case 0x0000:
					// ADD(3)
					op = this.thumbCompiler.constructADD3(rd, rn, rm);
					break;
				case 0x0200:
					// SUB(3)
					op = this.thumbCompiler.constructSUB3(rd, rn, rm);
					break;
				case 0x0400:
					var immediate = (instruction & 0x01c0) >> 6;
					if (immediate) {
						// ADD(1)
						op = this.thumbCompiler.constructADD1(
							rd,
							rn,
							immediate
						);
					} else {
						// MOV(2)
						op = this.thumbCompiler.constructMOV2(rd, rn, rm);
					}
					break;
				case 0x0600:
					// SUB(1)
					var immediate = (instruction & 0x01c0) >> 6;
					op = this.thumbCompiler.constructSUB1(rd, rn, immediate);
					break;
			}
			op.writesPC = false;
		} else if (!(instruction & 0xe000)) {
			// Shift by immediate
			var rd = instruction & 0x0007;
			var rm = (instruction & 0x0038) >> 3;
			var immediate = (instruction & 0x07c0) >> 6;
			switch (instruction & 0x1800) {
				case 0x0000:
					// LSL(1)
					op = this.thumbCompiler.constructLSL1(rd, rm, immediate);
					break;
				case 0x0800:
					// LSR(1)
					op = this.thumbCompiler.constructLSR1(rd, rm, immediate);
					break;
				case 0x1000:
					// ASR(1)
					op = this.thumbCompiler.constructASR1(rd, rm, immediate);
					break;
				case 0x1800:
					break;
			}
			op.writesPC = false;
		} else if ((instruction & 0xe000) == 0x2000) {
			// Add/subtract/compare/move immediate
			var immediate = instruction & 0x00ff;
			var rn = (instruction & 0x0700) >> 8;
			switch (instruction & 0x1800) {
				case 0x0000:
					// MOV(1)
					op = this.thumbCompiler.constructMOV1(rn, immediate);
					break;
				case 0x0800:
					// CMP(1)
					op = this.thumbCompiler.constructCMP1(rn, immediate);
					break;
				case 0x1000:
					// ADD(2)
					op = this.thumbCompiler.constructADD2(rn, immediate);
					break;
				case 0x1800:
					// SUB(2)
					op = this.thumbCompiler.constructSUB2(rn, immediate);
					break;
			}
			op.writesPC = false;
		} else if ((instruction & 0xf800) == 0x4800) {
			// LDR(3)
			var rd = (instruction & 0x0700) >> 8;
			var immediate = (instruction & 0x00ff) << 2;
			op = this.thumbCompiler.constructLDR3(rd, immediate);
			op.writesPC = false;
		} else if ((instruction & 0xf000) == 0x5000) {
			// Load and store with relative offset
			var rd = instruction & 0x0007;
			var rn = (instruction & 0x0038) >> 3;
			var rm = (instruction & 0x01c0) >> 6;
			var opcode = instruction & 0x0e00;
			switch (opcode) {
				case 0x0000:
					// STR(2)
					op = this.thumbCompiler.constructSTR2(rd, rn, rm);
					break;
				case 0x0200:
					// STRH(2)
					op = this.thumbCompiler.constructSTRH2(rd, rn, rm);
					break;
				case 0x0400:
					// STRB(2)
					op = this.thumbCompiler.constructSTRB2(rd, rn, rm);
					break;
				case 0x0600:
					// LDRSB
					op = this.thumbCompiler.constructLDRSB(rd, rn, rm);
					break;
				case 0x0800:
					// LDR(2)
					op = this.thumbCompiler.constructLDR2(rd, rn, rm);
					break;
				case 0x0a00:
					// LDRH(2)
					op = this.thumbCompiler.constructLDRH2(rd, rn, rm);
					break;
				case 0x0c00:
					// LDRB(2)
					op = this.thumbCompiler.constructLDRB2(rd, rn, rm);
					break;
				case 0x0e00:
					// LDRSH
					op = this.thumbCompiler.constructLDRSH(rd, rn, rm);
					break;
			}
			op.writesPC = false;
		} else if ((instruction & 0xe000) == 0x6000) {
			// Load and store with immediate offset
			var rd = instruction & 0x0007;
			var rn = (instruction & 0x0038) >> 3;
			var immediate = (instruction & 0x07c0) >> 4;
			var b = instruction & 0x1000;
			if (b) {
				immediate >>= 2;
			}
			var load = instruction & 0x0800;
			if (load) {
				if (b) {
					// LDRB(1)
					op = this.thumbCompiler.constructLDRB1(rd, rn, immediate);
				} else {
					// LDR(1)
					op = this.thumbCompiler.constructLDR1(rd, rn, immediate);
				}
			} else {
				if (b) {
					// STRB(1)
					op = this.thumbCompiler.constructSTRB1(rd, rn, immediate);
				} else {
					// STR(1)
					op = this.thumbCompiler.constructSTR1(rd, rn, immediate);
				}
			}
			op.writesPC = false;
		} else if ((instruction & 0xf600) == 0xb400) {
			// Push and pop registers
			var r = !!(instruction & 0x0100);
			var rs = instruction & 0x00ff;
			if (instruction & 0x0800) {
				// POP
				op = this.thumbCompiler.constructPOP(rs, r);
				op.writesPC = r;
				op.fixedJump = false;
			} else {
				// PUSH
				op = this.thumbCompiler.constructPUSH(rs, r);
				op.writesPC = false;
			}
		} else if (instruction & 0x8000) {
			switch (instruction & 0x7000) {
				case 0x0000:
					// Load and store halfword
					var rd = instruction & 0x0007;
					var rn = (instruction & 0x0038) >> 3;
					var immediate = (instruction & 0x07c0) >> 5;
					if (instruction & 0x0800) {
						// LDRH(1)
						op = this.thumbCompiler.constructLDRH1(
							rd,
							rn,
							immediate
						);
					} else {
						// STRH(1)
						op = this.thumbCompiler.constructSTRH1(
							rd,
							rn,
							immediate
						);
					}
					op.writesPC = false;
					break;
				case 0x1000:
					// SP-relative load and store
					var rd = (instruction & 0x0700) >> 8;
					var immediate = (instruction & 0x00ff) << 2;
					var load = instruction & 0x0800;
					if (load) {
						// LDR(4)
						op = this.thumbCompiler.constructLDR4(rd, immediate);
					} else {
						// STR(3)
						op = this.thumbCompiler.constructSTR3(rd, immediate);
					}
					op.writesPC = false;
					break;
				case 0x2000:
					// Load address
					var rd = (instruction & 0x0700) >> 8;
					var immediate = (instruction & 0x00ff) << 2;
					if (instruction & 0x0800) {
						// ADD(6)
						op = this.thumbCompiler.constructADD6(rd, immediate);
					} else {
						// ADD(5)
						op = this.thumbCompiler.constructADD5(rd, immediate);
					}
					op.writesPC = false;
					break;
				case 0x3000:
					// Miscellaneous
					if (!(instruction & 0x0f00)) {
						// Adjust stack pointer
						// ADD(7)/SUB(4)
						var b = instruction & 0x0080;
						var immediate = (instruction & 0x7f) << 2;
						if (b) {
							immediate = -immediate;
						}
						op = this.thumbCompiler.constructADD7(immediate);
						op.writesPC = false;
					}
					break;
				case 0x4000:
					// Multiple load and store
					var rn = (instruction & 0x0700) >> 8;
					var rs = instruction & 0x00ff;
					if (instruction & 0x0800) {
						// LDMIA
						op = this.thumbCompiler.constructLDMIA(rn, rs);
					} else {
						// STMIA
						op = this.thumbCompiler.constructSTMIA(rn, rs);
					}
					op.writesPC = false;
					break;
				case 0x5000:
					// Conditional branch
					var cond = (instruction & 0x0f00) >> 8;
					var immediate = instruction & 0x00ff;
					if (cond == 0xf) {
						// SWI
						op = this.thumbCompiler.constructSWI(immediate);
						op.writesPC = false;
					} else {
						// B(1)
						if (instruction & 0x0080) {
							immediate |= 0xffffff00;
						}
						immediate <<= 1;
						var condOp = this.conds[cond];
						op = this.thumbCompiler.constructB1(immediate, condOp);
						op.writesPC = true;
						op.fixedJump = true;
					}
					break;
				case 0x6000:
				case 0x7000:
					// BL(X)
					var immediate = instruction & 0x07ff;
					var h = instruction & 0x1800;
					switch (h) {
						case 0x0000:
							// B(2)
							if (immediate & 0x0400) {
								immediate |= 0xfffff800;
							}
							immediate <<= 1;
							op = this.thumbCompiler.constructB2(immediate);
							op.writesPC = true;
							op.fixedJump = true;
							break;
						case 0x0800:
							// BLX (ARMv5T)
							/*op = function() {
                                var pc = gprs[cpu.PC];
                                gprs[cpu.PC] = (gprs[cpu.LR] + (immediate << 1)) & 0xFFFFFFFC;
                                gprs[cpu.LR] = pc - 1;
                                cpu.switchExecMode(cpu.MODE_ARM);
                            }*/
							break;
						case 0x1000:
							// BL(1)
							if (immediate & 0x0400) {
								immediate |= 0xfffffc00;
							}
							immediate <<= 12;
							op = this.thumbCompiler.constructBL1(immediate);
							op.writesPC = false;
							break;
						case 0x1800:
							// BL(2)
							op = this.thumbCompiler.constructBL2(immediate);
							op.writesPC = true;
							op.fixedJump = false;
							break;
					}
					break;
				default:
					this.WARN(
						"Undefined instruction: 0x" + instruction.toString(16)
					);
			}
		} else {
			throw "Bad opcode: 0x" + instruction.toString(16);
		}

		op.execMode = this.MODE_THUMB;
		op.fixedJump = op.fixedJump || false;
		return op;
	}
}

class ARMCoreArm {
	constructor(cpu) {
		this.cpu = cpu;

		this.addressingMode23Immediate = [
			// 000x0
			function (rn, offset, condOp) {
				var gprs = cpu.gprs;
				var address = function () {
					var addr = gprs[rn];
					if (!condOp || condOp()) {
						gprs[rn] -= offset;
					}
					return addr;
				};
				address.writesPC = rn == cpu.PC;
				return address;
			},

			// 000xW
			null,

			null,
			null,

			// 00Ux0
			function (rn, offset, condOp) {
				var gprs = cpu.gprs;
				var address = function () {
					var addr = gprs[rn];
					if (!condOp || condOp()) {
						gprs[rn] += offset;
					}
					return addr;
				};
				address.writesPC = rn == cpu.PC;
				return address;
			},

			// 00UxW
			null,

			null,
			null,

			// 0P0x0
			function (rn, offset, condOp) {
				var gprs = cpu.gprs;
				var addr; // ???
				var address = function () {
					return (addr = gprs[rn] - offset);
				};
				address.writesPC = false;
				return address;
			},

			// 0P0xW
			function (rn, offset, condOp) {
				var gprs = cpu.gprs;
				var address = function () {
					var addr = gprs[rn] - offset;
					if (!condOp || condOp()) {
						gprs[rn] = addr;
					}
					return addr;
				};
				address.writesPC = rn == cpu.PC;
				return address;
			},

			null,
			null,

			// 0PUx0
			function (rn, offset, condOp) {
				var gprs = cpu.gprs;
				var addr; // ???
				var address = function () {
					return (addr = gprs[rn] + offset);
				};
				address.writesPC = false;
				return address;
			},

			// 0PUxW
			function (rn, offset, condOp) {
				var gprs = cpu.gprs;
				var address = function () {
					var addr = gprs[rn] + offset;
					if (!condOp || condOp()) {
						gprs[rn] = addr;
					}
					return addr;
				};
				address.writesPC = rn == cpu.PC;
				return address;
			},

			null,
			null
		];

		this.addressingMode23Register = [
			// I00x0
			function (rn, rm, condOp) {
				var gprs = cpu.gprs;
				var address = function () {
					var addr = gprs[rn];
					if (!condOp || condOp()) {
						gprs[rn] -= gprs[rm];
					}
					return addr;
				};
				address.writesPC = rn == cpu.PC;
				return address;
			},

			// I00xW
			null,

			null,
			null,

			// I0Ux0
			function (rn, rm, condOp) {
				var gprs = cpu.gprs;
				var address = function () {
					var addr = gprs[rn];
					if (!condOp || condOp()) {
						gprs[rn] += gprs[rm];
					}
					return addr;
				};
				address.writesPC = rn == cpu.PC;
				return address;
			},

			// I0UxW
			null,

			null,
			null,

			// IP0x0
			function (rn, rm, condOp) {
				var gprs = cpu.gprs;
				var address = function () {
					return gprs[rn] - gprs[rm];
				};
				address.writesPC = false;
				return address;
			},

			// IP0xW
			function (rn, rm, condOp) {
				var gprs = cpu.gprs;
				var address = function () {
					var addr = gprs[rn] - gprs[rm];
					if (!condOp || condOp()) {
						gprs[rn] = addr;
					}
					return addr;
				};
				address.writesPC = rn == cpu.PC;
				return address;
			},

			null,
			null,

			// IPUx0
			function (rn, rm, condOp) {
				var gprs = cpu.gprs;
				var address = function () {
					var addr = gprs[rn] + gprs[rm];
					return addr;
				};
				address.writesPC = false;
				return address;
			},

			// IPUxW
			function (rn, rm, condOp) {
				var gprs = cpu.gprs;
				var address = function () {
					var addr = gprs[rn] + gprs[rm];
					if (!condOp || condOp()) {
						gprs[rn] = addr;
					}
					return addr;
				};
				address.writesPC = rn == cpu.PC;
				return address;
			},

			null,
			null
		];

		this.addressingMode2RegisterShifted = [
			// I00x0
			function (rn, shiftOp, condOp) {
				var gprs = cpu.gprs;
				var address = function () {
					var addr = gprs[rn];
					if (!condOp || condOp()) {
						shiftOp();
						gprs[rn] -= cpu.shifterOperand;
					}
					return addr;
				};
				address.writesPC = rn == cpu.PC;
				return address;
			},

			// I00xW
			null,

			null,
			null,

			// I0Ux0
			function (rn, shiftOp, condOp) {
				var gprs = cpu.gprs;
				var address = function () {
					var addr = gprs[rn];
					if (!condOp || condOp()) {
						shiftOp();
						gprs[rn] += cpu.shifterOperand;
					}
					return addr;
				};
				address.writesPC = rn == cpu.PC;
				return address;
			},
			// I0UxW
			null,

			null,
			null,

			// IP0x0
			function (rn, shiftOp, condOp) {
				var gprs = cpu.gprs;
				var address = function () {
					shiftOp();
					return gprs[rn] - cpu.shifterOperand;
				};
				address.writesPC = false;
				return address;
			},

			// IP0xW
			function (rn, shiftOp, condOp) {
				var gprs = cpu.gprs;
				var address = function () {
					shiftOp();
					var addr = gprs[rn] - cpu.shifterOperand;
					if (!condOp || condOp()) {
						gprs[rn] = addr;
					}
					return addr;
				};
				address.writesPC = rn == cpu.PC;
				return address;
			},

			null,
			null,

			// IPUx0
			function (rn, shiftOp, condOp) {
				var gprs = cpu.gprs;
				var address = function () {
					shiftOp();
					return gprs[rn] + cpu.shifterOperand;
				};
				address.writesPC = false;
				return address;
			},

			// IPUxW
			function (rn, shiftOp, condOp) {
				var gprs = cpu.gprs;
				var address = function () {
					shiftOp();
					var addr = gprs[rn] + cpu.shifterOperand;
					if (!condOp || condOp()) {
						gprs[rn] = addr;
					}
					return addr;
				};
				address.writePC = rn == cpu.PC;
				return address;
			},

			null,
			null
		];
	}
	constructAddressingMode1ASR(rs, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			++cpu.cycles;
			var shift = gprs[rs];
			if (rs == cpu.PC) {
				shift += 4;
			}
			shift &= 0xff;
			var shiftVal = gprs[rm];
			if (rm == cpu.PC) {
				shiftVal += 4;
			}
			if (shift == 0) {
				cpu.shifterOperand = shiftVal;
				cpu.shifterCarryOut = cpu.cpsrC;
			} else if (shift < 32) {
				cpu.shifterOperand = shiftVal >> shift;
				cpu.shifterCarryOut = shiftVal & (1 << (shift - 1));
			} else if (gprs[rm] >> 31) {
				cpu.shifterOperand = 0xffffffff;
				cpu.shifterCarryOut = 0x80000000;
			} else {
				cpu.shifterOperand = 0;
				cpu.shifterCarryOut = 0;
			}
		};
	}
	constructAddressingMode1Immediate(immediate) {
		var cpu = this.cpu;
		return function () {
			cpu.shifterOperand = immediate;
			cpu.shifterCarryOut = cpu.cpsrC;
		};
	}
	constructAddressingMode1ImmediateRotate(immediate, rotate) {
		var cpu = this.cpu;
		return function () {
			cpu.shifterOperand =
				(immediate >>> rotate) | (immediate << (32 - rotate));
			cpu.shifterCarryOut = cpu.shifterOperand >> 31;
		};
	}
	constructAddressingMode1LSL(rs, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			++cpu.cycles;
			var shift = gprs[rs];
			if (rs == cpu.PC) {
				shift += 4;
			}
			shift &= 0xff;
			var shiftVal = gprs[rm];
			if (rm == cpu.PC) {
				shiftVal += 4;
			}
			if (shift == 0) {
				cpu.shifterOperand = shiftVal;
				cpu.shifterCarryOut = cpu.cpsrC;
			} else if (shift < 32) {
				cpu.shifterOperand = shiftVal << shift;
				cpu.shifterCarryOut = shiftVal & (1 << (32 - shift));
			} else if (shift == 32) {
				cpu.shifterOperand = 0;
				cpu.shifterCarryOut = shiftVal & 1;
			} else {
				cpu.shifterOperand = 0;
				cpu.shifterCarryOut = 0;
			}
		};
	}
	constructAddressingMode1LSR(rs, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			++cpu.cycles;
			var shift = gprs[rs];
			if (rs == cpu.PC) {
				shift += 4;
			}
			shift &= 0xff;
			var shiftVal = gprs[rm];
			if (rm == cpu.PC) {
				shiftVal += 4;
			}
			if (shift == 0) {
				cpu.shifterOperand = shiftVal;
				cpu.shifterCarryOut = cpu.cpsrC;
			} else if (shift < 32) {
				cpu.shifterOperand = shiftVal >>> shift;
				cpu.shifterCarryOut = shiftVal & (1 << (shift - 1));
			} else if (shift == 32) {
				cpu.shifterOperand = 0;
				cpu.shifterCarryOut = shiftVal >> 31;
			} else {
				cpu.shifterOperand = 0;
				cpu.shifterCarryOut = 0;
			}
		};
	}
	constructAddressingMode1ROR(rs, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			++cpu.cycles;
			var shift = gprs[rs];
			if (rs == cpu.PC) {
				shift += 4;
			}
			shift &= 0xff;
			var shiftVal = gprs[rm];
			if (rm == cpu.PC) {
				shiftVal += 4;
			}
			var rotate = shift & 0x1f;
			if (shift == 0) {
				cpu.shifterOperand = shiftVal;
				cpu.shifterCarryOut = cpu.cpsrC;
			} else if (rotate) {
				cpu.shifterOperand =
					(gprs[rm] >>> rotate) | (gprs[rm] << (32 - rotate));
				cpu.shifterCarryOut = shiftVal & (1 << (rotate - 1));
			} else {
				cpu.shifterOperand = shiftVal;
				cpu.shifterCarryOut = shiftVal >> 31;
			}
		};
	}
	constructAddressingMode23Immediate(instruction, immediate, condOp) {
		var rn = (instruction & 0x000f0000) >> 16;
		return this.addressingMode23Immediate[(instruction & 0x01a00000) >> 21](
			rn,
			immediate,
			condOp
		);
	}
	constructAddressingMode23Register(instruction, rm, condOp) {
		var rn = (instruction & 0x000f0000) >> 16;
		return this.addressingMode23Register[(instruction & 0x01a00000) >> 21](
			rn,
			rm,
			condOp
		);
	}
	constructAddressingMode2RegisterShifted(instruction, shiftOp, condOp) {
		var rn = (instruction & 0x000f0000) >> 16;
		return this.addressingMode2RegisterShifted[
			(instruction & 0x01a00000) >> 21
		](rn, shiftOp, condOp);
	}
	constructAddressingMode4(immediate, rn) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			var addr = gprs[rn] + immediate;
			return addr;
		};
	}
	constructAddressingMode4Writeback(immediate, offset, rn, overlap) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function (writeInitial) {
			var addr = gprs[rn] + immediate;
			if (writeInitial && overlap) {
				cpu.mmu.store32(gprs[rn] + immediate - 4, gprs[rn]);
			}
			gprs[rn] += offset;
			return addr;
		};
	}
	constructADC(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			var shifterOperand = (cpu.shifterOperand >>> 0) + !!cpu.cpsrC;
			gprs[rd] = (gprs[rn] >>> 0) + shifterOperand;
		};
	}
	constructADCS(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			var shifterOperand = (cpu.shifterOperand >>> 0) + !!cpu.cpsrC;
			var d = (gprs[rn] >>> 0) + shifterOperand;
			if (rd == cpu.PC && cpu.hasSPSR()) {
				cpu.unpackCPSR(cpu.spsr);
			} else {
				cpu.cpsrN = d >> 31;
				cpu.cpsrZ = !(d & 0xffffffff);
				cpu.cpsrC = d > 0xffffffff;
				cpu.cpsrV =
					gprs[rn] >> 31 == shifterOperand >> 31 &&
					gprs[rn] >> 31 != d >> 31 &&
					shifterOperand >> 31 != d >> 31;
			}
			gprs[rd] = d;
		};
	}
	constructADD(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			gprs[rd] = (gprs[rn] >>> 0) + (cpu.shifterOperand >>> 0);
		};
	}
	constructADDS(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			var d = (gprs[rn] >>> 0) + (cpu.shifterOperand >>> 0);
			if (rd == cpu.PC && cpu.hasSPSR()) {
				cpu.unpackCPSR(cpu.spsr);
			} else {
				cpu.cpsrN = d >> 31;
				cpu.cpsrZ = !(d & 0xffffffff);
				cpu.cpsrC = d > 0xffffffff;
				cpu.cpsrV =
					gprs[rn] >> 31 == cpu.shifterOperand >> 31 &&
					gprs[rn] >> 31 != d >> 31 &&
					cpu.shifterOperand >> 31 != d >> 31;
			}
			gprs[rd] = d;
		};
	}
	constructAND(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			gprs[rd] = gprs[rn] & cpu.shifterOperand;
		};
	}
	constructANDS(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			gprs[rd] = gprs[rn] & cpu.shifterOperand;
			if (rd == cpu.PC && cpu.hasSPSR()) {
				cpu.unpackCPSR(cpu.spsr);
			} else {
				cpu.cpsrN = gprs[rd] >> 31;
				cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
				cpu.cpsrC = cpu.shifterCarryOut;
			}
		};
	}
	constructB(immediate, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			if (condOp && !condOp()) {
				cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
				return;
			}
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			gprs[cpu.PC] += immediate;
		};
	}
	constructBIC(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			gprs[rd] = gprs[rn] & ~cpu.shifterOperand;
		};
	}
	constructBICS(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			gprs[rd] = gprs[rn] & ~cpu.shifterOperand;
			if (rd == cpu.PC && cpu.hasSPSR()) {
				cpu.unpackCPSR(cpu.spsr);
			} else {
				cpu.cpsrN = gprs[rd] >> 31;
				cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
				cpu.cpsrC = cpu.shifterCarryOut;
			}
		};
	}
	constructBL(immediate, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			if (condOp && !condOp()) {
				cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
				return;
			}
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			gprs[cpu.LR] = gprs[cpu.PC] - 4;
			gprs[cpu.PC] += immediate;
		};
	}
	constructBX(rm, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			if (condOp && !condOp()) {
				cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
				return;
			}
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			cpu.switchExecMode(gprs[rm] & 0x00000001);
			gprs[cpu.PC] = gprs[rm] & 0xfffffffe;
		};
	}
	constructCMN(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			var aluOut = (gprs[rn] >>> 0) + (cpu.shifterOperand >>> 0);
			cpu.cpsrN = aluOut >> 31;
			cpu.cpsrZ = !(aluOut & 0xffffffff);
			cpu.cpsrC = aluOut > 0xffffffff;
			cpu.cpsrV =
				gprs[rn] >> 31 == cpu.shifterOperand >> 31 &&
				gprs[rn] >> 31 != aluOut >> 31 &&
				cpu.shifterOperand >> 31 != aluOut >> 31;
		};
	}
	constructCMP(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			var aluOut = gprs[rn] - cpu.shifterOperand;
			cpu.cpsrN = aluOut >> 31;
			cpu.cpsrZ = !(aluOut & 0xffffffff);
			cpu.cpsrC = gprs[rn] >>> 0 >= cpu.shifterOperand >>> 0;
			cpu.cpsrV =
				gprs[rn] >> 31 != cpu.shifterOperand >> 31 &&
				gprs[rn] >> 31 != aluOut >> 31;
		};
	}
	constructEOR(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			gprs[rd] = gprs[rn] ^ cpu.shifterOperand;
		};
	}
	constructEORS(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			gprs[rd] = gprs[rn] ^ cpu.shifterOperand;
			if (rd == cpu.PC && cpu.hasSPSR()) {
				cpu.unpackCPSR(cpu.spsr);
			} else {
				cpu.cpsrN = gprs[rd] >> 31;
				cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
				cpu.cpsrC = cpu.shifterCarryOut;
			}
		};
	}
	constructLDM(rs, address, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		var mmu = cpu.mmu;
		return function () {
			mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			var addr = address(false);
			var total = 0;
			var m, i;
			for (m = rs, i = 0; m; m >>= 1, ++i) {
				if (m & 1) {
					gprs[i] = mmu.load32(addr & 0xfffffffc);
					addr += 4;
					++total;
				}
			}
			mmu.waitMulti32(addr, total);
			++cpu.cycles;
		};
	}
	constructLDMS(rs, address, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		var mmu = cpu.mmu;
		return function () {
			mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			var addr = address(false);
			var total = 0;
			var mode = cpu.mode;
			cpu.switchMode(cpu.MODE_SYSTEM);
			var m, i;
			for (m = rs, i = 0; m; m >>= 1, ++i) {
				if (m & 1) {
					gprs[i] = mmu.load32(addr & 0xfffffffc);
					addr += 4;
					++total;
				}
			}
			cpu.switchMode(mode);
			mmu.waitMulti32(addr, total);
			++cpu.cycles;
		};
	}
	constructLDR(rd, address, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			var addr = address();
			gprs[rd] = cpu.mmu.load32(addr);
			cpu.mmu.wait32(addr);
			++cpu.cycles;
		};
	}
	constructLDRB(rd, address, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			var addr = address();
			gprs[rd] = cpu.mmu.loadU8(addr);
			cpu.mmu.wait(addr);
			++cpu.cycles;
		};
	}
	constructLDRH(rd, address, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			var addr = address();
			gprs[rd] = cpu.mmu.loadU16(addr);
			cpu.mmu.wait(addr);
			++cpu.cycles;
		};
	}
	constructLDRSB(rd, address, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			var addr = address();
			gprs[rd] = cpu.mmu.load8(addr);
			cpu.mmu.wait(addr);
			++cpu.cycles;
		};
	}
	constructLDRSH(rd, address, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			var addr = address();
			gprs[rd] = cpu.mmu.load16(addr);
			cpu.mmu.wait(addr);
			++cpu.cycles;
		};
	}
	constructMLA(rd, rn, rs, rm, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			++cpu.cycles;
			cpu.mmu.waitMul(rs);
			if (gprs[rm] & 0xffff0000 && gprs[rs] & 0xffff0000) {
				// Our data type is a double--we'll lose bits if we do it all at once!
				var hi = ((gprs[rm] & 0xffff0000) * gprs[rs]) & 0xffffffff;
				var lo = ((gprs[rm] & 0x0000ffff) * gprs[rs]) & 0xffffffff;
				gprs[rd] = (hi + lo + gprs[rn]) & 0xffffffff;
			} else {
				gprs[rd] = gprs[rm] * gprs[rs] + gprs[rn];
			}
		};
	}
	constructMLAS(rd, rn, rs, rm, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			++cpu.cycles;
			cpu.mmu.waitMul(rs);
			if (gprs[rm] & 0xffff0000 && gprs[rs] & 0xffff0000) {
				// Our data type is a double--we'll lose bits if we do it all at once!
				var hi = ((gprs[rm] & 0xffff0000) * gprs[rs]) & 0xffffffff;
				var lo = ((gprs[rm] & 0x0000ffff) * gprs[rs]) & 0xffffffff;
				gprs[rd] = (hi + lo + gprs[rn]) & 0xffffffff;
			} else {
				gprs[rd] = gprs[rm] * gprs[rs] + gprs[rn];
			}
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
		};
	}
	constructMOV(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			gprs[rd] = cpu.shifterOperand;
		};
	}
	constructMOVS(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			gprs[rd] = cpu.shifterOperand;
			if (rd == cpu.PC && cpu.hasSPSR()) {
				cpu.unpackCPSR(cpu.spsr);
			} else {
				cpu.cpsrN = gprs[rd] >> 31;
				cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
				cpu.cpsrC = cpu.shifterCarryOut;
			}
		};
	}
	constructMRS(rd, r, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			if (r) {
				gprs[rd] = cpu.spsr;
			} else {
				gprs[rd] = cpu.packCPSR();
			}
		};
	}
	constructMSR(rm, r, instruction, immediate, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		var c = instruction & 0x00010000;
		//var x = instruction & 0x00020000;
		//var s = instruction & 0x00040000;
		var f = instruction & 0x00080000;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			var operand;
			if (instruction & 0x02000000) {
				operand = immediate;
			} else {
				operand = gprs[rm];
			}
			var mask =
				(c ? 0x000000ff : 0x00000000) |
				//(x ? 0x0000FF00 : 0x00000000) | // Irrelevant on ARMv4T
				//(s ? 0x00FF0000 : 0x00000000) | // Irrelevant on ARMv4T
				(f ? 0xff000000 : 0x00000000);

			if (r) {
				mask &= cpu.USER_MASK | cpu.PRIV_MASK | cpu.STATE_MASK;
				cpu.spsr = (cpu.spsr & ~mask) | (operand & mask);
			} else {
				if (mask & cpu.USER_MASK) {
					cpu.cpsrN = operand >> 31;
					cpu.cpsrZ = operand & 0x40000000;
					cpu.cpsrC = operand & 0x20000000;
					cpu.cpsrV = operand & 0x10000000;
				}
				if (cpu.mode != cpu.MODE_USER && mask & cpu.PRIV_MASK) {
					cpu.switchMode((operand & 0x0000000f) | 0x00000010);
					cpu.cpsrI = operand & 0x00000080;
					cpu.cpsrF = operand & 0x00000040;
				}
			}
		};
	}
	constructMUL(rd, rs, rm, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			cpu.mmu.waitMul(gprs[rs]);
			if (gprs[rm] & 0xffff0000 && gprs[rs] & 0xffff0000) {
				// Our data type is a double--we'll lose bits if we do it all at once!
				var hi = ((gprs[rm] & 0xffff0000) * gprs[rs]) | 0;
				var lo = ((gprs[rm] & 0x0000ffff) * gprs[rs]) | 0;
				gprs[rd] = hi + lo;
			} else {
				gprs[rd] = gprs[rm] * gprs[rs];
			}
		};
	}
	constructMULS(rd, rs, rm, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			cpu.mmu.waitMul(gprs[rs]);
			if (gprs[rm] & 0xffff0000 && gprs[rs] & 0xffff0000) {
				// Our data type is a double--we'll lose bits if we do it all at once!
				var hi = ((gprs[rm] & 0xffff0000) * gprs[rs]) | 0;
				var lo = ((gprs[rm] & 0x0000ffff) * gprs[rs]) | 0;
				gprs[rd] = hi + lo;
			} else {
				gprs[rd] = gprs[rm] * gprs[rs];
			}
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
		};
	}
	constructMVN(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			gprs[rd] = ~cpu.shifterOperand;
		};
	}
	constructMVNS(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			gprs[rd] = ~cpu.shifterOperand;
			if (rd == cpu.PC && cpu.hasSPSR()) {
				cpu.unpackCPSR(cpu.spsr);
			} else {
				cpu.cpsrN = gprs[rd] >> 31;
				cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
				cpu.cpsrC = cpu.shifterCarryOut;
			}
		};
	}
	constructORR(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			gprs[rd] = gprs[rn] | cpu.shifterOperand;
		};
	}
	constructORRS(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			gprs[rd] = gprs[rn] | cpu.shifterOperand;
			if (rd == cpu.PC && cpu.hasSPSR()) {
				cpu.unpackCPSR(cpu.spsr);
			} else {
				cpu.cpsrN = gprs[rd] >> 31;
				cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
				cpu.cpsrC = cpu.shifterCarryOut;
			}
		};
	}
	constructRSB(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			gprs[rd] = cpu.shifterOperand - gprs[rn];
		};
	}
	constructRSBS(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			var d = cpu.shifterOperand - gprs[rn];
			if (rd == cpu.PC && cpu.hasSPSR()) {
				cpu.unpackCPSR(cpu.spsr);
			} else {
				cpu.cpsrN = d >> 31;
				cpu.cpsrZ = !(d & 0xffffffff);
				cpu.cpsrC = cpu.shifterOperand >>> 0 >= gprs[rn] >>> 0;
				cpu.cpsrV =
					cpu.shifterOperand >> 31 != gprs[rn] >> 31 &&
					cpu.shifterOperand >> 31 != d >> 31;
			}
			gprs[rd] = d;
		};
	}
	constructRSC(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			var n = (gprs[rn] >>> 0) + !cpu.cpsrC;
			gprs[rd] = (cpu.shifterOperand >>> 0) - n;
		};
	}
	constructRSCS(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			var n = (gprs[rn] >>> 0) + !cpu.cpsrC;
			var d = (cpu.shifterOperand >>> 0) - n;
			if (rd == cpu.PC && cpu.hasSPSR()) {
				cpu.unpackCPSR(cpu.spsr);
			} else {
				cpu.cpsrN = d >> 31;
				cpu.cpsrZ = !(d & 0xffffffff);
				cpu.cpsrC = cpu.shifterOperand >>> 0 >= d >>> 0;
				cpu.cpsrV =
					cpu.shifterOperand >> 31 != n >> 31 &&
					cpu.shifterOperand >> 31 != d >> 31;
			}
			gprs[rd] = d;
		};
	}
	constructSBC(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			var shifterOperand = (cpu.shifterOperand >>> 0) + !cpu.cpsrC;
			gprs[rd] = (gprs[rn] >>> 0) - shifterOperand;
		};
	}
	constructSBCS(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			var shifterOperand = (cpu.shifterOperand >>> 0) + !cpu.cpsrC;
			var d = (gprs[rn] >>> 0) - shifterOperand;
			if (rd == cpu.PC && cpu.hasSPSR()) {
				cpu.unpackCPSR(cpu.spsr);
			} else {
				cpu.cpsrN = d >> 31;
				cpu.cpsrZ = !(d & 0xffffffff);
				cpu.cpsrC = gprs[rn] >>> 0 >= d >>> 0;
				cpu.cpsrV =
					gprs[rn] >> 31 != shifterOperand >> 31 &&
					gprs[rn] >> 31 != d >> 31;
			}
			gprs[rd] = d;
		};
	}
	constructSMLAL(rd, rn, rs, rm, condOp) {
		var cpu = this.cpu;
		var SHIFT_32 = 1 / 0x100000000;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			cpu.cycles += 2;
			cpu.mmu.waitMul(rs);
			var hi = (gprs[rm] & 0xffff0000) * gprs[rs];
			var lo = (gprs[rm] & 0x0000ffff) * gprs[rs];
			var carry = (gprs[rn] >>> 0) + hi + lo;
			gprs[rn] = carry;
			gprs[rd] += Math.floor(carry * SHIFT_32);
		};
	}
	constructSMLALS(rd, rn, rs, rm, condOp) {
		var cpu = this.cpu;
		var SHIFT_32 = 1 / 0x100000000;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			cpu.cycles += 2;
			cpu.mmu.waitMul(rs);
			var hi = (gprs[rm] & 0xffff0000) * gprs[rs];
			var lo = (gprs[rm] & 0x0000ffff) * gprs[rs];
			var carry = (gprs[rn] >>> 0) + hi + lo;
			gprs[rn] = carry;
			gprs[rd] += Math.floor(carry * SHIFT_32);
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff || gprs[rn] & 0xffffffff);
		};
	}
	constructSMULL(rd, rn, rs, rm, condOp) {
		var cpu = this.cpu;
		var SHIFT_32 = 1 / 0x100000000;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			++cpu.cycles;
			cpu.mmu.waitMul(gprs[rs]);
			var hi = ((gprs[rm] & 0xffff0000) >> 0) * (gprs[rs] >> 0);
			var lo = ((gprs[rm] & 0x0000ffff) >> 0) * (gprs[rs] >> 0);
			gprs[rn] = ((hi & 0xffffffff) + (lo & 0xffffffff)) & 0xffffffff;
			gprs[rd] = Math.floor(hi * SHIFT_32 + lo * SHIFT_32);
		};
	}
	constructSMULLS(rd, rn, rs, rm, condOp) {
		var cpu = this.cpu;
		var SHIFT_32 = 1 / 0x100000000;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			++cpu.cycles;
			cpu.mmu.waitMul(gprs[rs]);
			var hi = ((gprs[rm] & 0xffff0000) >> 0) * (gprs[rs] >> 0);
			var lo = ((gprs[rm] & 0x0000ffff) >> 0) * (gprs[rs] >> 0);
			gprs[rn] = ((hi & 0xffffffff) + (lo & 0xffffffff)) & 0xffffffff;
			gprs[rd] = Math.floor(hi * SHIFT_32 + lo * SHIFT_32);
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff || gprs[rn] & 0xffffffff);
		};
	}
	constructSTM(rs, address, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		var mmu = cpu.mmu;
		return function () {
			if (condOp && !condOp()) {
				mmu.waitPrefetch32(gprs[cpu.PC]);
				return;
			}
			mmu.wait32(gprs[cpu.PC]);
			var addr = address(true);
			var total = 0;
			var m, i;
			for (m = rs, i = 0; m; m >>= 1, ++i) {
				if (m & 1) {
					mmu.store32(addr, gprs[i]);
					addr += 4;
					++total;
				}
			}
			mmu.waitMulti32(addr, total);
		};
	}
	constructSTMS(rs, address, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		var mmu = cpu.mmu;
		return function () {
			if (condOp && !condOp()) {
				mmu.waitPrefetch32(gprs[cpu.PC]);
				return;
			}
			mmu.wait32(gprs[cpu.PC]);
			var mode = cpu.mode;
			var addr = address(true);
			var total = 0;
			var m, i;
			cpu.switchMode(cpu.MODE_SYSTEM);
			for (m = rs, i = 0; m; m >>= 1, ++i) {
				if (m & 1) {
					mmu.store32(addr, gprs[i]);
					addr += 4;
					++total;
				}
			}
			cpu.switchMode(mode);
			mmu.waitMulti32(addr, total);
		};
	}
	constructSTR(rd, address, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			if (condOp && !condOp()) {
				cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
				return;
			}
			var addr = address();
			cpu.mmu.store32(addr, gprs[rd]);
			cpu.mmu.wait32(addr);
			cpu.mmu.wait32(gprs[cpu.PC]);
		};
	}
	constructSTRB(rd, address, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			if (condOp && !condOp()) {
				cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
				return;
			}
			var addr = address();
			cpu.mmu.store8(addr, gprs[rd]);
			cpu.mmu.wait(addr);
			cpu.mmu.wait32(gprs[cpu.PC]);
		};
	}
	constructSTRH(rd, address, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			if (condOp && !condOp()) {
				cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
				return;
			}
			var addr = address();
			cpu.mmu.store16(addr, gprs[rd]);
			cpu.mmu.wait(addr);
			cpu.mmu.wait32(gprs[cpu.PC]);
		};
	}
	constructSUB(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			gprs[rd] = gprs[rn] - cpu.shifterOperand;
		};
	}
	constructSUBS(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			var d = gprs[rn] - cpu.shifterOperand;
			if (rd == cpu.PC && cpu.hasSPSR()) {
				cpu.unpackCPSR(cpu.spsr);
			} else {
				cpu.cpsrN = d >> 31;
			cpu.cpsrZ = !(d & 0xFFFFFFFF);
			cpu.cpsrC = (gprs[rn] >>> 0) >= (cpu.shifterOperand >>> 0);
			cpu.cpsrV = (gprs[rn] >> 31) != (cpu.shifterOperand >> 31) &&
						(gprs[rn] >> 31) != (d >> 31);
			}
			gprs[rd] = d;
		};
	}
	constructSWI(immediate, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			if (condOp && !condOp()) {
				cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
				return;
			}
			cpu.irq.swi32(immediate);
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
		};
	}
	constructSWP(rd, rn, rm, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			cpu.mmu.wait32(gprs[rn]);
			cpu.mmu.wait32(gprs[rn]);
			var d = cpu.mmu.load32(gprs[rn]);
			cpu.mmu.store32(gprs[rn], gprs[rm]);
			gprs[rd] = d;
			++cpu.cycles;
		};
	}
	constructSWPB(rd, rn, rm, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			cpu.mmu.wait(gprs[rn]);
			cpu.mmu.wait(gprs[rn]);
			var d = cpu.mmu.load8(gprs[rn]);
			cpu.mmu.store8(gprs[rn], gprs[rm]);
			gprs[rd] = d;
			++cpu.cycles;
		};
	}
	constructTEQ(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			var aluOut = gprs[rn] ^ cpu.shifterOperand;
			cpu.cpsrN = aluOut >> 31;
			cpu.cpsrZ = !(aluOut & 0xffffffff);
			cpu.cpsrC = cpu.shifterCarryOut;
		};
	}
	constructTST(rd, rn, shiftOp, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			shiftOp();
			var aluOut = gprs[rn] & cpu.shifterOperand;
			cpu.cpsrN = aluOut >> 31;
			cpu.cpsrZ = !(aluOut & 0xffffffff);
			cpu.cpsrC = cpu.shifterCarryOut;
		};
	}
	constructUMLAL(rd, rn, rs, rm, condOp) {
		var cpu = this.cpu;
		var SHIFT_32 = 1 / 0x100000000;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			cpu.cycles += 2;
			cpu.mmu.waitMul(rs);
			var hi = ((gprs[rm] & 0xffff0000) >>> 0) * (gprs[rs] >>> 0);
			var lo = (gprs[rm] & 0x0000ffff) * (gprs[rs] >>> 0);
			var carry = (gprs[rn] >>> 0) + hi + lo;
			gprs[rn] = carry;
			gprs[rd] += carry * SHIFT_32;
		};
	}
	constructUMLALS(rd, rn, rs, rm, condOp) {
		var cpu = this.cpu;
		var SHIFT_32 = 1 / 0x100000000;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			cpu.cycles += 2;
			cpu.mmu.waitMul(rs);
			var hi = ((gprs[rm] & 0xffff0000) >>> 0) * (gprs[rs] >>> 0);
			var lo = (gprs[rm] & 0x0000ffff) * (gprs[rs] >>> 0);
			var carry = (gprs[rn] >>> 0) + hi + lo;
			gprs[rn] = carry;
			gprs[rd] += carry * SHIFT_32;
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff || gprs[rn] & 0xffffffff);
		};
	}
	constructUMULL(rd, rn, rs, rm, condOp) {
		var cpu = this.cpu;
		var SHIFT_32 = 1 / 0x100000000;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			++cpu.cycles;
			cpu.mmu.waitMul(gprs[rs]);
			var hi = ((gprs[rm] & 0xffff0000) >>> 0) * (gprs[rs] >>> 0);
			var lo = ((gprs[rm] & 0x0000ffff) >>> 0) * (gprs[rs] >>> 0);
			gprs[rn] = ((hi & 0xffffffff) + (lo & 0xffffffff)) & 0xffffffff;
			gprs[rd] = (hi * SHIFT_32 + lo * SHIFT_32) >>> 0;
		};
	}
	constructUMULLS(rd, rn, rs, rm, condOp) {
		var cpu = this.cpu;
		var SHIFT_32 = 1 / 0x100000000;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch32(gprs[cpu.PC]);
			if (condOp && !condOp()) {
				return;
			}
			++cpu.cycles;
			cpu.mmu.waitMul(gprs[rs]);
			var hi = ((gprs[rm] & 0xffff0000) >>> 0) * (gprs[rs] >>> 0);
			var lo = ((gprs[rm] & 0x0000ffff) >>> 0) * (gprs[rs] >>> 0);
			gprs[rn] = ((hi & 0xffffffff) + (lo & 0xffffffff)) & 0xffffffff;
			gprs[rd] = (hi * SHIFT_32 + lo * SHIFT_32) >>> 0;
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff || gprs[rn] & 0xffffffff);
		};
	}
}

class ARMCoreThumb {
	constructor(cpu) {
		this.cpu = cpu;
	}
	constructADC(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var m = (gprs[rm] >>> 0) + !!cpu.cpsrC;
			var oldD = gprs[rd];
			var d = (oldD >>> 0) + m;
			var oldDn = oldD >> 31;
			var dn = d >> 31;
			var mn = m >> 31;
			cpu.cpsrN = dn;
			cpu.cpsrZ = !(d & 0xffffffff);
			cpu.cpsrC = d > 0xffffffff;
			cpu.cpsrV = oldDn == mn && oldDn != dn && mn != dn;
			gprs[rd] = d;
		};
	}
	constructADD1(rd, rn, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var d = (gprs[rn] >>> 0) + immediate;
			cpu.cpsrN = d >> 31;
			cpu.cpsrZ = !(d & 0xffffffff);
			cpu.cpsrC = d > 0xffffffff;
			cpu.cpsrV =
				!(gprs[rn] >> 31) && ((gprs[rn] >> 31) ^ d) >> 31 && d >> 31;
			gprs[rd] = d;
		};
	}
	constructADD2(rn, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var d = (gprs[rn] >>> 0) + immediate;
			cpu.cpsrN = d >> 31;
			cpu.cpsrZ = !(d & 0xffffffff);
			cpu.cpsrC = d > 0xffffffff;
			cpu.cpsrV =
				!(gprs[rn] >> 31) &&
				(gprs[rn] ^ d) >> 31 &&
				(immediate ^ d) >> 31;
			gprs[rn] = d;
		};
	}
	constructADD3(rd, rn, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var d = (gprs[rn] >>> 0) + (gprs[rm] >>> 0);
			cpu.cpsrN = d >> 31;
			cpu.cpsrZ = !(d & 0xffffffff);
			cpu.cpsrC = d > 0xffffffff;
			cpu.cpsrV =
				!((gprs[rn] ^ gprs[rm]) >> 31) &&
				(gprs[rn] ^ d) >> 31 &&
				(gprs[rm] ^ d) >> 31;
			gprs[rd] = d;
		};
	}
	constructADD4(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] += gprs[rm];
		};
	}
	constructADD5(rd, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] = (gprs[cpu.PC] & 0xfffffffc) + immediate;
		};
	}
	constructADD6(rd, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] = gprs[cpu.SP] + immediate;
		};
	}
	constructADD7(immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[cpu.SP] += immediate;
		};
	}
	constructAND(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] = gprs[rd] & gprs[rm];
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
		};
	}
	constructASR1(rd, rm, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			if (immediate == 0) {
				cpu.cpsrC = gprs[rm] >> 31;
				if (cpu.cpsrC) {
					gprs[rd] = 0xffffffff;
				} else {
					gprs[rd] = 0;
				}
			} else {
				cpu.cpsrC = gprs[rm] & (1 << (immediate - 1));
				gprs[rd] = gprs[rm] >> immediate;
			}
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
		};
	}
	constructASR2(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var rs = gprs[rm] & 0xff;
			if (rs) {
				if (rs < 32) {
					cpu.cpsrC = gprs[rd] & (1 << (rs - 1));
					gprs[rd] >>= rs;
				} else {
					cpu.cpsrC = gprs[rd] >> 31;
					if (cpu.cpsrC) {
						gprs[rd] = 0xffffffff;
					} else {
						gprs[rd] = 0;
					}
				}
			}
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
		};
	}
	constructB1(immediate, condOp) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			if (condOp()) {
				gprs[cpu.PC] += immediate;
			}
		};
	}
	constructB2(immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[cpu.PC] += immediate;
		};
	}
	constructBIC(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] = gprs[rd] & ~gprs[rm];
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
		};
	}
	constructBL1(immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[cpu.LR] = gprs[cpu.PC] + immediate;
		};
	}
	constructBL2(immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var pc = gprs[cpu.PC];
			gprs[cpu.PC] = gprs[cpu.LR] + (immediate << 1);
			gprs[cpu.LR] = pc - 1;
		};
	}
	constructBX(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			cpu.switchExecMode(gprs[rm] & 0x00000001);
			var misalign = 0;
			if (rm == 15) {
				misalign = gprs[rm] & 0x00000002;
			}
			gprs[cpu.PC] = gprs[rm] & (0xfffffffe - misalign);
		};
	}
	constructCMN(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var aluOut = (gprs[rd] >>> 0) + (gprs[rm] >>> 0);
			cpu.cpsrN = aluOut >> 31;
			cpu.cpsrZ = !(aluOut & 0xffffffff);
			cpu.cpsrC = aluOut > 0xffffffff;
			cpu.cpsrV =
				gprs[rd] >> 31 == gprs[rm] >> 31 &&
				gprs[rd] >> 31 != aluOut >> 31 &&
				gprs[rm] >> 31 != aluOut >> 31;
		};
	}
	constructCMP1(rn, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var aluOut = gprs[rn] - immediate;
			cpu.cpsrN = aluOut >> 31;
			cpu.cpsrZ = !(aluOut & 0xffffffff);
			cpu.cpsrC = gprs[rn] >>> 0 >= immediate;
			cpu.cpsrV = gprs[rn] >> 31 && (gprs[rn] ^ aluOut) >> 31;
		};
	}
	constructCMP2(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var d = gprs[rd];
			var m = gprs[rm];
			var aluOut = d - m;
			var an = aluOut >> 31;
			var dn = d >> 31;
			cpu.cpsrN = an;
			cpu.cpsrZ = !(aluOut & 0xffffffff);
			cpu.cpsrC = d >>> 0 >= m >>> 0;
			cpu.cpsrV = dn != m >> 31 && dn != an;
		};
	}
	constructCMP3(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var aluOut = gprs[rd] - gprs[rm];
			cpu.cpsrN = aluOut >> 31;
			cpu.cpsrZ = !(aluOut & 0xffffffff);
			cpu.cpsrC = gprs[rd] >>> 0 >= gprs[rm] >>> 0;
			cpu.cpsrV =
				(gprs[rd] ^ gprs[rm]) >> 31 && (gprs[rd] ^ aluOut) >> 31;
		};
	}
	constructEOR(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] = gprs[rd] ^ gprs[rm];
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
		};
	}
	constructLDMIA(rn, rs) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var address = gprs[rn];
			var total = 0;
			var m, i;
			for (m = 0x01, i = 0; i < 8; m <<= 1, ++i) {
				if (rs & m) {
					gprs[i] = cpu.mmu.load32(address);
					address += 4;
					++total;
				}
			}
			cpu.mmu.waitMulti32(address, total);
			if (!((1 << rn) & rs)) {
				gprs[rn] = address;
			}
		};
	}
	constructLDR1(rd, rn, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var n = gprs[rn] + immediate;
			gprs[rd] = cpu.mmu.load32(n);
			cpu.mmu.wait32(n);
			++cpu.cycles;
		};
	}
	constructLDR2(rd, rn, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] = cpu.mmu.load32(gprs[rn] + gprs[rm]);
			cpu.mmu.wait32(gprs[rn] + gprs[rm]);
			++cpu.cycles;
		};
	}
	constructLDR3(rd, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] = cpu.mmu.load32((gprs[cpu.PC] & 0xfffffffc) + immediate);
			cpu.mmu.wait32(gprs[cpu.PC]);
			++cpu.cycles;
		};
	}
	constructLDR4(rd, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] = cpu.mmu.load32(gprs[cpu.SP] + immediate);
			cpu.mmu.wait32(gprs[cpu.SP] + immediate);
			++cpu.cycles;
		};
	}
	constructLDRB1(rd, rn, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			var n = gprs[rn] + immediate;
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] = cpu.mmu.loadU8(n);
			cpu.mmu.wait(n);
			++cpu.cycles;
		};
	}
	constructLDRB2(rd, rn, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] = cpu.mmu.loadU8(gprs[rn] + gprs[rm]);
			cpu.mmu.wait(gprs[rn] + gprs[rm]);
			++cpu.cycles;
		};
	}
	constructLDRH1(rd, rn, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			var n = gprs[rn] + immediate;
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] = cpu.mmu.loadU16(n);
			cpu.mmu.wait(n);
			++cpu.cycles;
		};
	}
	constructLDRH2(rd, rn, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] = cpu.mmu.loadU16(gprs[rn] + gprs[rm]);
			cpu.mmu.wait(gprs[rn] + gprs[rm]);
			++cpu.cycles;
		};
	}
	constructLDRSB(rd, rn, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] = cpu.mmu.load8(gprs[rn] + gprs[rm]);
			cpu.mmu.wait(gprs[rn] + gprs[rm]);
			++cpu.cycles;
		};
	}
	constructLDRSH(rd, rn, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] = cpu.mmu.load16(gprs[rn] + gprs[rm]);
			cpu.mmu.wait(gprs[rn] + gprs[rm]);
			++cpu.cycles;
		};
	}
	constructLSL1(rd, rm, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			if (immediate == 0) {
				gprs[rd] = gprs[rm];
			} else {
				cpu.cpsrC = gprs[rm] & (1 << (32 - immediate));
				gprs[rd] = gprs[rm] << immediate;
			}
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
		};
	}
	constructLSL2(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var rs = gprs[rm] & 0xff;
			if (rs) {
				if (rs < 32) {
					cpu.cpsrC = gprs[rd] & (1 << (32 - rs));
					gprs[rd] <<= rs;
				} else {
					if (rs > 32) {
						cpu.cpsrC = 0;
					} else {
						cpu.cpsrC = gprs[rd] & 0x00000001;
					}
					gprs[rd] = 0;
				}
			}
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
		};
	}
	constructLSR1(rd, rm, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			if (immediate == 0) {
				cpu.cpsrC = gprs[rm] >> 31;
				gprs[rd] = 0;
			} else {
				cpu.cpsrC = gprs[rm] & (1 << (immediate - 1));
				gprs[rd] = gprs[rm] >>> immediate;
			}
			cpu.cpsrN = 0;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
		};
	}
	constructLSR2(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var rs = gprs[rm] & 0xff;
			if (rs) {
				if (rs < 32) {
					cpu.cpsrC = gprs[rd] & (1 << (rs - 1));
					gprs[rd] >>>= rs;
				} else {
					if (rs > 32) {
						cpu.cpsrC = 0;
					} else {
						cpu.cpsrC = gprs[rd] >> 31;
					}
					gprs[rd] = 0;
				}
			}
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
		};
	}
	constructMOV1(rn, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rn] = immediate;
			cpu.cpsrN = immediate >> 31;
			cpu.cpsrZ = !(immediate & 0xffffffff);
		};
	}
	constructMOV2(rd, rn, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var d = gprs[rn];
			cpu.cpsrN = d >> 31;
			cpu.cpsrZ = !(d & 0xffffffff);
			cpu.cpsrC = 0;
			cpu.cpsrV = 0;
			gprs[rd] = d;
		};
	}
	constructMOV3(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] = gprs[rm];
		};
	}
	constructMUL(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			cpu.mmu.waitMul(gprs[rm]);
			if (gprs[rm] & 0xffff0000 && gprs[rd] & 0xffff0000) {
				// Our data type is a double--we'll lose bits if we do it all at once!
				var hi = ((gprs[rd] & 0xffff0000) * gprs[rm]) & 0xffffffff;
				var lo = ((gprs[rd] & 0x0000ffff) * gprs[rm]) & 0xffffffff;
				gprs[rd] = (hi + lo) & 0xffffffff;
			} else {
				gprs[rd] *= gprs[rm];
			}
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
		};
	}
	constructMVN(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] = ~gprs[rm];
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
		};
	}
	constructNEG(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var d = -gprs[rm];
			cpu.cpsrN = d >> 31;
			cpu.cpsrZ = !(d & 0xffffffff);
			cpu.cpsrC = 0 >= d >>> 0;
			cpu.cpsrV = gprs[rm] >> 31 && d >> 31;
			gprs[rd] = d;
		};
	}
	constructORR(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			gprs[rd] = gprs[rd] | gprs[rm];
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
		};
	}
	constructPOP(rs, r) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			++cpu.cycles;
			var address = gprs[cpu.SP];
			var total = 0;
			var m, i;
			for (m = 0x01, i = 0; i < 8; m <<= 1, ++i) {
				if (rs & m) {
					cpu.mmu.waitSeq32(address);
					gprs[i] = cpu.mmu.load32(address);
					address += 4;
					++total;
				}
			}
			if (r) {
				gprs[cpu.PC] = cpu.mmu.load32(address) & 0xfffffffe;
				address += 4;
				++total;
			}
			cpu.mmu.waitMulti32(address, total);
			gprs[cpu.SP] = address;
		};
	}
	constructPUSH(rs, r) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			var address = gprs[cpu.SP] - 4;
			var total = 0;
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			if (r) {
				cpu.mmu.store32(address, gprs[cpu.LR]);
				address -= 4;
				++total;
			}
			var m, i;
			for (m = 0x80, i = 7; m; m >>= 1, --i) {
				if (rs & m) {
					cpu.mmu.store32(address, gprs[i]);
					address -= 4;
					++total;
					break;
				}
			}
			for (m >>= 1, --i; m; m >>= 1, --i) {
				if (rs & m) {
					cpu.mmu.store32(address, gprs[i]);
					address -= 4;
					++total;
				}
			}
			cpu.mmu.waitMulti32(address, total);
			gprs[cpu.SP] = address + 4;
		};
	}
	constructROR(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var rs = gprs[rm] & 0xff;
			if (rs) {
				var r4 = rs & 0x1f;
				if (r4 > 0) {
					cpu.cpsrC = gprs[rd] & (1 << (r4 - 1));
					gprs[rd] = (gprs[rd] >>> r4) | (gprs[rd] << (32 - r4));
				} else {
					cpu.cpsrC = gprs[rd] >> 31;
				}
			}
			cpu.cpsrN = gprs[rd] >> 31;
			cpu.cpsrZ = !(gprs[rd] & 0xffffffff);
		};
	}
	constructSBC(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var m = (gprs[rm] >>> 0) + !cpu.cpsrC;
			var d = (gprs[rd] >>> 0) - m;
			cpu.cpsrN = d >> 31;
			cpu.cpsrZ = !(d & 0xffffffff);
			cpu.cpsrC = gprs[rd] >>> 0 >= d >>> 0;
			cpu.cpsrV = (gprs[rd] ^ m) >> 31 && (gprs[rd] ^ d) >> 31;
			gprs[rd] = d;
		};
	}
	constructSTMIA(rn, rs) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.wait(gprs[cpu.PC]);
			var address = gprs[rn];
			var total = 0;
			var m, i;
			for (m = 0x01, i = 0; i < 8; m <<= 1, ++i) {
				if (rs & m) {
					cpu.mmu.store32(address, gprs[i]);
					address += 4;
					++total;
					break;
				}
			}
			for (m <<= 1, ++i; i < 8; m <<= 1, ++i) {
				if (rs & m) {
					cpu.mmu.store32(address, gprs[i]);
					address += 4;
					++total;
				}
			}
			cpu.mmu.waitMulti32(address, total);
			gprs[rn] = address;
		};
	}
	constructSTR1(rd, rn, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			var n = gprs[rn] + immediate;
			cpu.mmu.store32(n, gprs[rd]);
			cpu.mmu.wait(gprs[cpu.PC]);
			cpu.mmu.wait32(n);
		};
	}
	constructSTR2(rd, rn, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.store32(gprs[rn] + gprs[rm], gprs[rd]);
			cpu.mmu.wait(gprs[cpu.PC]);
			cpu.mmu.wait32(gprs[rn] + gprs[rm]);
		};
	}
	constructSTR3(rd, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.store32(gprs[cpu.SP] + immediate, gprs[rd]);
			cpu.mmu.wait(gprs[cpu.PC]);
			cpu.mmu.wait32(gprs[cpu.SP] + immediate);
		};
	}
	constructSTRB1(rd, rn, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			var n = gprs[rn] + immediate;
			cpu.mmu.store8(n, gprs[rd]);
			cpu.mmu.wait(gprs[cpu.PC]);
			cpu.mmu.wait(n);
		};
	}
	constructSTRB2(rd, rn, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.store8(gprs[rn] + gprs[rm], gprs[rd]);
			cpu.mmu.wait(gprs[cpu.PC]);
			cpu.mmu.wait(gprs[rn] + gprs[rm]);
		};
	}
	constructSTRH1(rd, rn, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			var n = gprs[rn] + immediate;
			cpu.mmu.store16(n, gprs[rd]);
			cpu.mmu.wait(gprs[cpu.PC]);
			cpu.mmu.wait(n);
		};
	}
	constructSTRH2(rd, rn, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.store16(gprs[rn] + gprs[rm], gprs[rd]);
			cpu.mmu.wait(gprs[cpu.PC]);
			cpu.mmu.wait(gprs[rn] + gprs[rm]);
		};
	}
	constructSUB1(rd, rn, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var d = gprs[rn] - immediate;
			cpu.cpsrN = d >> 31;
			cpu.cpsrZ = !(d & 0xffffffff);
			cpu.cpsrC = gprs[rn] >>> 0 >= immediate;
			cpu.cpsrV = gprs[rn] >> 31 && (gprs[rn] ^ d) >> 31;
			gprs[rd] = d;
		};
	}
	constructSUB2(rn, immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var d = gprs[rn] - immediate;
			cpu.cpsrN = d >> 31;
			cpu.cpsrZ = !(d & 0xffffffff);
			cpu.cpsrC = gprs[rn] >>> 0 >= immediate;
			cpu.cpsrV = gprs[rn] >> 31 && (gprs[rn] ^ d) >> 31;
			gprs[rn] = d;
		};
	}
	constructSUB3(rd, rn, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var d = gprs[rn] - gprs[rm];
			cpu.cpsrN = d >> 31;
			cpu.cpsrZ = !(d & 0xffffffff);
			cpu.cpsrC = gprs[rn] >>> 0 >= gprs[rm] >>> 0;
			cpu.cpsrV =
				gprs[rn] >> 31 != gprs[rm] >> 31 && gprs[rn] >> 31 != d >> 31;
			gprs[rd] = d;
		};
	}
	constructSWI(immediate) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.irq.swi(immediate);
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
		};
	}
	constructTST(rd, rm) {
		var cpu = this.cpu;
		var gprs = cpu.gprs;
		return function () {
			cpu.mmu.waitPrefetch(gprs[cpu.PC]);
			var aluOut = gprs[rd] & gprs[rm];
			cpu.cpsrN = aluOut >> 31;
			cpu.cpsrZ = !(aluOut & 0xffffffff);
		};
	}
}

class MemoryView {
	constructor(memory, offset) {
		// this.inherit();
		this.buffer = memory;
		this.view = new DataView(
			this.buffer,
			typeof offset === "number" ? offset : 0
		);
		this.mask = memory.byteLength - 1;
		this.resetMask();
	}
	resetMask() {
		this.mask8 = this.mask & 0xffffffff;
		this.mask16 = this.mask & 0xfffffffe;
		this.mask32 = this.mask & 0xfffffffc;
	}
	load8(offset) {
		return this.view.getInt8(offset & this.mask8);
	}
	load16(offset) {
		// Unaligned 16-bit loads are unpredictable...let's just pretend they work
		return this.view.getInt16(offset & this.mask, true);
	}
	loadU8(offset) {
		return this.view.getUint8(offset & this.mask8);
	}
	loadU16(offset) {
		// Unaligned 16-bit loads are unpredictable...let's just pretend they work
		return this.view.getUint16(offset & this.mask, true);
	}
	load32(offset) {
		// Unaligned 32-bit loads are "rotated" so they make some semblance of sense
		var rotate = (offset & 3) << 3;
		var mem = this.view.getInt32(offset & this.mask32, true);
		return (mem >>> rotate) | (mem << (32 - rotate));
	}
	store8(offset, value) {
		this.view.setInt8(offset & this.mask8, value);
	}
	store16(offset, value) {
		this.view.setInt16(offset & this.mask16, value, true);
	}
	store32(offset, value) {
		this.view.setInt32(offset & this.mask32, value, true);
	}
	invalidatePage(address) {}
	replaceData(memory, offset) {
		this.buffer = memory;
		this.view = new DataView(
			this.buffer,
			typeof offset === "number" ? offset : 0
		);
		if (this.icache) {
			this.icache = new Array(this.icache.length);
		}
	}
}
class MemoryBlock extends MemoryView {
	constructor(size, cacheBits) {
		super(new ArrayBuffer(size));
		this.ICACHE_PAGE_BITS = cacheBits;
		this.PAGE_MASK = (2 << this.ICACHE_PAGE_BITS) - 1;
		this.icache = new Array(size >> (this.ICACHE_PAGE_BITS + 1));
	}
	invalidatePage(address) {
		var page = this.icache[(address & this.mask) >> this.ICACHE_PAGE_BITS];
		if (page) {
			page.invalid = true;
		}
	}
}

class ROMView extends MemoryView {
	constructor(rom, offset) {
		super(rom, offset);
		this.ICACHE_PAGE_BITS = 10;
		this.PAGE_MASK = (2 << this.ICACHE_PAGE_BITS) - 1;
		this.icache = new Array(rom.byteLength >> (this.ICACHE_PAGE_BITS + 1));
		this.mask = 0x01ffffff;
		this.resetMask();
	}
	store8(offset, value) {}
	store16(offset, value) {
		if (offset < 0xca && offset >= 0xc4) {
			if (!this.gpio) {
				this.gpio = this.mmu.allocGPIO(this);
			}
			this.gpio.store16(offset, value);
		}
	}
	store32(offset, value) {
		if (offset < 0xca && offset >= 0xc4) {
			if (!this.gpio) {
				this.gpio = this.mmu.allocGPIO(this);
			}
			this.gpio.store32(offset, value);
		}
	}
}

class BIOSView extends MemoryView {
	constructor(rom, offset) {
		super(rom, offset);

		this.ICACHE_PAGE_BITS = 16;
		this.PAGE_MASK = (2 << this.ICACHE_PAGE_BITS) - 1;
		this.icache = new Array(1);
	}
	load8(offset) {
		if (offset >= this.buffer.byteLength) {
			return -1;
		}
		return this.view.getInt8(offset);
	}
	load16(offset) {
		if (offset >= this.buffer.byteLength) {
			return -1;
		}
		return this.view.getInt16(offset, true);
	}
	loadU8(offset) {
		if (offset >= this.buffer.byteLength) {
			return -1;
		}
		return this.view.getUint8(offset);
	}
	loadU16(offset) {
		if (offset >= this.buffer.byteLength) {
			return -1;
		}
		return this.view.getUint16(offset, true);
	}
	load32(offset) {
		if (offset >= this.buffer.byteLength) {
			return -1;
		}
		return this.view.getInt32(offset, true);
	}
	store8(offset, value) {}
	store16(offset, value) {}
	store32(offset, value) {}
}

class BadMemory {
	constructor(mmu, cpu) {
		// this.inherit();
		this.cpu = cpu;
		this.mmu = mmu;
	}
	load8(offset) {
		return this.mmu.load8(
			this.cpu.gprs[this.cpu.PC] -
				this.cpu.instructionWidth +
				(offset & 0x3)
		);
	}
	load16(offset) {
		return this.mmu.load16(
			this.cpu.gprs[this.cpu.PC] -
				this.cpu.instructionWidth +
				(offset & 0x2)
		);
	}
	loadU8(offset) {
		return this.mmu.loadU8(
			this.cpu.gprs[this.cpu.PC] -
				this.cpu.instructionWidth +
				(offset & 0x3)
		);
	}
	loadU16(offset) {
		return this.mmu.loadU16(
			this.cpu.gprs[this.cpu.PC] -
				this.cpu.instructionWidth +
				(offset & 0x2)
		);
	}
	load32(offset) {
		if (this.cpu.execMode == this.cpu.MODE_ARM) {
			return this.mmu.load32(
				this.cpu.gprs[this.cpu.gprs.PC] - this.cpu.instructionWidth
			);
		} else {
			var halfword = this.mmu.loadU16(
				this.cpu.gprs[this.cpu.PC] - this.cpu.instructionWidth
			);
			return halfword | (halfword << 16);
		}
	}
	store8(offset, value) {}
	store16(offset, value) {}
	store32(offset, value) {}
	invalidatePage(address) {}
}

class GameBoyAdvanceMMU {
	constructor() {
		// this.inherit();
		this.REGION_BIOS = 0x0;
		this.REGION_WORKING_RAM = 0x2;
		this.REGION_WORKING_IRAM = 0x3;
		this.REGION_IO = 0x4;
		this.REGION_PALETTE_RAM = 0x5;
		this.REGION_VRAM = 0x6;
		this.REGION_OAM = 0x7;
		this.REGION_CART0 = 0x8;
		this.REGION_CART1 = 0xa;
		this.REGION_CART2 = 0xc;
		this.REGION_CART_SRAM = 0xe;

		this.BASE_BIOS = 0x00000000;
		this.BASE_WORKING_RAM = 0x02000000;
		this.BASE_WORKING_IRAM = 0x03000000;
		this.BASE_IO = 0x04000000;
		this.BASE_PALETTE_RAM = 0x05000000;
		this.BASE_VRAM = 0x06000000;
		this.BASE_OAM = 0x07000000;
		this.BASE_CART0 = 0x08000000;
		this.BASE_CART1 = 0x0a000000;
		this.BASE_CART2 = 0x0c000000;
		this.BASE_CART_SRAM = 0x0e000000;

		this.BASE_MASK = 0x0f000000;
		this.BASE_OFFSET = 24;
		this.OFFSET_MASK = 0x00ffffff;

		this.SIZE_BIOS = 0x00004000;
		this.SIZE_WORKING_RAM = 0x00040000;
		this.SIZE_WORKING_IRAM = 0x00008000;
		this.SIZE_IO = 0x00000400;
		this.SIZE_PALETTE_RAM = 0x00000400;
		this.SIZE_VRAM = 0x00018000;
		this.SIZE_OAM = 0x00000400;
		this.SIZE_CART0 = 0x02000000;
		this.SIZE_CART1 = 0x02000000;
		this.SIZE_CART2 = 0x02000000;
		this.SIZE_CART_SRAM = 0x00008000;
		this.SIZE_CART_FLASH512 = 0x00010000;
		this.SIZE_CART_FLASH1M = 0x00020000;
		this.SIZE_CART_EEPROM = 0x00002000;

		this.DMA_TIMING_NOW = 0;
		this.DMA_TIMING_VBLANK = 1;
		this.DMA_TIMING_HBLANK = 2;
		this.DMA_TIMING_CUSTOM = 3;

		this.DMA_INCREMENT = 0;
		this.DMA_DECREMENT = 1;
		this.DMA_FIXED = 2;
		this.DMA_INCREMENT_RELOAD = 3;

		this.DMA_OFFSET = [1, -1, 0, 1];

		this.WAITSTATES = [0, 0, 2, 0, 0, 0, 0, 0, 4, 4, 4, 4, 4, 4, 4];
		this.WAITSTATES_32 = [0, 0, 5, 0, 0, 1, 0, 1, 7, 7, 9, 9, 13, 13, 8];
		this.WAITSTATES_SEQ = [0, 0, 2, 0, 0, 0, 0, 0, 2, 2, 4, 4, 8, 8, 4];
		this.WAITSTATES_SEQ_32 = [
			0,
			0,
			5,
			0,
			0,
			1,
			0,
			1,
			5,
			5,
			9,
			9,
			17,
			17,
			8
		];
		this.NULLWAIT = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];

		for (var i = 15; i < 256; ++i) {
			this.WAITSTATES[i] = 0;
			this.WAITSTATES_32[i] = 0;
			this.WAITSTATES_SEQ[i] = 0;
			this.WAITSTATES_SEQ_32[i] = 0;
			this.NULLWAIT[i] = 0;
		}

		this.ROM_WS = [4, 3, 2, 8];
		this.ROM_WS_SEQ = [
			[2, 1],
			[4, 1],
			[8, 1]
		];

		this.ICACHE_PAGE_BITS = 8;
		this.PAGE_MASK = (2 << this.ICACHE_PAGE_BITS) - 1;

		this.bios = null;
	}
	mmap(region, object) {
		this.memory[region] = object;
	}
	clear() {
		this.badMemory = new BadMemory(this, this.cpu);
		this.memory = [
			this.bios,
			this.badMemory,
			new MemoryBlock(this.SIZE_WORKING_RAM, 9),
			new MemoryBlock(this.SIZE_WORKING_IRAM, 7),
		null, // This is owned by GameBoyAdvanceIO
		null, // This is owned by GameBoyAdvancePalette
		null, // This is owned by GameBoyAdvanceVRAM
		null, // This is owned by GameBoyAdvanceOAM
			this.badMemory,
			this.badMemory,
			this.badMemory,
			this.badMemory,
			this.badMemory,
			this.badMemory,
			this.badMemory,
			this.badMemory // Unused
		];
		for (var i = 16; i < 256; ++i) {
			this.memory[i] = this.badMemory;
		}

		this.waitstates = this.WAITSTATES.slice(0);
		this.waitstatesSeq = this.WAITSTATES_SEQ.slice(0);
		this.waitstates32 = this.WAITSTATES_32.slice(0);
		this.waitstatesSeq32 = this.WAITSTATES_SEQ_32.slice(0);
		this.waitstatesPrefetch = this.WAITSTATES_SEQ.slice(0);
		this.waitstatesPrefetch32 = this.WAITSTATES_SEQ_32.slice(0);

		this.cart = null;
		this.save = null;

		this.DMA_REGISTER = [
			this.core.io.DMA0CNT_HI >> 1,
			this.core.io.DMA1CNT_HI >> 1,
			this.core.io.DMA2CNT_HI >> 1,
			this.core.io.DMA3CNT_HI >> 1
		];
	}
	freeze() {
		return {
			ram: Serializer.prefix(this.memory[this.REGION_WORKING_RAM].buffer),
			iram: Serializer.prefix(
				this.memory[this.REGION_WORKING_IRAM].buffer
			)
		};
	}
	defrost(frost) {
		this.memory[this.REGION_WORKING_RAM].replaceData(frost.ram);
		this.memory[this.REGION_WORKING_IRAM].replaceData(frost.iram);
	}
	loadBios(bios, real) {
		this.bios = new BIOSView(bios);
		this.bios.real = !!real;
	}
	loadRom(rom, process) {
		var cart = {
			title: null,
			code: null,
			maker: null,
			memory: rom,
			saveType: null
		};

		var lo = new ROMView(rom);
		if (lo.view.getUint8(0xb2) != 0x96) {
			// Not a valid ROM
			return null;
		}
		lo.mmu = this; // Needed for GPIO
		this.memory[this.REGION_CART0] = lo;
		this.memory[this.REGION_CART1] = lo;
		this.memory[this.REGION_CART2] = lo;

		if (rom.byteLength > 0x01000000) {
			var hi = new ROMView(rom, 0x01000000);
			this.memory[this.REGION_CART0 + 1] = hi;
			this.memory[this.REGION_CART1 + 1] = hi;
			this.memory[this.REGION_CART2 + 1] = hi;
		}

		if (process) {
			var name = "";
			for (var i = 0; i < 12; ++i) {
				var c = lo.loadU8(i + 0xa0);
				if (!c) {
					break;
				}
				name += String.fromCharCode(c);
			}
			cart.title = name;

			var code = "";
			for (var i = 0; i < 4; ++i) {
				var c = lo.loadU8(i + 0xac);
				if (!c) {
					break;
				}
				code += String.fromCharCode(c);
			}
			cart.code = code;

			var maker = "";
			for (var i = 0; i < 2; ++i) {
				var c = lo.loadU8(i + 0xb0);
				if (!c) {
					break;
				}
				maker += String.fromCharCode(c);
			}
			cart.maker = maker;

			// Find savedata type
			var state = "";
			var next;
			var terminal = false;
			for (var i = 0xe4; i < rom.byteLength && !terminal; ++i) {
				next = String.fromCharCode(lo.loadU8(i));
				state += next;
				switch (state) {
					case "F":
					case "FL":
					case "FLA":
					case "FLAS":
					case "FLASH":
					case "FLASH_":
					case "FLASH5":
					case "FLASH51":
					case "FLASH512":
					case "FLASH512_":
					case "FLASH1":
					case "FLASH1M":
					case "FLASH1M_":
					case "S":
					case "SR":
					case "SRA":
					case "SRAM":
					case "SRAM_":
					case "E":
					case "EE":
					case "EEP":
					case "EEPR":
					case "EEPRO":
					case "EEPROM":
					case "EEPROM_":
						break;
					case "FLASH_V":
					case "FLASH512_V":
					case "FLASH1M_V":
					case "SRAM_V":
					case "EEPROM_V":
						terminal = true;
						break;
					default:
						state = next;
						break;
				}
			}
			if (terminal) {
				cart.saveType = state;
				switch (state) {
					case "FLASH_V":
					case "FLASH512_V":
						this.save = this.memory[
							this.REGION_CART_SRAM
						] = new FlashSavedata(this.SIZE_CART_FLASH512);
						break;
					case "FLASH1M_V":
						this.save = this.memory[
							this.REGION_CART_SRAM
						] = new FlashSavedata(this.SIZE_CART_FLASH1M);
						break;
					case "SRAM_V":
						this.save = this.memory[
							this.REGION_CART_SRAM
						] = new SRAMSavedata(this.SIZE_CART_SRAM);
						break;
					case "EEPROM_V":
						this.save = this.memory[
							this.REGION_CART2 + 1
						] = new EEPROMSavedata(this.SIZE_CART_EEPROM, this);
						break;
				}
			}
			if (!this.save) {
				// Assume we have SRAM
				this.save = this.memory[
					this.REGION_CART_SRAM
				] = new SRAMSavedata(this.SIZE_CART_SRAM);
			}
		}

		this.cart = cart;
		return cart;
	}
	loadSavedata(save) {
		this.save.replaceData(save);
	}
	load8(offset) {
		return this.memory[offset >>> this.BASE_OFFSET].load8(
			offset & 0x00ffffff
		);
	}
	load16(offset) {
		return this.memory[offset >>> this.BASE_OFFSET].load16(
			offset & 0x00ffffff
		);
	}
	load32(offset) {
		return this.memory[offset >>> this.BASE_OFFSET].load32(
			offset & 0x00ffffff
		);
	}
	loadU8(offset) {
		return this.memory[offset >>> this.BASE_OFFSET].loadU8(
			offset & 0x00ffffff
		);
	}
	loadU16(offset) {
		return this.memory[offset >>> this.BASE_OFFSET].loadU16(
			offset & 0x00ffffff
		);
	}
	store8(offset, value) {
		var maskedOffset = offset & 0x00ffffff;
		var memory = this.memory[offset >>> this.BASE_OFFSET];
		memory.store8(maskedOffset, value);
		memory.invalidatePage(maskedOffset);
	}
	store16(offset, value) {
		var maskedOffset = offset & 0x00fffffe;
		var memory = this.memory[offset >>> this.BASE_OFFSET];
		memory.store16(maskedOffset, value);
		memory.invalidatePage(maskedOffset);
	}
	store32(offset, value) {
		var maskedOffset = offset & 0x00fffffc;
		var memory = this.memory[offset >>> this.BASE_OFFSET];
		memory.store32(maskedOffset, value);
		memory.invalidatePage(maskedOffset);
		memory.invalidatePage(maskedOffset + 2);
	}
	waitPrefetch(memory) {
		this.cpu.cycles +=
			1 + this.waitstatesPrefetch[memory >>> this.BASE_OFFSET];
	}
	waitPrefetch32(memory) {
		this.cpu.cycles +=
			1 + this.waitstatesPrefetch32[memory >>> this.BASE_OFFSET];
	}
	wait(memory) {
		this.cpu.cycles += 1 + this.waitstates[memory >>> this.BASE_OFFSET];
	}
	wait32(memory) {
		this.cpu.cycles += 1 + this.waitstates32[memory >>> this.BASE_OFFSET];
	}
	waitSeq(memory) {
		this.cpu.cycles += 1 + this.waitstatesSeq[memory >>> this.BASE_OFFSET];
	}
	waitSeq32(memory) {
		this.cpu.cycles +=
			1 + this.waitstatesSeq32[memory >>> this.BASE_OFFSET];
	}
	waitMul(rs) {
		if (rs & (0xffffff00 == 0xffffff00) || !(rs & 0xffffff00)) {
			this.cpu.cycles += 1;
		} else if (rs & (0xffff0000 == 0xffff0000) || !(rs & 0xffff0000)) {
			this.cpu.cycles += 2;
		} else if (rs & (0xff000000 == 0xff000000) || !(rs & 0xff000000)) {
			this.cpu.cycles += 3;
		} else {
			this.cpu.cycles += 4;
		}
	}
	waitMulti32(memory, seq) {
		this.cpu.cycles += 1 + this.waitstates32[memory >>> this.BASE_OFFSET];
		this.cpu.cycles +=
			(1 + this.waitstatesSeq32[memory >>> this.BASE_OFFSET]) * (seq - 1);
	}
	addressToPage(region, address) {
		return address >> this.memory[region].ICACHE_PAGE_BITS;
	}
	accessPage(region, pageId) {
		var memory = this.memory[region];
		var page = memory.icache[pageId];
		if (!page || page.invalid) {
			page = {
				thumb: new Array(1 << memory.ICACHE_PAGE_BITS),
				arm: new Array(1 << (memory.ICACHE_PAGE_BITS - 1)),
				invalid: false
			};
			memory.icache[pageId] = page;
		}
		return page;
	}
	scheduleDma(number, info) {
		switch (info.timing) {
			case this.DMA_TIMING_NOW:
				this.serviceDma(number, info);
				break;
			case this.DMA_TIMING_HBLANK:
				// Handled implicitly
				break;
			case this.DMA_TIMING_VBLANK:
				// Handled implicitly
				break;
			case this.DMA_TIMING_CUSTOM:
				switch (number) {
					case 0:
						this.core.WARN("Discarding invalid DMA0 scheduling");
						break;
					case 1:
					case 2:
						this.cpu.irq.audio.scheduleFIFODma(number, info);
						break;
					case 3:
						this.cpu.irq.video.scheduleVCaptureDma(dma, info);
						break;
				}
		}
	}
	runHblankDmas() {
		var dma;
		for (var i = 0; i < this.cpu.irq.dma.length; ++i) {
			dma = this.cpu.irq.dma[i];
			if (dma.enable && dma.timing == this.DMA_TIMING_HBLANK) {
				this.serviceDma(i, dma);
			}
		}
	}
	runVblankDmas() {
		var dma;
		for (var i = 0; i < this.cpu.irq.dma.length; ++i) {
			dma = this.cpu.irq.dma[i];
			if (dma.enable && dma.timing == this.DMA_TIMING_VBLANK) {
				this.serviceDma(i, dma);
			}
		}
	}
	serviceDma(number, info) {
		if (!info.enable) {
			// There was a DMA scheduled that got canceled
			return;
		}

		var width = info.width;
		var sourceOffset = this.DMA_OFFSET[info.srcControl] * width;
		var destOffset = this.DMA_OFFSET[info.dstControl] * width;
		var wordsRemaining = info.nextCount;
		var source = info.nextSource & this.OFFSET_MASK;
		var dest = info.nextDest & this.OFFSET_MASK;
		var sourceRegion = info.nextSource >>> this.BASE_OFFSET;
		var destRegion = info.nextDest >>> this.BASE_OFFSET;
		var sourceBlock = this.memory[sourceRegion];
		var destBlock = this.memory[destRegion];
		var sourceView = null;
		var destView = null;
		var sourceMask = 0xffffffff;
		var destMask = 0xffffffff;
		var word;

		if (destBlock.ICACHE_PAGE_BITS) {
			var endPage =
				(dest + wordsRemaining * width) >> destBlock.ICACHE_PAGE_BITS;
			for (
				var i = dest >> destBlock.ICACHE_PAGE_BITS;
				i <= endPage;
				++i
			) {
				destBlock.invalidatePage(i << destBlock.ICACHE_PAGE_BITS);
			}
		}

		if (
			destRegion == this.REGION_WORKING_RAM ||
			destRegion == this.REGION_WORKING_IRAM
		) {
			destView = destBlock.view;
			destMask = destBlock.mask;
		}

		if (
			sourceRegion == this.REGION_WORKING_RAM ||
			sourceRegion == this.REGION_WORKING_IRAM ||
			sourceRegion == this.REGION_CART0 ||
			sourceRegion == this.REGION_CART1
		) {
			sourceView = sourceBlock.view;
			sourceMask = sourceBlock.mask;
		}

		if (sourceBlock && destBlock) {
			if (sourceView && destView) {
				if (width == 4) {
					source &= 0xfffffffc;
					dest &= 0xfffffffc;
					while (wordsRemaining--) {
						word = sourceView.getInt32(source & sourceMask);
						destView.setInt32(dest & destMask, word);
						source += sourceOffset;
						dest += destOffset;
					}
				} else {
					while (wordsRemaining--) {
						word = sourceView.getUint16(source & sourceMask);
						destView.setUint16(dest & destMask, word);
						source += sourceOffset;
						dest += destOffset;
					}
				}
			} else if (sourceView) {
				if (width == 4) {
					source &= 0xfffffffc;
					dest &= 0xfffffffc;
					while (wordsRemaining--) {
						word = sourceView.getInt32(source & sourceMask, true);
						destBlock.store32(dest, word);
						source += sourceOffset;
						dest += destOffset;
					}
				} else {
					while (wordsRemaining--) {
						word = sourceView.getUint16(source & sourceMask, true);
						destBlock.store16(dest, word);
						source += sourceOffset;
						dest += destOffset;
					}
				}
			} else {
				if (width == 4) {
					source &= 0xfffffffc;
					dest &= 0xfffffffc;
					while (wordsRemaining--) {
						word = sourceBlock.load32(source);
						destBlock.store32(dest, word);
						source += sourceOffset;
						dest += destOffset;
					}
				} else {
					while (wordsRemaining--) {
						word = sourceBlock.loadU16(source);
						destBlock.store16(dest, word);
						source += sourceOffset;
						dest += destOffset;
					}
				}
			}
		} else {
			this.core.WARN("Invalid DMA");
		}

		if (info.doIrq) {
			info.nextIRQ = this.cpu.cycles + 2;
			info.nextIRQ +=
				width == 4
					? this.waitstates32[sourceRegion] +
					  this.waitstates32[destRegion]
					: this.waitstates[sourceRegion] +
					  this.waitstates[destRegion];
			info.nextIRQ +=
				(info.count - 1) *
				(width == 4
					? this.waitstatesSeq32[sourceRegion] +
					  this.waitstatesSeq32[destRegion]
					: this.waitstatesSeq[sourceRegion] +
					  this.waitstatesSeq[destRegion]);
		}

		info.nextSource = source | (sourceRegion << this.BASE_OFFSET);
		info.nextDest = dest | (destRegion << this.BASE_OFFSET);
		info.nextCount = wordsRemaining;

		if (!info.repeat) {
			info.enable = false;

			// Clear the enable bit in memory
			var io = this.memory[this.REGION_IO];
			io.registers[this.DMA_REGISTER[number]] &= 0x7fe0;
		} else {
			info.nextCount = info.count;
			if (info.dstControl == this.DMA_INCREMENT_RELOAD) {
				info.nextDest = info.dest;
			}
			this.scheduleDma(number, info);
		}
	}
	adjustTimings(word) {
		var sram = word & 0x0003;
		var ws0 = (word & 0x000c) >> 2;
		var ws0seq = (word & 0x0010) >> 4;
		var ws1 = (word & 0x0060) >> 5;
		var ws1seq = (word & 0x0080) >> 7;
		var ws2 = (word & 0x0300) >> 8;
		var ws2seq = (word & 0x0400) >> 10;
		var prefetch = word & 0x4000;

		this.waitstates[this.REGION_CART_SRAM] = this.ROM_WS[sram];
		this.waitstatesSeq[this.REGION_CART_SRAM] = this.ROM_WS[sram];
		this.waitstates32[this.REGION_CART_SRAM] = this.ROM_WS[sram];
		this.waitstatesSeq32[this.REGION_CART_SRAM] = this.ROM_WS[sram];

		this.waitstates[this.REGION_CART0] = this.waitstates[
			this.REGION_CART0 + 1
		] = this.ROM_WS[ws0];
		this.waitstates[this.REGION_CART1] = this.waitstates[
			this.REGION_CART1 + 1
		] = this.ROM_WS[ws1];
		this.waitstates[this.REGION_CART2] = this.waitstates[
			this.REGION_CART2 + 1
		] = this.ROM_WS[ws2];

		this.waitstatesSeq[this.REGION_CART0] = this.waitstatesSeq[
			this.REGION_CART0 + 1
		] = this.ROM_WS_SEQ[0][ws0seq];
		this.waitstatesSeq[this.REGION_CART1] = this.waitstatesSeq[
			this.REGION_CART1 + 1
		] = this.ROM_WS_SEQ[1][ws1seq];
		this.waitstatesSeq[this.REGION_CART2] = this.waitstatesSeq[
			this.REGION_CART2 + 1
		] = this.ROM_WS_SEQ[2][ws2seq];

		this.waitstates32[this.REGION_CART0] = this.waitstates32[
			this.REGION_CART0 + 1
		] =
			this.waitstates[this.REGION_CART0] +
			1 +
			this.waitstatesSeq[this.REGION_CART0];
		this.waitstates32[this.REGION_CART1] = this.waitstates32[
			this.REGION_CART1 + 1
		] =
			this.waitstates[this.REGION_CART1] +
			1 +
			this.waitstatesSeq[this.REGION_CART1];
		this.waitstates32[this.REGION_CART2] = this.waitstates32[
			this.REGION_CART2 + 1
		] =
			this.waitstates[this.REGION_CART2] +
			1 +
			this.waitstatesSeq[this.REGION_CART2];

		this.waitstatesSeq32[this.REGION_CART0] = this.waitstatesSeq32[
			this.REGION_CART0 + 1
		] = 2 * this.waitstatesSeq[this.REGION_CART0] + 1;
		this.waitstatesSeq32[this.REGION_CART1] = this.waitstatesSeq32[
			this.REGION_CART1 + 1
		] = 2 * this.waitstatesSeq[this.REGION_CART1] + 1;
		this.waitstatesSeq32[this.REGION_CART2] = this.waitstatesSeq32[
			this.REGION_CART2 + 1
		] = 2 * this.waitstatesSeq[this.REGION_CART2] + 1;

		if (prefetch) {
			this.waitstatesPrefetch[
				this.REGION_CART0
			] = this.waitstatesPrefetch[this.REGION_CART0 + 1] = 0;
			this.waitstatesPrefetch[
				this.REGION_CART1
			] = this.waitstatesPrefetch[this.REGION_CART1 + 1] = 0;
			this.waitstatesPrefetch[
				this.REGION_CART2
			] = this.waitstatesPrefetch[this.REGION_CART2 + 1] = 0;

			this.waitstatesPrefetch32[
				this.REGION_CART0
			] = this.waitstatesPrefetch32[this.REGION_CART0 + 1] = 0;
			this.waitstatesPrefetch32[
				this.REGION_CART1
			] = this.waitstatesPrefetch32[this.REGION_CART1 + 1] = 0;
			this.waitstatesPrefetch32[
				this.REGION_CART2
			] = this.waitstatesPrefetch32[this.REGION_CART2 + 1] = 0;
		} else {
			this.waitstatesPrefetch[
				this.REGION_CART0
			] = this.waitstatesPrefetch[
				this.REGION_CART0 + 1
			] = this.waitstatesSeq[this.REGION_CART0];
			this.waitstatesPrefetch[
				this.REGION_CART1
			] = this.waitstatesPrefetch[
				this.REGION_CART1 + 1
			] = this.waitstatesSeq[this.REGION_CART1];
			this.waitstatesPrefetch[
				this.REGION_CART2
			] = this.waitstatesPrefetch[
				this.REGION_CART2 + 1
			] = this.waitstatesSeq[this.REGION_CART2];

			this.waitstatesPrefetch32[
				this.REGION_CART0
			] = this.waitstatesPrefetch32[
				this.REGION_CART0 + 1
			] = this.waitstatesSeq32[this.REGION_CART0];
			this.waitstatesPrefetch32[
				this.REGION_CART1
			] = this.waitstatesPrefetch32[
				this.REGION_CART1 + 1
			] = this.waitstatesSeq32[this.REGION_CART1];
			this.waitstatesPrefetch32[
				this.REGION_CART2
			] = this.waitstatesPrefetch32[
				this.REGION_CART2 + 1
			] = this.waitstatesSeq32[this.REGION_CART2];
		}
	}
	saveNeedsFlush() {
		return this.save.writePending;
	}
	flushSave() {
		this.save.writePending = false;
	}
	allocGPIO(rom) {
		return new GameBoyAdvanceGPIO(this.core, rom);
	}
}

class GameBoyAdvanceIO {
	constructor() {
		// Video
		this.DISPCNT = 0x000;
		this.GREENSWP = 0x002;
		this.DISPSTAT = 0x004;
		this.VCOUNT = 0x006;
		this.BG0CNT = 0x008;
		this.BG1CNT = 0x00a;
		this.BG2CNT = 0x00c;
		this.BG3CNT = 0x00e;
		this.BG0HOFS = 0x010;
		this.BG0VOFS = 0x012;
		this.BG1HOFS = 0x014;
		this.BG1VOFS = 0x016;
		this.BG2HOFS = 0x018;
		this.BG2VOFS = 0x01a;
		this.BG3HOFS = 0x01c;
		this.BG3VOFS = 0x01e;
		this.BG2PA = 0x020;
		this.BG2PB = 0x022;
		this.BG2PC = 0x024;
		this.BG2PD = 0x026;
		this.BG2X_LO = 0x028;
		this.BG2X_HI = 0x02a;
		this.BG2Y_LO = 0x02c;
		this.BG2Y_HI = 0x02e;
		this.BG3PA = 0x030;
		this.BG3PB = 0x032;
		this.BG3PC = 0x034;
		this.BG3PD = 0x036;
		this.BG3X_LO = 0x038;
		this.BG3X_HI = 0x03a;
		this.BG3Y_LO = 0x03c;
		this.BG3Y_HI = 0x03e;
		this.WIN0H = 0x040;
		this.WIN1H = 0x042;
		this.WIN0V = 0x044;
		this.WIN1V = 0x046;
		this.WININ = 0x048;
		this.WINOUT = 0x04a;
		this.MOSAIC = 0x04c;
		this.BLDCNT = 0x050;
		this.BLDALPHA = 0x052;
		this.BLDY = 0x054;

		// Sound
		this.SOUND1CNT_LO = 0x060;
		this.SOUND1CNT_HI = 0x062;
		this.SOUND1CNT_X = 0x064;
		this.SOUND2CNT_LO = 0x068;
		this.SOUND2CNT_HI = 0x06c;
		this.SOUND3CNT_LO = 0x070;
		this.SOUND3CNT_HI = 0x072;
		this.SOUND3CNT_X = 0x074;
		this.SOUND4CNT_LO = 0x078;
		this.SOUND4CNT_HI = 0x07c;
		this.SOUNDCNT_LO = 0x080;
		this.SOUNDCNT_HI = 0x082;
		this.SOUNDCNT_X = 0x084;
		this.SOUNDBIAS = 0x088;
		this.WAVE_RAM0_LO = 0x090;
		this.WAVE_RAM0_HI = 0x092;
		this.WAVE_RAM1_LO = 0x094;
		this.WAVE_RAM1_HI = 0x096;
		this.WAVE_RAM2_LO = 0x098;
		this.WAVE_RAM2_HI = 0x09a;
		this.WAVE_RAM3_LO = 0x09c;
		this.WAVE_RAM3_HI = 0x09e;
		this.FIFO_A_LO = 0x0a0;
		this.FIFO_A_HI = 0x0a2;
		this.FIFO_B_LO = 0x0a4;
		this.FIFO_B_HI = 0x0a6;

		// DMA
		this.DMA0SAD_LO = 0x0b0;
		this.DMA0SAD_HI = 0x0b2;
		this.DMA0DAD_LO = 0x0b4;
		this.DMA0DAD_HI = 0x0b6;
		this.DMA0CNT_LO = 0x0b8;
		this.DMA0CNT_HI = 0x0ba;
		this.DMA1SAD_LO = 0x0bc;
		this.DMA1SAD_HI = 0x0be;
		this.DMA1DAD_LO = 0x0c0;
		this.DMA1DAD_HI = 0x0c2;
		this.DMA1CNT_LO = 0x0c4;
		this.DMA1CNT_HI = 0x0c6;
		this.DMA2SAD_LO = 0x0c8;
		this.DMA2SAD_HI = 0x0ca;
		this.DMA2DAD_LO = 0x0cc;
		this.DMA2DAD_HI = 0x0ce;
		this.DMA2CNT_LO = 0x0d0;
		this.DMA2CNT_HI = 0x0d2;
		this.DMA3SAD_LO = 0x0d4;
		this.DMA3SAD_HI = 0x0d6;
		this.DMA3DAD_LO = 0x0d8;
		this.DMA3DAD_HI = 0x0da;
		this.DMA3CNT_LO = 0x0dc;
		this.DMA3CNT_HI = 0x0de;

		// Timers
		this.TM0CNT_LO = 0x100;
		this.TM0CNT_HI = 0x102;
		this.TM1CNT_LO = 0x104;
		this.TM1CNT_HI = 0x106;
		this.TM2CNT_LO = 0x108;
		this.TM2CNT_HI = 0x10a;
		this.TM3CNT_LO = 0x10c;
		this.TM3CNT_HI = 0x10e;

		// SIO (note: some of these are repeated)
		this.SIODATA32_LO = 0x120;
		this.SIOMULTI0 = 0x120;
		this.SIODATA32_HI = 0x122;
		this.SIOMULTI1 = 0x122;
		this.SIOMULTI2 = 0x124;
		this.SIOMULTI3 = 0x126;
		this.SIOCNT = 0x128;
		this.SIOMLT_SEND = 0x12a;
		this.SIODATA8 = 0x12a;
		this.RCNT = 0x134;
		this.JOYCNT = 0x140;
		this.JOY_RECV = 0x150;
		this.JOY_TRANS = 0x154;
		this.JOYSTAT = 0x158;

		// Keypad
		this.KEYINPUT = 0x130;
		this.KEYCNT = 0x132;

		// Interrupts, etc
		this.IE = 0x200;
		this.IF = 0x202;
		this.WAITCNT = 0x204;
		this.IME = 0x208;

		this.POSTFLG = 0x300;
		this.HALTCNT = 0x301;

		this.DEFAULT_DISPCNT = 0x0080;
		this.DEFAULT_SOUNDBIAS = 0x200;
		this.DEFAULT_BGPA = 1;
		this.DEFAULT_BGPD = 1;
		this.DEFAULT_RCNT = 0x8000;
	}
	clear() {
		this.registers = new Uint16Array(this.cpu.mmu.SIZE_IO);

		this.registers[this.DISPCNT >> 1] = this.DEFAULT_DISPCNT;
		this.registers[this.SOUNDBIAS >> 1] = this.DEFAULT_SOUNDBIAS;
		this.registers[this.BG2PA >> 1] = this.DEFAULT_BGPA;
		this.registers[this.BG2PD >> 1] = this.DEFAULT_BGPD;
		this.registers[this.BG3PA >> 1] = this.DEFAULT_BGPA;
		this.registers[this.BG3PD >> 1] = this.DEFAULT_BGPD;
		this.registers[this.RCNT >> 1] = this.DEFAULT_RCNT;
	}
	freeze() {
		return {
			registers: Serializer.prefix(this.registers.buffer)
		};
	}
	defrost(frost) {
		this.registers = new Uint16Array(frost.registers);
		// Video registers don't serialize themselves
		for (var i = 0; i <= this.BLDY; i += 2) {
			this.store16(i, this.registers[i >> 1]);
		}
	}
	load8(offset) {
		throw "Unimplmeneted unaligned I/O access";
	}
	load16(offset) {
		return (this.loadU16(offset) << 16) >> 16;
	}
	load32(offset) {
		offset &= 0xfffffffc;
		switch (offset) {
			case this.DMA0CNT_LO:
			case this.DMA1CNT_LO:
			case this.DMA2CNT_LO:
			case this.DMA3CNT_LO:
				return this.loadU16(offset | 2) << 16;
			case this.IME:
				return this.loadU16(offset) & 0xffff;
			case this.JOY_RECV:
			case this.JOY_TRANS:
				this.core.STUB(
					"Unimplemented JOY register read: 0x" + offset.toString(16)
				);
				return 0;
		}

		return this.loadU16(offset) | (this.loadU16(offset | 2) << 16);
	}
	loadU8(offset) {
		var odd = offset & 0x0001;
		var value = this.loadU16(offset & 0xfffe);
		return (value >>> (odd << 3)) & 0xff;
	}
	loadU16(offset) {
		switch (offset) {
			case this.DISPCNT:
			case this.BG0CNT:
			case this.BG1CNT:
			case this.BG2CNT:
			case this.BG3CNT:
			case this.WININ:
			case this.WINOUT:
			case this.SOUND1CNT_LO:
			case this.SOUND3CNT_LO:
			case this.SOUNDCNT_LO:
			case this.SOUNDCNT_HI:
			case this.SOUNDBIAS:
			case this.BLDCNT:
			case this.BLDALPHA:

			case this.TM0CNT_HI:
			case this.TM1CNT_HI:
			case this.TM2CNT_HI:
			case this.TM3CNT_HI:
			case this.DMA0CNT_HI:
			case this.DMA1CNT_HI:
			case this.DMA2CNT_HI:
			case this.DMA3CNT_HI:
			case this.RCNT:
			case this.WAITCNT:
			case this.IE:
			case this.IF:
			case this.IME:
			case this.POSTFLG:
				// Handled transparently by the written registers
				break;

			// Video
			case this.DISPSTAT:
				return (
					this.registers[offset >> 1] | this.video.readDisplayStat()
				);
			case this.VCOUNT:
				return this.video.vcount;

			// Sound
			case this.SOUND1CNT_HI:
			case this.SOUND2CNT_LO:
				return this.registers[offset >> 1] & 0xffc0;
			case this.SOUND1CNT_X:
			case this.SOUND2CNT_HI:
			case this.SOUND3CNT_X:
				return this.registers[offset >> 1] & 0x4000;
			case this.SOUND3CNT_HI:
				return this.registers[offset >> 1] & 0xe000;
			case this.SOUND4CNT_LO:
				return this.registers[offset >> 1] & 0xff00;
			case this.SOUND4CNT_HI:
				return this.registers[offset >> 1] & 0x40ff;
			case this.SOUNDCNT_X:
				this.core.STUB("Unimplemented sound register read: SOUNDCNT_X");
				return this.registers[offset >> 1] | 0x0000;

			// Timers
			case this.TM0CNT_LO:
				return this.cpu.irq.timerRead(0);
			case this.TM1CNT_LO:
				return this.cpu.irq.timerRead(1);
			case this.TM2CNT_LO:
				return this.cpu.irq.timerRead(2);
			case this.TM3CNT_LO:
				return this.cpu.irq.timerRead(3);

			// SIO
			case this.SIOCNT:
				return this.sio.readSIOCNT();

			case this.KEYINPUT:
				this.keypad.pollGamepads();
				return this.keypad.currentDown;
			case this.KEYCNT:
				this.core.STUB("Unimplemented I/O register read: KEYCNT");
				return 0;

			case this.BG0HOFS:
			case this.BG0VOFS:
			case this.BG1HOFS:
			case this.BG1VOFS:
			case this.BG2HOFS:
			case this.BG2VOFS:
			case this.BG3HOFS:
			case this.BG3VOFS:
			case this.BG2PA:
			case this.BG2PB:
			case this.BG2PC:
			case this.BG2PD:
			case this.BG3PA:
			case this.BG3PB:
			case this.BG3PC:
			case this.BG3PD:
			case this.BG2X_LO:
			case this.BG2X_HI:
			case this.BG2Y_LO:
			case this.BG2Y_HI:
			case this.BG3X_LO:
			case this.BG3X_HI:
			case this.BG3Y_LO:
			case this.BG3Y_HI:
			case this.WIN0H:
			case this.WIN1H:
			case this.WIN0V:
			case this.WIN1V:
			case this.BLDY:
			case this.DMA0SAD_LO:
			case this.DMA0SAD_HI:
			case this.DMA0DAD_LO:
			case this.DMA0DAD_HI:
			case this.DMA0CNT_LO:
			case this.DMA1SAD_LO:
			case this.DMA1SAD_HI:
			case this.DMA1DAD_LO:
			case this.DMA1DAD_HI:
			case this.DMA1CNT_LO:
			case this.DMA2SAD_LO:
			case this.DMA2SAD_HI:
			case this.DMA2DAD_LO:
			case this.DMA2DAD_HI:
			case this.DMA2CNT_LO:
			case this.DMA3SAD_LO:
			case this.DMA3SAD_HI:
			case this.DMA3DAD_LO:
			case this.DMA3DAD_HI:
			case this.DMA3CNT_LO:
			case this.FIFO_A_LO:
			case this.FIFO_A_HI:
			case this.FIFO_B_LO:
			case this.FIFO_B_HI:
				this.core.WARN(
					"Read for write-only register: 0x" + offset.toString(16)
				);
				return this.core.mmu.badMemory.loadU16(0);

			case this.MOSAIC:
				this.core.WARN(
					"Read for write-only register: 0x" + offset.toString(16)
				);
				return 0;

			case this.SIOMULTI0:
			case this.SIOMULTI1:
			case this.SIOMULTI2:
			case this.SIOMULTI3:
				return this.sio.read((offset - this.SIOMULTI0) >> 1);

			case this.SIODATA8:
				this.core.STUB(
					"Unimplemented SIO register read: 0x" + offset.toString(16)
				);
				return 0;
			case this.JOYCNT:
			case this.JOYSTAT:
				this.core.STUB(
					"Unimplemented JOY register read: 0x" + offset.toString(16)
				);
				return 0;

			default:
				this.core.WARN(
					"Bad I/O register read: 0x" + offset.toString(16)
				);
				return this.core.mmu.badMemory.loadU16(0);
		}
		return this.registers[offset >> 1];
	}
	store8(offset, value) {
		switch (offset) {
			case this.WININ:
				this.value & 0x3f;
				break;
			case this.WININ | 1:
				this.value & 0x3f;
				break;
			case this.WINOUT:
				this.value & 0x3f;
				break;
			case this.WINOUT | 1:
				this.value & 0x3f;
				break;
			case this.SOUND1CNT_LO:
			case this.SOUND1CNT_LO | 1:
			case this.SOUND1CNT_HI:
			case this.SOUND1CNT_HI | 1:
			case this.SOUND1CNT_X:
			case this.SOUND1CNT_X | 1:
			case this.SOUND2CNT_LO:
			case this.SOUND2CNT_LO | 1:
			case this.SOUND2CNT_HI:
			case this.SOUND2CNT_HI | 1:
			case this.SOUND3CNT_LO:
			case this.SOUND3CNT_LO | 1:
			case this.SOUND3CNT_HI:
			case this.SOUND3CNT_HI | 1:
			case this.SOUND3CNT_X:
			case this.SOUND3CNT_X | 1:
			case this.SOUND4CNT_LO:
			case this.SOUND4CNT_LO | 1:
			case this.SOUND4CNT_HI:
			case this.SOUND4CNT_HI | 1:
			case this.SOUNDCNT_LO:
			case this.SOUNDCNT_LO | 1:
			case this.SOUNDCNT_X:
			case this.IF:
			case this.IME:
				break;
			case this.SOUNDBIAS | 1:
				this.STUB_REG("sound", offset);
				break;
			case this.HALTCNT:
				value &= 0x80;
				if (!value) {
					this.core.irq.halt();
				} else {
					this.core.STUB("Stop");
				}
				return;
			default:
				this.STUB_REG("8-bit I/O", offset);
				break;
		}

		if (offset & 1) {
			value <<= 8;
			value |= this.registers[offset >> 1] & 0x00ff;
		} else {
			value &= 0x00ff;
			value |= this.registers[offset >> 1] & 0xff00;
		}
		this.store16(offset & 0xffffffe, value);
	}
	store16(offset, value) {
		switch (offset) {
			// Video
			case this.DISPCNT:
				this.video.renderPath.writeDisplayControl(value);
				break;
			case this.DISPSTAT:
				value &= this.video.DISPSTAT_MASK;
				this.video.writeDisplayStat(value);
				break;
			case this.BG0CNT:
				this.video.renderPath.writeBackgroundControl(0, value);
				break;
			case this.BG1CNT:
				this.video.renderPath.writeBackgroundControl(1, value);
				break;
			case this.BG2CNT:
				this.video.renderPath.writeBackgroundControl(2, value);
				break;
			case this.BG3CNT:
				this.video.renderPath.writeBackgroundControl(3, value);
				break;
			case this.BG0HOFS:
				this.video.renderPath.writeBackgroundHOffset(0, value);
				break;
			case this.BG0VOFS:
				this.video.renderPath.writeBackgroundVOffset(0, value);
				break;
			case this.BG1HOFS:
				this.video.renderPath.writeBackgroundHOffset(1, value);
				break;
			case this.BG1VOFS:
				this.video.renderPath.writeBackgroundVOffset(1, value);
				break;
			case this.BG2HOFS:
				this.video.renderPath.writeBackgroundHOffset(2, value);
				break;
			case this.BG2VOFS:
				this.video.renderPath.writeBackgroundVOffset(2, value);
				break;
			case this.BG3HOFS:
				this.video.renderPath.writeBackgroundHOffset(3, value);
				break;
			case this.BG3VOFS:
				this.video.renderPath.writeBackgroundVOffset(3, value);
				break;
			case this.BG2X_LO:
				this.video.renderPath.writeBackgroundRefX(
					2,
					(this.registers[(offset >> 1) | 1] << 16) | value
				);
				break;
			case this.BG2X_HI:
				this.video.renderPath.writeBackgroundRefX(
					2,
					this.registers[(offset >> 1) ^ 1] | (value << 16)
				);
				break;
			case this.BG2Y_LO:
				this.video.renderPath.writeBackgroundRefY(
					2,
					(this.registers[(offset >> 1) | 1] << 16) | value
				);
				break;
			case this.BG2Y_HI:
				this.video.renderPath.writeBackgroundRefY(
					2,
					this.registers[(offset >> 1) ^ 1] | (value << 16)
				);
				break;
			case this.BG2PA:
				this.video.renderPath.writeBackgroundParamA(2, value);
				break;
			case this.BG2PB:
				this.video.renderPath.writeBackgroundParamB(2, value);
				break;
			case this.BG2PC:
				this.video.renderPath.writeBackgroundParamC(2, value);
				break;
			case this.BG2PD:
				this.video.renderPath.writeBackgroundParamD(2, value);
				break;
			case this.BG3X_LO:
				this.video.renderPath.writeBackgroundRefX(
					3,
					(this.registers[(offset >> 1) | 1] << 16) | value
				);
				break;
			case this.BG3X_HI:
				this.video.renderPath.writeBackgroundRefX(
					3,
					this.registers[(offset >> 1) ^ 1] | (value << 16)
				);
				break;
			case this.BG3Y_LO:
				this.video.renderPath.writeBackgroundRefY(
					3,
					(this.registers[(offset >> 1) | 1] << 16) | value
				);
				break;
			case this.BG3Y_HI:
				this.video.renderPath.writeBackgroundRefY(
					3,
					this.registers[(offset >> 1) ^ 1] | (value << 16)
				);
				break;
			case this.BG3PA:
				this.video.renderPath.writeBackgroundParamA(3, value);
				break;
			case this.BG3PB:
				this.video.renderPath.writeBackgroundParamB(3, value);
				break;
			case this.BG3PC:
				this.video.renderPath.writeBackgroundParamC(3, value);
				break;
			case this.BG3PD:
				this.video.renderPath.writeBackgroundParamD(3, value);
				break;
			case this.WIN0H:
				this.video.renderPath.writeWin0H(value);
				break;
			case this.WIN1H:
				this.video.renderPath.writeWin1H(value);
				break;
			case this.WIN0V:
				this.video.renderPath.writeWin0V(value);
				break;
			case this.WIN1V:
				this.video.renderPath.writeWin1V(value);
				break;
			case this.WININ:
				value &= 0x3f3f;
				this.video.renderPath.writeWinIn(value);
				break;
			case this.WINOUT:
				value &= 0x3f3f;
				this.video.renderPath.writeWinOut(value);
				break;
			case this.BLDCNT:
				value &= 0x7fff;
				this.video.renderPath.writeBlendControl(value);
				break;
			case this.BLDALPHA:
				value &= 0x1f1f;
				this.video.renderPath.writeBlendAlpha(value);
				break;
			case this.BLDY:
				value &= 0x001f;
				this.video.renderPath.writeBlendY(value);
				break;
			case this.MOSAIC:
				this.video.renderPath.writeMosaic(value);
				break;

			// Sound
			case this.SOUND1CNT_LO:
				value &= 0x007f;
				this.audio.writeSquareChannelSweep(0, value);
				break;
			case this.SOUND1CNT_HI:
				this.audio.writeSquareChannelDLE(0, value);
				break;
			case this.SOUND1CNT_X:
				value &= 0xc7ff;
				this.audio.writeSquareChannelFC(0, value);
				value &= ~0x8000;
				break;
			case this.SOUND2CNT_LO:
				this.audio.writeSquareChannelDLE(1, value);
				break;
			case this.SOUND2CNT_HI:
				value &= 0xc7ff;
				this.audio.writeSquareChannelFC(1, value);
				value &= ~0x8000;
				break;
			case this.SOUND3CNT_LO:
				value &= 0x00e0;
				this.audio.writeChannel3Lo(value);
				break;
			case this.SOUND3CNT_HI:
				value &= 0xe0ff;
				this.audio.writeChannel3Hi(value);
				break;
			case this.SOUND3CNT_X:
				value &= 0xc7ff;
				this.audio.writeChannel3X(value);
				value &= ~0x8000;
				break;
			case this.SOUND4CNT_LO:
				value &= 0xff3f;
				this.audio.writeChannel4LE(value);
				break;
			case this.SOUND4CNT_HI:
				value &= 0xc0ff;
				this.audio.writeChannel4FC(value);
				value &= ~0x8000;
				break;
			case this.SOUNDCNT_LO:
				value &= 0xff77;
				this.audio.writeSoundControlLo(value);
				break;
			case this.SOUNDCNT_HI:
				value &= 0xff0f;
				this.audio.writeSoundControlHi(value);
				break;
			case this.SOUNDCNT_X:
				value &= 0x0080;
				this.audio.writeEnable(value);
				break;
			case this.WAVE_RAM0_LO:
			case this.WAVE_RAM0_HI:
			case this.WAVE_RAM1_LO:
			case this.WAVE_RAM1_HI:
			case this.WAVE_RAM2_LO:
			case this.WAVE_RAM2_HI:
			case this.WAVE_RAM3_LO:
			case this.WAVE_RAM3_HI:
				this.audio.writeWaveData(offset - this.WAVE_RAM0_LO, value, 2);
				break;

			// DMA
			case this.DMA0SAD_LO:
			case this.DMA0DAD_LO:
			case this.DMA1SAD_LO:
			case this.DMA1DAD_LO:
			case this.DMA2SAD_LO:
			case this.DMA2DAD_LO:
			case this.DMA3SAD_LO:
			case this.DMA3DAD_LO:
				this.store32(
					offset,
					(this.registers[(offset >> 1) + 1] << 16) | value
				);
				return;

			case this.DMA0SAD_HI:
			case this.DMA0DAD_HI:
			case this.DMA1SAD_HI:
			case this.DMA1DAD_HI:
			case this.DMA2SAD_HI:
			case this.DMA2DAD_HI:
			case this.DMA3SAD_HI:
			case this.DMA3DAD_HI:
				this.store32(
					offset - 2,
					this.registers[(offset >> 1) - 1] | (value << 16)
				);
				return;

			case this.DMA0CNT_LO:
				this.cpu.irq.dmaSetWordCount(0, value);
				break;
			case this.DMA0CNT_HI:
				// The DMA registers need to set the values before writing the control, as writing the
				// control can synchronously trigger a DMA transfer
				this.registers[offset >> 1] = value & 0xffe0;
				this.cpu.irq.dmaWriteControl(0, value);
				return;
			case this.DMA1CNT_LO:
				this.cpu.irq.dmaSetWordCount(1, value);
				break;
			case this.DMA1CNT_HI:
				this.registers[offset >> 1] = value & 0xffe0;
				this.cpu.irq.dmaWriteControl(1, value);
				return;
			case this.DMA2CNT_LO:
				this.cpu.irq.dmaSetWordCount(2, value);
				break;
			case this.DMA2CNT_HI:
				this.registers[offset >> 1] = value & 0xffe0;
				this.cpu.irq.dmaWriteControl(2, value);
				return;
			case this.DMA3CNT_LO:
				this.cpu.irq.dmaSetWordCount(3, value);
				break;
			case this.DMA3CNT_HI:
				this.registers[offset >> 1] = value & 0xffe0;
				this.cpu.irq.dmaWriteControl(3, value);
				return;

			// Timers
			case this.TM0CNT_LO:
				this.cpu.irq.timerSetReload(0, value);
				return;
			case this.TM1CNT_LO:
				this.cpu.irq.timerSetReload(1, value);
				return;
			case this.TM2CNT_LO:
				this.cpu.irq.timerSetReload(2, value);
				return;
			case this.TM3CNT_LO:
				this.cpu.irq.timerSetReload(3, value);
				return;

			case this.TM0CNT_HI:
				value &= 0x00c7;
				this.cpu.irq.timerWriteControl(0, value);
				break;
			case this.TM1CNT_HI:
				value &= 0x00c7;
				this.cpu.irq.timerWriteControl(1, value);
				break;
			case this.TM2CNT_HI:
				value &= 0x00c7;
				this.cpu.irq.timerWriteControl(2, value);
				break;
			case this.TM3CNT_HI:
				value &= 0x00c7;
				this.cpu.irq.timerWriteControl(3, value);
				break;

			// SIO
			case this.SIOMULTI0:
			case this.SIOMULTI1:
			case this.SIOMULTI2:
			case this.SIOMULTI3:
			case this.SIODATA8:
				this.STUB_REG("SIO", offset);
				break;
			case this.RCNT:
				this.sio.setMode(
					((value >> 12) & 0xc) |
						((this.registers[this.SIOCNT >> 1] >> 12) & 0x3)
				);
				this.sio.writeRCNT(value);
				break;
			case this.SIOCNT:
				this.sio.setMode(
					((value >> 12) & 0x3) |
						((this.registers[this.RCNT >> 1] >> 12) & 0xc)
				);
				this.sio.writeSIOCNT(value);
				return;
			case this.JOYCNT:
			case this.JOYSTAT:
				this.STUB_REG("JOY", offset);
				break;

			// Misc
			case this.IE:
				value &= 0x3fff;
				this.cpu.irq.setInterruptsEnabled(value);
				break;
			case this.IF:
				this.cpu.irq.dismissIRQs(value);
				return;
			case this.WAITCNT:
				value &= 0xdfff;
				this.cpu.mmu.adjustTimings(value);
				break;
			case this.IME:
				value &= 0x0001;
				this.cpu.irq.masterEnable(value);
				break;
			default:
				this.STUB_REG("I/O", offset);
		}
		this.registers[offset >> 1] = value;
	}
	store32(offset, value) {
		switch (offset) {
			case this.BG2X_LO:
				value &= 0x0fffffff;
				this.video.renderPath.writeBackgroundRefX(2, value);
				break;
			case this.BG2Y_LO:
				value &= 0x0fffffff;
				this.video.renderPath.writeBackgroundRefY(2, value);
				break;
			case this.BG3X_LO:
				value &= 0x0fffffff;
				this.video.renderPath.writeBackgroundRefX(3, value);
				break;
			case this.BG3Y_LO:
				value &= 0x0fffffff;
				this.video.renderPath.writeBackgroundRefY(3, value);
				break;
			case this.DMA0SAD_LO:
				this.cpu.irq.dmaSetSourceAddress(0, value);
				break;
			case this.DMA0DAD_LO:
				this.cpu.irq.dmaSetDestAddress(0, value);
				break;
			case this.DMA1SAD_LO:
				this.cpu.irq.dmaSetSourceAddress(1, value);
				break;
			case this.DMA1DAD_LO:
				this.cpu.irq.dmaSetDestAddress(1, value);
				break;
			case this.DMA2SAD_LO:
				this.cpu.irq.dmaSetSourceAddress(2, value);
				break;
			case this.DMA2DAD_LO:
				this.cpu.irq.dmaSetDestAddress(2, value);
				break;
			case this.DMA3SAD_LO:
				this.cpu.irq.dmaSetSourceAddress(3, value);
				break;
			case this.DMA3DAD_LO:
				this.cpu.irq.dmaSetDestAddress(3, value);
				break;
			case this.FIFO_A_LO:
				this.audio.appendToFifoA(value);
				return;
			case this.FIFO_B_LO:
				this.audio.appendToFifoB(value);
				return;

			// High bits of this write should be ignored
			case this.IME:
				this.store16(offset, value & 0xffff);
				return;
			case this.JOY_RECV:
			case this.JOY_TRANS:
				this.STUB_REG("JOY", offset);
				return;
			default:
				this.store16(offset, value & 0xffff);
				this.store16(offset | 2, value >>> 16);
				return;
		}

		this.registers[offset >> 1] = value & 0xffff;
		this.registers[(offset >> 1) + 1] = value >>> 16;
	}
	invalidatePage(address) {}
	STUB_REG(type, offset) {
		this.core.STUB(
			"Unimplemented " + type + " register write: " + offset.toString(16)
		);
	}
}

class GameBoyAdvanceAudio {
	constructor() {
		window.AudioContext = window.AudioContext || window.webkitAudioContext;
		if (window.AudioContext) {
			var self = this;
			this.context = new AudioContext();
			this.bufferSize = 0;
			this.bufferSize = 4096;
			this.maxSamples = this.bufferSize << 2;
			this.buffers = [
				new Float32Array(this.maxSamples),
				new Float32Array(this.maxSamples)
			];
			this.sampleMask = this.maxSamples - 1;
			if (this.context.createScriptProcessor) {
				this.jsAudio = this.context.createScriptProcessor(
					this.bufferSize
				);
			} else {
				this.jsAudio = this.context.createJavaScriptNode(
					this.bufferSize
				);
			}
			this.jsAudio.onaudioprocess = function (e) {
				self.audioProcess(e);
			};
		} else {
			this.context = null;
		}

		this.masterEnable = true;
		this.masterVolume = 1.0;

		this.SOUND_MAX = 0x400;
		this.FIFO_MAX = 0x200;
		this.PSG_MAX = 0x080;
	}
	clear() {
		this.fifoA = [];
		this.fifoB = [];
		this.fifoASample = 0;
		this.fifoBSample = 0;

		this.enabled = false;
		if (this.context) {
			try {
				this.jsAudio.disconnect(this.context.destination);
			} catch (e) {}
		}

		this.enableChannel3 = false;
		this.enableChannel4 = false;
		this.enableChannelA = false;
		this.enableChannelB = false;
		this.enableRightChannelA = false;
		this.enableLeftChannelA = false;
		this.enableRightChannelB = false;
		this.enableLeftChannelB = false;

		this.playingChannel3 = false;
		this.playingChannel4 = false;

		this.volumeLeft = 0;
		this.volumeRight = 0;
		this.ratioChannelA = 1;
		this.ratioChannelB = 1;
		this.enabledLeft = 0;
		this.enabledRight = 0;

		this.dmaA = -1;
		this.dmaB = -1;
		this.soundTimerA = 0;
		this.soundTimerB = 0;

		this.soundRatio = 1;
		this.soundBias = 0x200;

		this.squareChannels = new Array();
		for (var i = 0; i < 2; ++i) {
			this.squareChannels[i] = {
				enabled: false,
				playing: false,
				sample: 0,
				duty: 0.5,
				increment: 0,
				step: 0,
				initialVolume: 0,
				volume: 0,
				frequency: 0,
				interval: 0,
				sweepSteps: 0,
				sweepIncrement: 0,
				sweepInterval: 0,
				doSweep: false,
				raise: 0,
				lower: 0,
				nextStep: 0,
				timed: false,
				length: 0,
				end: 0
			};
		}

		this.waveData = new Uint8Array(32);
		this.channel3Dimension = 0;
		this.channel3Bank = 0;
		this.channel3Volume = 0;
		this.channel3Interval = 0;
		this.channel3Next = 0;
		this.channel3Length = 0;
		this.channel3Timed = false;
		this.channel3End = 0;
		this.channel3Pointer = 0;
		this.channel3Sample = 0;

		this.cpuFrequency = this.core.irq.FREQUENCY;

		this.channel4 = {
			sample: 0,
			lfsr: 0,
			width: 15,
			interval: this.cpuFrequency / 524288,
			increment: 0,
			step: 0,
			initialVolume: 0,
			volume: 0,
			nextStep: 0,
			timed: false,
			length: 0,
			end: 0
		};

		this.nextEvent = 0;

		this.nextSample = 0;
		this.outputPointer = 0;
		this.samplePointer = 0;

		this.backup = 0;
		this.totalSamples = 0;

		this.sampleRate = 32768;
		this.sampleInterval = this.cpuFrequency / this.sampleRate;
		this.resampleRatio = 1;
		if (this.context) {
			this.resampleRatio = this.sampleRate / this.context.sampleRate;
		}

		this.writeSquareChannelFC(0, 0);
		this.writeSquareChannelFC(1, 0);
		this.writeChannel4FC(0);
	}
	freeze() {
		return {
			nextSample: this.nextSample
		};
	}
	defrost(frost) {
		this.nextSample = frost.nextSample;
	}
	pause(paused) {
		if (this.context) {
			if (paused) {
				try {
					this.jsAudio.disconnect(this.context.destination);
				} catch (e) {
					// Sigh
				}
			} else if (this.enabled) {
				this.jsAudio.connect(this.context.destination);
			}
		}
	}
	updateTimers() {
		var cycles = this.cpu.cycles;
		if (
			!this.enabled ||
			(cycles < this.nextEvent && cycles < this.nextSample)
		) {
			return;
		}

		if (cycles >= this.nextEvent) {
			var channel = this.squareChannels[0];
			this.nextEvent = Infinity;
			if (channel.playing) {
				this.updateSquareChannel(channel, cycles);
			}

			channel = this.squareChannels[1];
			if (channel.playing) {
				this.updateSquareChannel(channel, cycles);
			}

			if (this.enableChannel3 && this.playingChannel3) {
				if (cycles >= this.channel3Next) {
					if (this.channel3Write) {
						var sample = this.waveData[this.channel3Pointer >> 1];
					this.channel3Sample = (((sample >> ((this.channel3Pointer & 1) << 2)) & 0xF) - 0x8) / 8;
					this.channel3Pointer = (this.channel3Pointer + 1);
						if (
							this.channel3Dimension &&
							this.channel3Pointer >= 64
						) {
							this.channel3Pointer -= 64;
						} else if (
							!this.channel3Bank &&
							this.channel3Pointer >= 32
						) {
							this.channel3Pointer -= 32;
						} else if (this.channel3Pointer >= 64) {
							this.channel3Pointer -= 32;
						}
					}
					this.channel3Next += this.channel3Interval;
					if (
						this.channel3Interval &&
						this.nextEvent > this.channel3Next
					) {
						this.nextEvent = this.channel3Next;
					}
				}
				if (this.channel3Timed && cycles >= this.channel3End) {
					this.playingChannel3 = false;
				}
			}

			if (this.enableChannel4 && this.playingChannel4) {
				if (this.channel4.timed && cycles >= this.channel4.end) {
					this.playingChannel4 = false;
				} else {
					if (cycles >= this.channel4.next) {
						this.channel4.lfsr >>= 1;
						var sample = this.channel4.lfsr & 1;
						this.channel4.lfsr |=
							(((this.channel4.lfsr >> 1) & 1) ^ sample) <<
							(this.channel4.width - 1);
						this.channel4.next += this.channel4.interval;
						this.channel4.sample =
							(sample - 0.5) * 2 * this.channel4.volume;
					}
					this.updateEnvelope(this.channel4, cycles);
					if (this.nextEvent > this.channel4.next) {
						this.nextEvent = this.channel4.next;
					}
					if (
						this.channel4.timed &&
						this.nextEvent > this.channel4.end
					) {
						this.nextEvent = this.channel4.end;
					}
				}
			}
		}

		if (cycles >= this.nextSample) {
			this.sample();
			this.nextSample += this.sampleInterval;
		}

		this.nextEvent = Math.ceil(this.nextEvent);
		if (this.nextEvent < cycles || this.nextSample < cycles) {
			// STM instructions may take a long time
			this.updateTimers();
		}
	}
	writeEnable(value) {
		this.enabled = !!value;
		this.nextEvent = this.cpu.cycles;
		this.nextSample = this.nextEvent;
		this.updateTimers();
		this.core.irq.pollNextEvent();
		if (this.context) {
			if (value) {
				this.jsAudio.connect(this.context.destination);
			} else {
				try {
					this.jsAudio.disconnect(this.context.destination);
				} catch (e) {}
			}
		}
	}
	writeSoundControlLo(value) {
		this.masterVolumeLeft = value & 0x7;
		this.masterVolumeRight = (value >> 4) & 0x7;
		this.enabledLeft = (value >> 8) & 0xf;
		this.enabledRight = (value >> 12) & 0xf;

		this.setSquareChannelEnabled(
			this.squareChannels[0],
			(this.enabledLeft | this.enabledRight) & 0x1
		);
		this.setSquareChannelEnabled(
			this.squareChannels[1],
			(this.enabledLeft | this.enabledRight) & 0x2
		);
		this.enableChannel3 = (this.enabledLeft | this.enabledRight) & 0x4;
		this.setChannel4Enabled((this.enabledLeft | this.enabledRight) & 0x8);

		this.updateTimers();
		this.core.irq.pollNextEvent();
	}
	writeSoundControlHi(value) {
		switch (value & 0x0003) {
			case 0:
				this.soundRatio = 0.25;
				break;
			case 1:
		this.soundRatio = 0.50;
				break;
			case 2:
				this.soundRatio = 1;
				break;
		}
		this.ratioChannelA = (((value & 0x0004) >> 2) + 1) * 0.5;
		this.ratioChannelB = (((value & 0x0008) >> 3) + 1) * 0.5;

		this.enableRightChannelA = value & 0x0100;
		this.enableLeftChannelA = value & 0x0200;
		this.enableChannelA = value & 0x0300;
		this.soundTimerA = value & 0x0400;
		if (value & 0x0800) {
			this.fifoA = [];
		}
		this.enableRightChannelB = value & 0x1000;
		this.enableLeftChannelB = value & 0x2000;
		this.enableChannelB = value & 0x3000;
		this.soundTimerB = value & 0x4000;
		if (value & 0x8000) {
			this.fifoB = [];
		}
	}
	resetSquareChannel(channel) {
		if (channel.step) {
			channel.nextStep = this.cpu.cycles + channel.step;
		}
		if (channel.enabled && !channel.playing) {
			channel.raise = this.cpu.cycles;
			channel.lower = channel.raise + channel.duty * channel.interval;
			channel.end = this.cpu.cycles + channel.length;
			this.nextEvent = this.cpu.cycles;
		}
		channel.playing = channel.enabled;
		this.updateTimers();
		this.core.irq.pollNextEvent();
	}
	setSquareChannelEnabled(channel, enable) {
		if (!(channel.enabled && channel.playing) && enable) {
			channel.enabled = !!enable;
			this.updateTimers();
			this.core.irq.pollNextEvent();
		} else {
			channel.enabled = !!enable;
		}
	}
	writeSquareChannelSweep(channelId, value) {
		var channel = this.squareChannels[channelId];
		channel.sweepSteps = value & 0x07;
	channel.sweepIncrement = (value & 0x08) ? -1 : 1;
	channel.sweepInterval = ((value >> 4) & 0x7) * this.cpuFrequency / 128;
		channel.doSweep = !!channel.sweepInterval;
		channel.nextSweep = this.cpu.cycles + channel.sweepInterval;
		this.resetSquareChannel(channel);
	}
	writeSquareChannelDLE(channelId, value) {
		var channel = this.squareChannels[channelId];
		var duty = (value >> 6) & 0x3;
		switch (duty) {
			case 0:
				channel.duty = 0.125;
				break;
			case 1:
				channel.duty = 0.25;
				break;
			case 2:
				channel.duty = 0.5;
				break;
			case 3:
				channel.duty = 0.75;
				break;
		}
		this.writeChannelLE(channel, value);
		this.resetSquareChannel(channel);
	}
	writeSquareChannelFC(channelId, value) {
		var channel = this.squareChannels[channelId];
		var frequency = value & 2047;
		channel.frequency = frequency;
		channel.interval = (this.cpuFrequency * (2048 - frequency)) / 131072;
		channel.timed = !!(value & 0x4000);

		if (value & 0x8000) {
			this.resetSquareChannel(channel);
			channel.volume = channel.initialVolume;
		}
	}
	updateSquareChannel(channel, cycles) {
		if (channel.timed && cycles >= channel.end) {
			channel.playing = false;
			return;
		}

		if (channel.doSweep && cycles >= channel.nextSweep) {
			channel.frequency +=
				channel.sweepIncrement *
				(channel.frequency >> channel.sweepSteps);
			if (channel.frequency < 0) {
				channel.frequency = 0;
			} else if (channel.frequency > 2047) {
				channel.frequency = 2047;
				channel.playing = false;
				return;
			}
			channel.interval =
				(this.cpuFrequency * (2048 - channel.frequency)) / 131072;
			channel.nextSweep += channel.sweepInterval;
		}

		if (cycles >= channel.raise) {
			channel.sample = channel.volume;
			channel.lower = channel.raise + channel.duty * channel.interval;
			channel.raise += channel.interval;
		} else if (cycles >= channel.lower) {
			channel.sample = -channel.volume;
			channel.lower += channel.interval;
		}

		this.updateEnvelope(channel, cycles);

		if (this.nextEvent > channel.raise) {
			this.nextEvent = channel.raise;
		}
		if (this.nextEvent > channel.lower) {
			this.nextEvent = channel.lower;
		}
		if (channel.timed && this.nextEvent > channel.end) {
			this.nextEvent = channel.end;
		}
		if (channel.doSweep && this.nextEvent > channel.nextSweep) {
			this.nextEvent = channel.nextSweep;
		}
	}
	writeChannel3Lo(value) {
		this.channel3Dimension = value & 0x20;
		this.channel3Bank = value & 0x40;
		var enable = value & 0x80;
		if (!this.channel3Write && enable) {
			this.channel3Write = enable;
			this.resetChannel3();
		} else {
			this.channel3Write = enable;
		}
	}
	writeChannel3Hi(value) {
		this.channel3Length =
			(this.cpuFrequency * (0x100 - (value & 0xff))) / 256;
		var volume = (value >> 13) & 0x7;
		switch (volume) {
			case 0:
				this.channel3Volume = 0;
				break;
			case 1:
				this.channel3Volume = 1;
				break;
			case 2:
				this.channel3Volume = 0.5;
				break;
			case 3:
				this.channel3Volume = 0.25;
				break;
			default:
				this.channel3Volume = 0.75;
		}
	}
	writeChannel3X(value) {
		this.channel3Interval =
			(this.cpuFrequency * (2048 - (value & 0x7ff))) / 2097152;
		this.channel3Timed = !!(value & 0x4000);
		if (this.channel3Write) {
			this.resetChannel3();
		}
	}
	resetChannel3() {
		this.channel3Next = this.cpu.cycles;
		this.nextEvent = this.channel3Next;
		this.channel3End = this.cpu.cycles + this.channel3Length;
		this.playingChannel3 = this.channel3Write;
		this.updateTimers();
		this.core.irq.pollNextEvent();
	}
	writeWaveData(offset, data, width) {
		if (!this.channel3Bank) {
			offset += 16;
		}
		if (width == 2) {
			this.waveData[offset] = data & 0xff;
			data >>= 8;
			++offset;
		}
		this.waveData[offset] = data & 0xff;
	}
	setChannel4Enabled(enable) {
		if (!this.enableChannel4 && enable) {
			this.channel4.next = this.cpu.cycles;
			this.channel4.end = this.cpu.cycles + this.channel4.length;
			this.enableChannel4 = true;
			this.playingChannel4 = true;
			this.nextEvent = this.cpu.cycles;
			this.updateEnvelope(this.channel4);
			this.updateTimers();
			this.core.irq.pollNextEvent();
		} else {
			this.enableChannel4 = enable;
		}
	}
	writeChannel4LE(value) {
		this.writeChannelLE(this.channel4, value);
		this.resetChannel4();
	}
	writeChannel4FC(value) {
		this.channel4.timed = !!(value & 0x4000);

		var r = value & 0x7;
		if (!r) {
			r = 0.5;
		}
		var s = (value >> 4) & 0xf;
		var interval = (this.cpuFrequency * (r * (2 << s))) / 524288;
		if (interval != this.channel4.interval) {
			this.channel4.interval = interval;
			this.resetChannel4();
		}

		var width = value & 0x8 ? 7 : 15;
		if (width != this.channel4.width) {
			this.channel4.width = width;
			this.resetChannel4();
		}

		if (value & 0x8000) {
			this.resetChannel4();
		}
	}
	resetChannel4() {
		if (this.channel4.width == 15) {
			this.channel4.lfsr = 0x4000;
		} else {
			this.channel4.lfsr = 0x40;
		}
		this.channel4.volume = this.channel4.initialVolume;
		if (this.channel4.step) {
			this.channel4.nextStep = this.cpu.cycles + this.channel4.step;
		}
		this.channel4.end = this.cpu.cycles + this.channel4.length;
		this.channel4.next = this.cpu.cycles;
		this.nextEvent = this.channel4.next;

		this.playingChannel4 = this.enableChannel4;
		this.updateTimers();
		this.core.irq.pollNextEvent();
	}
	writeChannelLE(channel, value) {
		channel.length = this.cpuFrequency * ((0x40 - (value & 0x3f)) / 256);

		if (value & 0x0800) {
			channel.increment = 1 / 16;
		} else {
			channel.increment = -1 / 16;
		}
		channel.initialVolume = ((value >> 12) & 0xf) / 16;

		channel.step = this.cpuFrequency * (((value >> 8) & 0x7) / 64);
	}
	updateEnvelope(channel, cycles) {
		if (channel.step) {
			if (cycles >= channel.nextStep) {
				channel.volume += channel.increment;
				if (channel.volume > 1) {
					channel.volume = 1;
				} else if (channel.volume < 0) {
					channel.volume = 0;
				}
				channel.nextStep += channel.step;
			}

			if (this.nextEvent > channel.nextStep) {
				this.nextEvent = channel.nextStep;
			}
		}
	}
	appendToFifoA(value) {
		var b;
		if (this.fifoA.length > 28) {
			this.fifoA = this.fifoA.slice(-28);
		}
		for (var i = 0; i < 4; ++i) {
			b = (value & 0xff) << 24;
			value >>= 8;
			this.fifoA.push(b / 0x80000000);
		}
	}
	appendToFifoB(value) {
		var b;
		if (this.fifoB.length > 28) {
			this.fifoB = this.fifoB.slice(-28);
		}
		for (var i = 0; i < 4; ++i) {
			b = (value & 0xff) << 24;
			value >>= 8;
			this.fifoB.push(b / 0x80000000);
		}
	}
	sampleFifoA() {
		if (this.fifoA.length <= 16) {
			var dma = this.core.irq.dma[this.dmaA];
			dma.nextCount = 4;
			this.core.mmu.serviceDma(this.dmaA, dma);
		}
		this.fifoASample = this.fifoA.shift();
	}
	sampleFifoB() {
		if (this.fifoB.length <= 16) {
			var dma = this.core.irq.dma[this.dmaB];
			dma.nextCount = 4;
			this.core.mmu.serviceDma(this.dmaB, dma);
		}
		this.fifoBSample = this.fifoB.shift();
	}
	scheduleFIFODma(number, info) {
		switch (info.dest) {
			case this.cpu.mmu.BASE_IO | this.cpu.irq.io.FIFO_A_LO:
				// FIXME: is this needed or a hack?
				info.dstControl = 2;
				this.dmaA = number;
				break;
			case this.cpu.mmu.BASE_IO | this.cpu.irq.io.FIFO_B_LO:
				info.dstControl = 2;
				this.dmaB = number;
				break;
			default:
				this.core.WARN(
					"Tried to schedule FIFO DMA for non-FIFO destination"
				);
				break;
		}
	}
	sample() {
		var sampleLeft = 0;
		var sampleRight = 0;
		var sample;
		var channel;

		channel = this.squareChannels[0];
		if (channel.playing) {
			sample = channel.sample * this.soundRatio * this.PSG_MAX;
			if (this.enabledLeft & 0x1) {
				sampleLeft += sample;
			}
			if (this.enabledRight & 0x1) {
				sampleRight += sample;
			}
		}

		channel = this.squareChannels[1];
		if (channel.playing) {
			sample = channel.sample * this.soundRatio * this.PSG_MAX;
			if (this.enabledLeft & 0x2) {
				sampleLeft += sample;
			}
			if (this.enabledRight & 0x2) {
				sampleRight += sample;
			}
		}

		if (this.playingChannel3) {
			sample =
				this.channel3Sample *
				this.soundRatio *
				this.channel3Volume *
				this.PSG_MAX;
			if (this.enabledLeft & 0x4) {
				sampleLeft += sample;
			}
			if (this.enabledRight & 0x4) {
				sampleRight += sample;
			}
		}

		if (this.playingChannel4) {
			sample = this.channel4.sample * this.soundRatio * this.PSG_MAX;
			if (this.enabledLeft & 0x8) {
				sampleLeft += sample;
			}
			if (this.enabledRight & 0x8) {
				sampleRight += sample;
			}
		}

		if (this.enableChannelA) {
			sample = this.fifoASample * this.FIFO_MAX * this.ratioChannelA;
			if (this.enableLeftChannelA) {
				sampleLeft += sample;
			}
			if (this.enableRightChannelA) {
				sampleRight += sample;
			}
		}

		if (this.enableChannelB) {
			sample = this.fifoBSample * this.FIFO_MAX * this.ratioChannelB;
			if (this.enableLeftChannelB) {
				sampleLeft += sample;
			}
			if (this.enableRightChannelB) {
				sampleRight += sample;
			}
		}

		var samplePointer = this.samplePointer;
		sampleLeft *= this.masterVolume / this.SOUND_MAX;
		sampleLeft = Math.max(Math.min(sampleLeft, 1), -1);
		sampleRight *= this.masterVolume / this.SOUND_MAX;
		sampleRight = Math.max(Math.min(sampleRight, 1), -1);
		if (this.buffers) {
			this.buffers[0][samplePointer] = sampleLeft;
			this.buffers[1][samplePointer] = sampleRight;
		}
		this.samplePointer = (samplePointer + 1) & this.sampleMask;
	}
	audioProcess(audioProcessingEvent) {
		var left = audioProcessingEvent.outputBuffer.getChannelData(0);
		var right = audioProcessingEvent.outputBuffer.getChannelData(1);
		if (this.masterEnable) {
			var i;
			var o = this.outputPointer;
			for (i = 0; i < this.bufferSize; ++i, o += this.resampleRatio) {
				if (o >= this.maxSamples) {
					o -= this.maxSamples;
				}
				if ((o | 0) == this.samplePointer) {
					++this.backup;
					break;
				}
				left[i] = this.buffers[0][o | 0];
				right[i] = this.buffers[1][o | 0];
			}
			for (; i < this.bufferSize; ++i) {
				left[i] = 0;
				right[i] = 0;
			}
			this.outputPointer = o;
			++this.totalSamples;
		} else {
			for (i = 0; i < this.bufferSize; ++i) {
				left[i] = 0;
				right[i] = 0;
			}
		}
	}
}

class GameBoyAdvanceVideo {
	constructor() {
		try {
			this.renderPath = new GameBoyAdvanceRenderProxy();
		} catch(err) {
			console.log("Service worker renderer couldn't load. Save states (not save files) may be glitchy")
			this.renderPath = new GameBoyAdvanceSoftwareRenderer();
		}


		this.CYCLES_PER_PIXEL = 4;

		this.HORIZONTAL_PIXELS = 240;
		this.HBLANK_PIXELS = 68;
		this.HDRAW_LENGTH = 1006;
		this.HBLANK_LENGTH = 226;
		this.HORIZONTAL_LENGTH = 1232;

		this.VERTICAL_PIXELS = 160;
		this.VBLANK_PIXELS = 68;
		this.VERTICAL_TOTAL_PIXELS = 228;

		this.TOTAL_LENGTH = 280896;

		this.drawCallback = function () {};
		this.vblankCallback = function () {};
	}
	clear() {
		this.renderPath.clear(this.cpu.mmu);

		// DISPSTAT
		this.DISPSTAT_MASK = 0xff38;
		this.inHblank = false;
		this.inVblank = false;
		this.vcounter = 0;
		this.vblankIRQ = 0;
		this.hblankIRQ = 0;
		this.vcounterIRQ = 0;
		this.vcountSetting = 0;

		// VCOUNT
		this.vcount = -1;

		this.lastHblank = 0;
		this.nextHblank = this.HDRAW_LENGTH;
		this.nextEvent = this.nextHblank;

		this.nextHblankIRQ = 0;
		this.nextVblankIRQ = 0;
		this.nextVcounterIRQ = 0;
	}
	freeze() {
		return {
			inHblank: this.inHblank,
			inVblank: this.inVblank,
			vcounter: this.vcounter,
			vblankIRQ: this.vblankIRQ,
			hblankIRQ: this.hblankIRQ,
			vcounterIRQ: this.vcounterIRQ,
			vcountSetting: this.vcountSetting,
			vcount: this.vcount,
			lastHblank: this.lastHblank,
			nextHblank: this.nextHblank,
			nextEvent: this.nextEvent,
			nextHblankIRQ: this.nextHblankIRQ,
			nextVblankIRQ: this.nextVblankIRQ,
			nextVcounterIRQ: this.nextVcounterIRQ,
			renderPath: this.renderPath.freeze(this.core.encodeBase64)
		};
	}
	defrost(frost) {
		this.inHblank = frost.inHblank;
		this.inVblank = frost.inVblank;
		this.vcounter = frost.vcounter;
		this.vblankIRQ = frost.vblankIRQ;
		this.hblankIRQ = frost.hblankIRQ;
		this.vcounterIRQ = frost.vcounterIRQ;
		this.vcountSetting = frost.vcountSetting;
		this.vcount = frost.vcount;
		this.lastHblank = frost.lastHblank;
		this.nextHblank = frost.nextHblank;
		this.nextEvent = frost.nextEvent;
		this.nextHblankIRQ = frost.nextHblankIRQ;
		this.nextVblankIRQ = frost.nextVblankIRQ;
		this.nextVcounterIRQ = frost.nextVcounterIRQ;
		this.renderPath.defrost(frost.renderPath, this.core.decodeBase64);
	}
	setBacking(backing) {
		var pixelData = backing.createImageData(
			this.HORIZONTAL_PIXELS,
			this.VERTICAL_PIXELS
		);
		this.context = backing;

		// Clear backing first
		for (
			var offset = 0;
			offset < this.HORIZONTAL_PIXELS * this.VERTICAL_PIXELS * 4;

		) {
			pixelData.data[offset++] = 0xff;
			pixelData.data[offset++] = 0xff;
			pixelData.data[offset++] = 0xff;
			pixelData.data[offset++] = 0xff;
		}

		this.renderPath.setBacking(pixelData);
	}
	updateTimers(cpu) {
		var cycles = cpu.cycles;

		if (this.nextEvent <= cycles) {
			if (this.inHblank) {
				// End Hblank
				this.inHblank = false;
				this.nextEvent = this.nextHblank;

				++this.vcount;

				switch (this.vcount) {
					case this.VERTICAL_PIXELS:
						this.inVblank = true;
						this.renderPath.finishDraw(this);
						this.nextVblankIRQ = this.nextEvent + this.TOTAL_LENGTH;
						this.cpu.mmu.runVblankDmas();
						if (this.vblankIRQ) {
							this.cpu.irq.raiseIRQ(this.cpu.irq.IRQ_VBLANK);
						}
						this.vblankCallback();
						break;
					case this.VERTICAL_TOTAL_PIXELS - 1:
						this.inVblank = false;
						break;
					case this.VERTICAL_TOTAL_PIXELS:
						this.vcount = 0;
						this.renderPath.startDraw();
						break;
				}

				this.vcounter = this.vcount == this.vcountSetting;
				if (this.vcounter && this.vcounterIRQ) {
					this.cpu.irq.raiseIRQ(this.cpu.irq.IRQ_VCOUNTER);
					this.nextVcounterIRQ += this.TOTAL_LENGTH;
				}

				if (this.vcount < this.VERTICAL_PIXELS) {
					this.renderPath.drawScanline(this.vcount);
				}
			} else {
				// Begin Hblank
				this.inHblank = true;
				this.lastHblank = this.nextHblank;
				this.nextEvent = this.lastHblank + this.HBLANK_LENGTH;
				this.nextHblank = this.nextEvent + this.HDRAW_LENGTH;
				this.nextHblankIRQ = this.nextHblank;

				if (this.vcount < this.VERTICAL_PIXELS) {
					this.cpu.mmu.runHblankDmas();
				}
				if (this.hblankIRQ) {
					this.cpu.irq.raiseIRQ(this.cpu.irq.IRQ_HBLANK);
				}
			}
		}
	}
	writeDisplayStat(value) {
		this.vblankIRQ = value & 0x0008;
		this.hblankIRQ = value & 0x0010;
		this.vcounterIRQ = value & 0x0020;
		this.vcountSetting = (value & 0xff00) >> 8;

		if (this.vcounterIRQ) {
			// FIXME: this can be too late if we're in the middle of an Hblank
			this.nextVcounterIRQ =
				this.nextHblank +
				this.HBLANK_LENGTH +
				(this.vcountSetting - this.vcount) * this.HORIZONTAL_LENGTH;
			if (this.nextVcounterIRQ < this.nextEvent) {
				this.nextVcounterIRQ += this.TOTAL_LENGTH;
			}
		}
	}
	readDisplayStat() {
		return this.inVblank | (this.inHblank << 1) | (this.vcounter << 2);
	}
	finishDraw(pixelData) {
		this.context.putImageData(pixelData, 0, 0);
		this.drawCallback();
	}
}

class MemoryProxy {
	constructor(owner, size, blockSize) {
		this.owner = owner;
		this.blocks = [];
		this.blockSize = blockSize;
		this.mask = (1 << blockSize) - 1;
		this.size = size;
		if (blockSize) {
			for (var i = 0; i < (size >> blockSize); ++i) {
				this.blocks.push(new MemoryView(new ArrayBuffer(1 << blockSize)));
			}
		}
		else {
			this.blockSize = 31;
			this.mask = -1;
			this.blocks[0] = new MemoryView(new ArrayBuffer(size));
		}
	}
	combine() {
		if (this.blocks.length > 1) {
			var combined = new Uint8Array(this.size);
			for (var i = 0; i < this.blocks.length; ++i) {
				combined.set(new Uint8Array(this.blocks[i].buffer), i << this.blockSize);
			}
			return combined.buffer;
		}
		else {
			return this.blocks[0].buffer;
		}
	}
	replace(buffer) {
		for (var i = 0; i < this.blocks.length; ++i) {
			this.blocks[i] = new MemoryView(buffer.slice(i << this.blockSize, (i << this.blockSize) + this.blocks[i].buffer.byteLength));
		}
	}
	load8(offset) {
		return this.blocks[offset >> this.blockSize].load8(offset & this.mask);
	}
	load16(offset) {
		return this.blocks[offset >> this.blockSize].load16(offset & this.mask);
	}
	loadU8(offset) {
		return this.blocks[offset >> this.blockSize].loadU8(offset & this.mask);
	}
	loadU16(offset) {
		return this.blocks[offset >> this.blockSize].loadU16(offset & this.mask);
	}
	load32(offset) {
		return this.blocks[offset >> this.blockSize].load32(offset & this.mask);
	}
	store8(offset, value) {
		if (offset >= this.size) {
			return;
		}
		this.owner.memoryDirtied(this, offset >> this.blockSize);
		this.blocks[offset >> this.blockSize].store8(offset & this.mask, value);
		this.blocks[offset >> this.blockSize].store8((offset & this.mask) ^ 1, value);
	}
	store16(offset, value) {
		if (offset >= this.size) {
			return;
		}
		this.owner.memoryDirtied(this, offset >> this.blockSize);
		return this.blocks[offset >> this.blockSize].store16(offset & this.mask, value);
	}
	store32(offset, value) {
		if (offset >= this.size) {
			return;
		}
		this.owner.memoryDirtied(this, offset >> this.blockSize);
		return this.blocks[offset >> this.blockSize].store32(offset & this.mask, value);
	}
	invalidatePage(address) { }
};

class GameBoyAdvanceRenderProxy {
	constructor() {
		this.worker = new Worker('js/video/worker.js');

		this.currentFrame = 0;
		this.delay = 0;
		this.skipFrame = false;

		this.dirty = null;
		var self = this;
		var handlers = {
			finish: function (data) {
				self.backing = data.backing;
				self.caller.finishDraw(self.backing);
				--self.delay;
			}
		};
		this.worker.onmessage = function (message) {
			handlers[message.data['type']](message.data);
		};
	}
	memoryDirtied(mem, block) {
		this.dirty = this.dirty || {};
		this.dirty.memory = this.dirty.memory || {};
		if (mem === this.palette) {
			this.dirty.memory.palette = mem.blocks[0].buffer;
		}
		if (mem === this.oam) {
			this.dirty.memory.oam = mem.blocks[0].buffer;
		}
		if (mem === this.vram) {
			this.dirty.memory.vram = this.dirty.memory.vram || [];
			this.dirty.memory.vram[block] = mem.blocks[block].buffer;
		}
	}
	clear(mmu) {
		this.palette = new MemoryProxy(this, mmu.SIZE_PALETTE_RAM, 0);
		this.vram = new MemoryProxy(this, mmu.SIZE_VRAM, 13);
		this.oam = new MemoryProxy(this, mmu.SIZE_OAM, 0);

		this.dirty = null;
		this.scanlineQueue = [];

		this.worker.postMessage({ type: 'clear', SIZE_VRAM: mmu.SIZE_VRAM, SIZE_OAM: mmu.SIZE_OAM });
	}
	freeze(encodeBase64) {
		return {
			'palette': Serializer.prefix(this.palette.combine()),
			'vram': Serializer.prefix(this.vram.combine()),
			'oam': Serializer.prefix(this.oam.combine())
		};
	}
	defrost(frost, decodeBase64) {
		this.palette.replace(frost.palette);
		this.memoryDirtied(this.palette, 0);
		this.vram.replace(frost.vram);
		for (var i = 0; i < this.vram.blocks.length; ++i) {
			this.memoryDirtied(this.vram, i);
		}
		this.oam.replace(frost.oam);
		this.memoryDirtied(this.oam, 0);
	}
	writeDisplayControl(value) {
		this.dirty = this.dirty || {};
		this.dirty.DISPCNT = value;
	}
	writeBackgroundControl(bg, value) {
		this.dirty = this.dirty || {};
		this.dirty.BGCNT = this.dirty.BGCNT || [];
		this.dirty.BGCNT[bg] = value;
	}
	writeBackgroundHOffset(bg, value) {
		this.dirty = this.dirty || {};
		this.dirty.BGHOFS = this.dirty.BGHOFS || [];
		this.dirty.BGHOFS[bg] = value;
	}
	writeBackgroundVOffset(bg, value) {
		this.dirty = this.dirty || {};
		this.dirty.BGVOFS = this.dirty.BGVOFS || [];
		this.dirty.BGVOFS[bg] = value;
	}
	writeBackgroundRefX(bg, value) {
		this.dirty = this.dirty || {};
		this.dirty.BGX = this.dirty.BGX || [];
		this.dirty.BGX[bg] = value;
	}
	writeBackgroundRefY(bg, value) {
		this.dirty = this.dirty || {};
		this.dirty.BGY = this.dirty.BGY || [];
		this.dirty.BGY[bg] = value;
	}
	writeBackgroundParamA(bg, value) {
		this.dirty = this.dirty || {};
		this.dirty.BGPA = this.dirty.BGPA || [];
		this.dirty.BGPA[bg] = value;
	}
	writeBackgroundParamB(bg, value) {
		this.dirty = this.dirty || {};
		this.dirty.BGPB = this.dirty.BGPB || [];
		this.dirty.BGPB[bg] = value;
	}
	writeBackgroundParamC(bg, value) {
		this.dirty = this.dirty || {};
		this.dirty.BGPC = this.dirty.BGPC || [];
		this.dirty.BGPC[bg] = value;
	}
	writeBackgroundParamD(bg, value) {
		this.dirty = this.dirty || {};
		this.dirty.BGPD = this.dirty.BGPD || [];
		this.dirty.BGPD[bg] = value;
	}
	writeWin0H(value) {
		this.dirty = this.dirty || {};
		this.dirty.WIN0H = value;
	}
	writeWin1H(value) {
		this.dirty = this.dirty || {};
		this.dirty.WIN1H = value;
	}
	writeWin0V(value) {
		this.dirty = this.dirty || {};
		this.dirty.WIN0V = value;
	}
	writeWin1V(value) {
		this.dirty = this.dirty || {};
		this.dirty.WIN1V = value;
	}
	writeWinIn(value) {
		this.dirty = this.dirty || {};
		this.dirty.WININ = value;
	}
	writeWinOut(value) {
		this.dirty = this.dirty || {};
		this.dirty.WINOUT = value;
	}
	writeBlendControl(value) {
		this.dirty = this.dirty || {};
		this.dirty.BLDCNT = value;
	}
	writeBlendAlpha(value) {
		this.dirty = this.dirty || {};
		this.dirty.BLDALPHA = value;
	}
	writeBlendY(value) {
		this.dirty = this.dirty || {};
		this.dirty.BLDY = value;
	}
	writeMosaic(value) {
		this.dirty = this.dirty || {};
		this.dirty.MOSAIC = value;
	}
	clearSubsets(mmu, regions) {
		this.dirty = this.dirty || {};
		if (regions & 0x04) {
			this.palette = new MemoryProxy(this, mmu.SIZE_PALETTE_RAM, 0);
			mmu.mmap(mmu.REGION_PALETTE_RAM, this.palette);
			this.memoryDirtied(this.palette, 0);
		}
		if (regions & 0x08) {
			this.vram = new MemoryProxy(this, mmu.SIZE_VRAM, 13);
			mmu.mmap(mmu.REGION_VRAM, this.vram);
			for (var i = 0; i < this.vram.blocks.length; ++i) {
				this.memoryDirtied(this.vram, i);
			}
		}
		if (regions & 0x10) {
			this.oam = new MemoryProxy(this, mmu.SIZE_OAM, 0);
			mmu.mmap(mmu.REGION_OAM, this.oam);
			this.memoryDirtied(this.oam, 0);
		}
	}
	setBacking(backing) {
		this.backing = backing;
		this.worker.postMessage({ type: 'start', backing: this.backing });
	}
	drawScanline(y) {
		if (!this.skipFrame) {
			if (this.dirty) {
				if (this.dirty.memory) {
					if (this.dirty.memory.palette) {
						this.dirty.memory.palette = this.dirty.memory.palette.slice(0);
					}
					if (this.dirty.memory.oam) {
						this.dirty.memory.oam = this.dirty.memory.oam.slice(0);
					}
					if (this.dirty.memory.vram) {
						for (var i = 0; i < 12; ++i) {
							if (this.dirty.memory.vram[i]) {
								this.dirty.memory.vram[i] = this.dirty.memory.vram[i].slice(0);
							}
						}
					}
				}
				this.scanlineQueue.push({ y: y, dirty: this.dirty });
				this.dirty = null;
			}
		}
	}
	startDraw() {
		++this.currentFrame;
		if (this.delay <= 0) {
			this.skipFrame = false;
		}
		if (!this.skipFrame) {
			++this.delay;
		}
	}
	finishDraw(caller) {
		this.caller = caller;
		if (!this.skipFrame) {
			this.worker.postMessage({ type: 'finish', scanlines: this.scanlineQueue, frame: this.currentFrame });
			this.scanlineQueue = [];
			if (this.delay > 2) {
				this.skipFrame = true;
			}
		}
	}
};

class MemoryAligned16 {
	constructor(size) {
		this.buffer = new Uint16Array(size >> 1);
	}
	load8(offset) {
		return (this.loadU8(offset) << 24) >> 24;
	}
	load16(offset) {
		return (this.loadU16(offset) << 16) >> 16;
	}
	loadU8(offset) {
		var index = offset >> 1;
		if (offset & 1) {
			return (this.buffer[index] & 0xFF00) >>> 8;
		}
		else {
			return this.buffer[index] & 0x00FF;
		}
	}
	loadU16(offset) {
		return this.buffer[offset >> 1];
	}
	load32(offset) {
		return this.buffer[(offset >> 1) & ~1] | (this.buffer[(offset >> 1) | 1] << 16);
	}
	store8(offset, value) {
		var index = offset >> 1;
		this.store16(offset, (value << 8) | value);
	}
	store16(offset, value) {
		this.buffer[offset >> 1] = value;
	}
	store32(offset, value) {
		var index = offset >> 1;
		this.store16(offset, this.buffer[index] = value & 0xFFFF);
		this.store16(offset + 2, this.buffer[index + 1] = value >>> 16);
	}
	insert(start, data) {
		this.buffer.set(data, start);
	}
	invalidatePage(address) { }
};

class GameBoyAdvanceVRAM extends MemoryAligned16 {
	constructor(size) {
		super(size);
		this.vram = this.buffer;
	}
};

class GameBoyAdvanceOAM extends MemoryAligned16 {
	constructor(size) {
		super(size);
		this.oam = this.buffer;
		this.objs = new Array(128);
		for (var i = 0; i < 128; ++i) {
			this.objs[i] = new GameBoyAdvanceOBJ(this, i);
		}
		this.scalerot = new Array(32);
		for (var i = 0; i < 32; ++i) {
			this.scalerot[i] = {
				a: 1,
				b: 0,
				c: 0,
				d: 1
			};
		}
	}
	overwrite(memory) {
		for (var i = 0; i < (this.buffer.byteLength >> 1); ++i) {
			this.store16(i << 1, memory[i]);
		}
	}
	store16(offset, value) {
		var index = (offset & 0x3F8) >> 3;
		var obj = this.objs[index];
		var scalerot = this.scalerot[index >> 2];
		var layer = obj.priority;
		var disable = obj.disable;
		var y = obj.y;
		switch (offset & 0x00000006) {
			case 0:
				// Attribute 0
				obj.y = value & 0x00FF;
				var wasScalerot = obj.scalerot;
				obj.scalerot = value & 0x0100;
				if (obj.scalerot) {
					obj.scalerotOam = this.scalerot[obj.scalerotParam];
					obj.doublesize = !!(value & 0x0200);
					obj.disable = 0;
					obj.hflip = 0;
					obj.vflip = 0;
				}
				else {
					obj.doublesize = false;
					obj.disable = value & 0x0200;
					if (wasScalerot) {
						obj.hflip = obj.scalerotParam & 0x0008;
						obj.vflip = obj.scalerotParam & 0x0010;
					}
				}
				obj.mode = (value & 0x0C00) >> 6; // This lines up with the stencil format
				obj.mosaic = value & 0x1000;
				obj.multipalette = value & 0x2000;
				obj.shape = (value & 0xC000) >> 14;

				obj.recalcSize();
				break;
			case 2:
				// Attribute 1
				obj.x = value & 0x01FF;
				if (obj.scalerot) {
					obj.scalerotParam = (value & 0x3E00) >> 9;
					obj.scalerotOam = this.scalerot[obj.scalerotParam];
					obj.hflip = 0;
					obj.vflip = 0;
					obj.drawScanline = obj.drawScanlineAffine;
				}
				else {
					obj.hflip = value & 0x1000;
					obj.vflip = value & 0x2000;
					obj.drawScanline = obj.drawScanlineNormal;
				}
				obj.size = (value & 0xC000) >> 14;

				obj.recalcSize();
				break;
			case 4:
				// Attribute 2
				obj.tileBase = value & 0x03FF;
				obj.priority = (value & 0x0C00) >> 10;
				obj.palette = (value & 0xF000) >> 8; // This is shifted up 4 to make pushPixel faster
				break;
			case 6:
				// Scaling/rotation parameter
				switch (index & 0x3) {
					case 0:
						scalerot.a = (value << 16) / 0x1000000;
						break;
					case 1:
						scalerot.b = (value << 16) / 0x1000000;
						break;
					case 2:
						scalerot.c = (value << 16) / 0x1000000;
						break;
					case 3:
						scalerot.d = (value << 16) / 0x1000000;
						break;
				}
				break;
		}

		MemoryAligned16.prototype.store16.call(this, offset, value);
	}
}
;

GameBoyAdvanceOAM.prototype = Object.create(MemoryAligned16.prototype);



class GameBoyAdvancePalette {
	constructor() {
		this.colors = [new Array(0x100), new Array(0x100)];
		this.adjustedColors = [new Array(0x100), new Array(0x100)];
		this.passthroughColors = [
			this.colors[0], // BG0
			this.colors[0], // BG1
			this.colors[0], // BG2
			this.colors[0], // BG3
			this.colors[1], // OBJ
			this.colors[0] // Backdrop
		];
		this.blendY = 1;
	}
	overwrite(memory) {
		for (var i = 0; i < 512; ++i) {
			this.store16(i << 1, memory[i]);
		}
	}
	loadU8(offset) {
		return (this.loadU16(offset) >> (8 * (offset & 1))) & 0xFF;
	}
	loadU16(offset) {
		return this.colors[(offset & 0x200) >> 9][(offset & 0x1FF) >> 1];
	}
	load16(offset) {
		return (this.loadU16(offset) << 16) >> 16;
	}
	load32(offset) {
		return this.loadU16(offset) | (this.loadU16(offset + 2) << 16);
	}
	store16(offset, value) {
		var type = (offset & 0x200) >> 9;
		var index = (offset & 0x1FF) >> 1;
		this.colors[type][index] = value;
		this.adjustedColors[type][index] = this.adjustColor(value);
	}
	store32(offset, value) {
		this.store16(offset, value & 0xFFFF);
		this.store16(offset + 2, value >> 16);
	}
	invalidatePage(address) { }
	convert16To32(value, input) {
		var r = (value & 0x001F) << 3;
		var g = (value & 0x03E0) >> 2;
		var b = (value & 0x7C00) >> 7;

		input[0] = r;
		input[1] = g;
		input[2] = b;
	}
	mix(aWeight, aColor, bWeight, bColor) {
		var ar = (aColor & 0x001F);
		var ag = (aColor & 0x03E0) >> 5;
		var ab = (aColor & 0x7C00) >> 10;

		var br = (bColor & 0x001F);
		var bg = (bColor & 0x03E0) >> 5;
		var bb = (bColor & 0x7C00) >> 10;

		var r = Math.min(aWeight * ar + bWeight * br, 0x1F);
		var g = Math.min(aWeight * ag + bWeight * bg, 0x1F);
		var b = Math.min(aWeight * ab + bWeight * bb, 0x1F);

		return r | (g << 5) | (b << 10);
	}
	makeDarkPalettes(layers) {
		if (this.adjustColor != this.adjustColorDark) {
			this.adjustColor = this.adjustColorDark;
			this.resetPalettes();
		}
		this.resetPaletteLayers(layers);
	}
	makeBrightPalettes(layers) {
		if (this.adjustColor != this.adjustColorBright) {
			this.adjustColor = this.adjustColorBright;
			this.resetPalettes();
		}
		this.resetPaletteLayers(layers);
	}
	makeNormalPalettes() {
		this.passthroughColors[0] = this.colors[0];
		this.passthroughColors[1] = this.colors[0];
		this.passthroughColors[2] = this.colors[0];
		this.passthroughColors[3] = this.colors[0];
		this.passthroughColors[4] = this.colors[1];
		this.passthroughColors[5] = this.colors[0];
	}
	makeSpecialPalette(layer) {
		this.passthroughColors[layer] = this.adjustedColors[layer == 4 ? 1 : 0];
	}
	makeNormalPalette(layer) {
		this.passthroughColors[layer] = this.colors[layer == 4 ? 1 : 0];
	}
	resetPaletteLayers(layers) {
		if (layers & 0x01) {
			this.passthroughColors[0] = this.adjustedColors[0];
		}
		else {
			this.passthroughColors[0] = this.colors[0];
		}
		if (layers & 0x02) {
			this.passthroughColors[1] = this.adjustedColors[0];
		}
		else {
			this.passthroughColors[1] = this.colors[0];
		}
		if (layers & 0x04) {
			this.passthroughColors[2] = this.adjustedColors[0];
		}
		else {
			this.passthroughColors[2] = this.colors[0];
		}
		if (layers & 0x08) {
			this.passthroughColors[3] = this.adjustedColors[0];
		}
		else {
			this.passthroughColors[3] = this.colors[0];
		}
		if (layers & 0x10) {
			this.passthroughColors[4] = this.adjustedColors[1];
		}
		else {
			this.passthroughColors[4] = this.colors[1];
		}
		if (layers & 0x20) {
			this.passthroughColors[5] = this.adjustedColors[0];
		}
		else {
			this.passthroughColors[5] = this.colors[0];
		}
	}
	resetPalettes() {
		var i;
		var outPalette = this.adjustedColors[0];
		var inPalette = this.colors[0];
		for (i = 0; i < 256; ++i) {
			outPalette[i] = this.adjustColor(inPalette[i]);
		}

		outPalette = this.adjustedColors[1];
		inPalette = this.colors[1];
		for (i = 0; i < 256; ++i) {
			outPalette[i] = this.adjustColor(inPalette[i]);
		}
	}
	accessColor(layer, index) {
		return this.passthroughColors[layer][index];
	}
	adjustColorDark(color) {
		var r = (color & 0x001F);
		var g = (color & 0x03E0) >> 5;
		var b = (color & 0x7C00) >> 10;

		r = r - (r * this.blendY);
		g = g - (g * this.blendY);
		b = b - (b * this.blendY);

		return r | (g << 5) | (b << 10);
	}
	adjustColorBright(color) {
		var r = (color & 0x001F);
		var g = (color & 0x03E0) >> 5;
		var b = (color & 0x7C00) >> 10;

		r = r + ((31 - r) * this.blendY);
		g = g + ((31 - g) * this.blendY);
		b = b + ((31 - b) * this.blendY);

		return r | (g << 5) | (b << 10);
	}
	setBlendY(y) {
		if (this.blendY != y) {
			this.blendY = y;
			this.resetPalettes();
		}
	}
}

GameBoyAdvancePalette.prototype.adjustColor = GameBoyAdvancePalette.prototype.adjustColorBright;

class GameBoyAdvanceOBJ {
	constructor(oam, index) {
		this.TILE_OFFSET = 0x10000;
		this.oam = oam;

		this.index = index;
		this.x = 0;
		this.y = 0;
		this.scalerot = 0;
		this.doublesize = false;
		this.disable = 1;
		this.mode = 0;
		this.mosaic = false;
		this.multipalette = false;
		this.shape = 0;
		this.scalerotParam = 0;
		this.hflip = 0;
		this.vflip = 0;
		this.tileBase = 0;
		this.priority = 0;
		this.palette = 0;
		this.drawScanline = this.drawScanlineNormal;
		this.pushPixel = GameBoyAdvanceSoftwareRenderer.pushPixel;
		this.cachedWidth = 8;
		this.cachedHeight = 8;
	}
	drawScanlineNormal(backing, y, yOff, start, end) {
		var video = this.oam.video;
		var x;
		var underflow;
		var offset;
		var mask = this.mode | video.target2[video.LAYER_OBJ] | (this.priority << 1);
		if (this.mode == 0x10) {
			mask |= video.TARGET1_MASK;
		}
		if (video.blendMode == 1 && video.alphaEnabled) {
			mask |= video.target1[video.LAYER_OBJ];
		}

		var totalWidth = this.cachedWidth;
		if (this.x < video.HORIZONTAL_PIXELS) {
			if (this.x < start) {
				underflow = start - this.x;
				offset = start;
			}
			else {
				underflow = 0;
				offset = this.x;
			}
			if (end < this.cachedWidth + this.x) {
				totalWidth = end - this.x;
			}
		}
		else {
			underflow = start + 512 - this.x;
			offset = start;
			if (end < this.cachedWidth - underflow) {
				totalWidth = end;
			}
		}

		var localX;
		var localY;
		if (!this.vflip) {
			localY = y - yOff;
		}
		else {
			localY = this.cachedHeight - y + yOff - 1;
		}
		var localYLo = localY & 0x7;
		var mosaicX;
		var tileOffset;

		var paletteShift = this.multipalette ? 1 : 0;

		if (video.objCharacterMapping) {
			tileOffset = ((localY & 0x01F8) * this.cachedWidth) >> 6;
		}
		else {
			tileOffset = (localY & 0x01F8) << (2 - paletteShift);
		}

		if (this.mosaic) {
			mosaicX = video.objMosaicX - 1 - (video.objMosaicX + offset - 1) % video.objMosaicX;
			offset += mosaicX;
			underflow += mosaicX;
		}
		if (!this.hflip) {
			localX = underflow;
		}
		else {
			localX = this.cachedWidth - underflow - 1;
		}

		var tileRow = video.accessTile(this.TILE_OFFSET + (x & 0x4) * paletteShift, this.tileBase + (tileOffset << paletteShift) + ((localX & 0x01F8) >> (3 - paletteShift)), localYLo << paletteShift);
		for (x = underflow; x < totalWidth; ++x) {
			mosaicX = this.mosaic ? offset % video.objMosaicX : 0;
			if (!this.hflip) {
				localX = x - mosaicX;
			}
			else {
				localX = this.cachedWidth - (x - mosaicX) - 1;
			}
			if (!paletteShift) {
				if (!(x & 0x7) || (this.mosaic && !mosaicX)) {
					tileRow = video.accessTile(this.TILE_OFFSET, this.tileBase + tileOffset + (localX >> 3), localYLo);
				}
			}
			else {
				if (!(x & 0x3) || (this.mosaic && !mosaicX)) {
					tileRow = video.accessTile(this.TILE_OFFSET + (localX & 0x4), this.tileBase + (tileOffset << 1) + ((localX & 0x01F8) >> 2), localYLo << 1);
				}
			}
			this.pushPixel(video.LAYER_OBJ, this, video, tileRow, localX & 0x7, offset, backing, mask, false);
			offset++;
		}
	}
	drawScanlineAffine(backing, y, yOff, start, end) {
		var video = this.oam.video;
		var x;
		var underflow;
		var offset;
		var mask = this.mode | video.target2[video.LAYER_OBJ] | (this.priority << 1);
		if (this.mode == 0x10) {
			mask |= video.TARGET1_MASK;
		}
		if (video.blendMode == 1 && video.alphaEnabled) {
			mask |= video.target1[video.LAYER_OBJ];
		}

		var localX;
		var localY;
		var yDiff = y - yOff;
		var tileOffset;

		var paletteShift = this.multipalette ? 1 : 0;
		var totalWidth = this.cachedWidth << this.doublesize;
		var totalHeight = this.cachedHeight << this.doublesize;
		var drawWidth = totalWidth;
		if (drawWidth > video.HORIZONTAL_PIXELS) {
			totalWidth = video.HORIZONTAL_PIXELS;
		}

		if (this.x < video.HORIZONTAL_PIXELS) {
			if (this.x < start) {
				underflow = start - this.x;
				offset = start;
			}
			else {
				underflow = 0;
				offset = this.x;
			}
			if (end < drawWidth + this.x) {
				drawWidth = end - this.x;
			}
		}
		else {
			underflow = start + 512 - this.x;
			offset = start;
			if (end < drawWidth - underflow) {
				drawWidth = end;
			}
		}

		for (x = underflow; x < drawWidth; ++x) {
			localX = this.scalerotOam.a * (x - (totalWidth >> 1)) + this.scalerotOam.b * (yDiff - (totalHeight >> 1)) + (this.cachedWidth >> 1);
			localY = this.scalerotOam.c * (x - (totalWidth >> 1)) + this.scalerotOam.d * (yDiff - (totalHeight >> 1)) + (this.cachedHeight >> 1);
			if (this.mosaic) {
				localX -= (x % video.objMosaicX) * this.scalerotOam.a + (y % video.objMosaicY) * this.scalerotOam.b;
				localY -= (x % video.objMosaicX) * this.scalerotOam.c + (y % video.objMosaicY) * this.scalerotOam.d;
			}

			if (localX < 0 || localX >= this.cachedWidth || localY < 0 || localY >= this.cachedHeight) {
				offset++;
				continue;
			}

			if (video.objCharacterMapping) {
				tileOffset = ((localY & 0x01F8) * this.cachedWidth) >> 6;
			}
			else {
				tileOffset = (localY & 0x01F8) << (2 - paletteShift);
			}
			var tileRow = video.accessTile(this.TILE_OFFSET + (localX & 0x4) * paletteShift, this.tileBase + (tileOffset << paletteShift) + ((localX & 0x01F8) >> (3 - paletteShift)), (localY & 0x7) << paletteShift);
			this.pushPixel(video.LAYER_OBJ, this, video, tileRow, localX & 0x7, offset, backing, mask, false);
			offset++;
		}
	}
	recalcSize() {
		switch (this.shape) {
			case 0:
				// Square
				this.cachedHeight = this.cachedWidth = 8 << this.size;
				break;
			case 1:
				// Horizontal
				switch (this.size) {
					case 0:
						this.cachedHeight = 8;
						this.cachedWidth = 16;
						break;
					case 1:
						this.cachedHeight = 8;
						this.cachedWidth = 32;
						break;
					case 2:
						this.cachedHeight = 16;
						this.cachedWidth = 32;
						break;
					case 3:
						this.cachedHeight = 32;
						this.cachedWidth = 64;
						break;
				}
				break;
			case 2:
				// Vertical
				switch (this.size) {
					case 0:
						this.cachedHeight = 16;
						this.cachedWidth = 8;
						break;
					case 1:
						this.cachedHeight = 32;
						this.cachedWidth = 8;
						break;
					case 2:
						this.cachedHeight = 32;
						this.cachedWidth = 16;
						break;
					case 3:
						this.cachedHeight = 64;
						this.cachedWidth = 32;
						break;
				}
				break;
			default:
			// Bad!
		}
	}
}

class GameBoyAdvanceOBJLayer {
	constructor(video, index) {
		this.video = video;
		this.bg = false;
		this.index = video.LAYER_OBJ;
		this.priority = index;
		this.enabled = false;
		this.objwin = 0;
	}
	drawScanline(backing, layer, start, end) {
		var y = this.video.vcount;
		var wrappedY;
		var mosaicY;
		var obj;
		if (start >= end) {
			return;
		}
		var objs = this.video.oam.objs;
		for (var i = 0; i < objs.length; ++i) {
			obj = objs[i];
			if (obj.disable) {
				continue;
			}
			if ((obj.mode & this.video.OBJWIN_MASK) != this.objwin) {
				continue;
			}
			if (!(obj.mode & this.video.OBJWIN_MASK) && this.priority != obj.priority) {
				continue;
			}
			if (obj.y < this.video.VERTICAL_PIXELS) {
				wrappedY = obj.y;
			}
			else {
				wrappedY = obj.y - 256;
			}
			var totalHeight;
			if (!obj.scalerot) {
				totalHeight = obj.cachedHeight;
			}
			else {
				totalHeight = obj.cachedHeight << obj.doublesize;
			}
			if (!obj.mosaic) {
				mosaicY = y;
			}
			else {
				mosaicY = y - y % this.video.objMosaicY;
			}
			if (wrappedY <= y && (wrappedY + totalHeight) > y) {
				obj.drawScanline(backing, mosaicY, wrappedY, start, end);
			}
		}
	}
	objComparator(a, b) {
		return a.index - b.index;
	}
}
;



class GameBoyAdvanceSoftwareRenderer {
	constructor() {
		this.LAYER_BG0 = 0;
		this.LAYER_BG1 = 1;
		this.LAYER_BG2 = 2;
		this.LAYER_BG3 = 3;
		this.LAYER_OBJ = 4;
		this.LAYER_BACKDROP = 5;

		this.HORIZONTAL_PIXELS = 240;
		this.VERTICAL_PIXELS = 160;

		this.LAYER_MASK = 0x06;
		this.BACKGROUND_MASK = 0x01;
		this.TARGET2_MASK = 0x08;
		this.TARGET1_MASK = 0x10;
		this.OBJWIN_MASK = 0x20;
		this.WRITTEN_MASK = 0x80;

		this.PRIORITY_MASK = this.LAYER_MASK | this.BACKGROUND_MASK;

		this.drawBackdrop = new (function (video) {
			this.bg = true;
			this.priority = -1;
			this.index = video.LAYER_BACKDROP;
			this.enabled = true;

			this.drawScanline = function (backing, layer, start, end) {
				// TODO: interactions with blend modes and OBJWIN
				for (var x = start; x < end; ++x) {
					if (!(backing.stencil[x] & video.WRITTEN_MASK)) {
						backing.color[x] = video.palette.accessColor(this.index, 0);
						backing.stencil[x] = video.WRITTEN_MASK;
					}
					else if (backing.stencil[x] & video.TARGET1_MASK) {
						backing.color[x] = video.palette.mix(video.blendB, video.palette.accessColor(this.index, 0), video.blendA, backing.color[x]);
						backing.stencil[x] = video.WRITTEN_MASK;
					}
				}
			};
		})(this);
	}
	clear(mmu) {
		this.palette = new GameBoyAdvancePalette();
		this.vram = new GameBoyAdvanceVRAM(mmu.SIZE_VRAM);
		this.oam = new GameBoyAdvanceOAM(mmu.SIZE_OAM);
		this.oam.video = this;
		this.objLayers = [
			new GameBoyAdvanceOBJLayer(this, 0),
			new GameBoyAdvanceOBJLayer(this, 1),
			new GameBoyAdvanceOBJLayer(this, 2),
			new GameBoyAdvanceOBJLayer(this, 3)
		];
		this.objwinLayer = new GameBoyAdvanceOBJLayer(this, 4);
		this.objwinLayer.objwin = this.OBJWIN_MASK;

		// DISPCNT
		this.backgroundMode = 0;
		this.displayFrameSelect = 0;
		this.hblankIntervalFree = 0;
		this.objCharacterMapping = 0;
		this.forcedBlank = 1;
		this.win0 = 0;
		this.win1 = 0;
		this.objwin = 0;

		// VCOUNT
		this.vcount = -1;

		// WIN0H
		this.win0Left = 0;
		this.win0Right = 240;

		// WIN1H
		this.win1Left = 0;
		this.win1Right = 240;

		// WIN0V
		this.win0Top = 0;
		this.win0Bottom = 160;

		// WIN1V
		this.win1Top = 0;
		this.win1Bottom = 160;

		// WININ/WINOUT
		this.windows = new Array();
		for (var i = 0; i < 4; ++i) {
			this.windows.push({
				enabled: [false, false, false, false, false, true],
				special: 0
			});
		};

		// BLDCNT
		this.target1 = new Array(5);
		this.target2 = new Array(5);
		this.blendMode = 0;

		// BLDALPHA
		this.blendA = 0;
		this.blendB = 0;

		// BLDY
		this.blendY = 0;

		// MOSAIC
		this.bgMosaicX = 1;
		this.bgMosaicY = 1;
		this.objMosaicX = 1;
		this.objMosaicY = 1;

		this.lastHblank = 0;
		this.nextHblank = this.HDRAW_LENGTH;
		this.nextEvent = this.nextHblank;

		this.nextHblankIRQ = 0;
		this.nextVblankIRQ = 0;
		this.nextVcounterIRQ = 0;

		this.bg = new Array();
		for (var i = 0; i < 4; ++i) {
			this.bg.push({
				bg: true,
				index: i,
				enabled: false,
				video: this,
				vram: this.vram,
				priority: 0,
				charBase: 0,
				mosaic: false,
				multipalette: false,
				screenBase: 0,
				overflow: 0,
				size: 0,
				x: 0,
				y: 0,
				refx: 0,
				refy: 0,
				dx: 1,
				dmx: 0,
				dy: 0,
				dmy: 1,
				sx: 0,
				sy: 0,
				pushPixel: GameBoyAdvanceSoftwareRenderer.pushPixel,
				drawScanline: this.drawScanlineBGMode0
			});
		}

		this.bgModes = [
			this.drawScanlineBGMode0,
			this.drawScanlineBGMode2, // Modes 1 and 2 are identical for layers 2 and 3
			this.drawScanlineBGMode2,
			this.drawScanlineBGMode3,
			this.drawScanlineBGMode4,
			this.drawScanlineBGMode5
		];

		this.drawLayers = [
			this.bg[0],
			this.bg[1],
			this.bg[2],
			this.bg[3],
			this.objLayers[0],
			this.objLayers[1],
			this.objLayers[2],
			this.objLayers[3],
			this.objwinLayer,
			this.drawBackdrop
		];

		this.objwinActive = false;
		this.alphaEnabled = false;

		this.scanline = {
			color: new Uint16Array(this.HORIZONTAL_PIXELS),
			// Stencil format:
			// Bits 0-1: Layer
			// Bit 2: Is background
			// Bit 3: Is Target 2
			// Bit 4: Is Target 1
			// Bit 5: Is OBJ Window
			// Bit 6: Reserved
			// Bit 7: Has been written
			stencil: new Uint8Array(this.HORIZONTAL_PIXELS)
		};
		this.sharedColor = [0, 0, 0];
		this.sharedMap = {
			tile: 0,
			hflip: false,
			vflip: false,
			palette: 0
		};
	}
	clearSubsets(mmu, regions) {
		if (regions & 0x04) {
			this.palette.overwrite(new Uint16Array(mmu.SIZE_PALETTE >> 1));
		}

		if (regions & 0x08) {
			this.vram.insert(0, new Uint16Array(mmu.SIZE_VRAM >> 1));
		}

		if (regions & 0x10) {
			this.oam.overwrite(new Uint16Array(mmu.SIZE_OAM >> 1));
			this.oam.video = this;
		}
	}
	freeze() {
	}
	defrost(frost) {
	}
	setBacking(backing) {
		this.pixelData = backing;

		// Clear backing first
		for (var offset = 0; offset < this.HORIZONTAL_PIXELS * this.VERTICAL_PIXELS * 4;) {
			this.pixelData.data[offset++] = 0xFF;
			this.pixelData.data[offset++] = 0xFF;
			this.pixelData.data[offset++] = 0xFF;
			this.pixelData.data[offset++] = 0xFF;
		}
	}
	writeDisplayControl(value) {
		this.backgroundMode = value & 0x0007;
		this.displayFrameSelect = value & 0x0010;
		this.hblankIntervalFree = value & 0x0020;
		this.objCharacterMapping = value & 0x0040;
		this.forcedBlank = value & 0x0080;
		this.bg[0].enabled = value & 0x0100;
		this.bg[1].enabled = value & 0x0200;
		this.bg[2].enabled = value & 0x0400;
		this.bg[3].enabled = value & 0x0800;
		this.objLayers[0].enabled = value & 0x1000;
		this.objLayers[1].enabled = value & 0x1000;
		this.objLayers[2].enabled = value & 0x1000;
		this.objLayers[3].enabled = value & 0x1000;
		this.win0 = value & 0x2000;
		this.win1 = value & 0x4000;
		this.objwin = value & 0x8000;
		this.objwinLayer.enabled = value & 0x1000 && value & 0x8000;

		// Total hack so we can store both things that would set it to 256-color mode in the same variable
		this.bg[2].multipalette &= ~0x0001;
		this.bg[3].multipalette &= ~0x0001;
		if (this.backgroundMode > 0) {
			this.bg[2].multipalette |= 0x0001;
		}
		if (this.backgroundMode == 2) {
			this.bg[3].multipalette |= 0x0001;
		}

		this.resetLayers();
	}
	writeBackgroundControl(bg, value) {
		var bgData = this.bg[bg];
		bgData.priority = value & 0x0003;
		bgData.charBase = (value & 0x000C) << 12;
		bgData.mosaic = value & 0x0040;
		bgData.multipalette &= ~0x0080;
		if (bg < 2 || this.backgroundMode == 0) {
			bgData.multipalette |= value & 0x0080;
		}
		bgData.screenBase = (value & 0x1F00) << 3;
		bgData.overflow = value & 0x2000;
		bgData.size = (value & 0xC000) >> 14;

		this.drawLayers.sort(this.layerComparator);
	}
	writeBackgroundHOffset(bg, value) {
		this.bg[bg].x = value & 0x1FF;
	}
	writeBackgroundVOffset(bg, value) {
		this.bg[bg].y = value & 0x1FF;
	}
	writeBackgroundRefX(bg, value) {
		this.bg[bg].refx = (value << 4) / 0x1000;
		this.bg[bg].sx = this.bg[bg].refx;
	}
	writeBackgroundRefY(bg, value) {
		this.bg[bg].refy = (value << 4) / 0x1000;
		this.bg[bg].sy = this.bg[bg].refy;
	}
	writeBackgroundParamA(bg, value) {
		this.bg[bg].dx = (value << 16) / 0x1000000;
	}
	writeBackgroundParamB(bg, value) {
		this.bg[bg].dmx = (value << 16) / 0x1000000;
	}
	writeBackgroundParamC(bg, value) {
		this.bg[bg].dy = (value << 16) / 0x1000000;
	}
	writeBackgroundParamD(bg, value) {
		this.bg[bg].dmy = (value << 16) / 0x1000000;
	}
	writeWin0H(value) {
		this.win0Left = (value & 0xFF00) >> 8;
		this.win0Right = Math.min(this.HORIZONTAL_PIXELS, value & 0x00FF);
		if (this.win0Left > this.win0Right) {
			this.win0Right = this.HORIZONTAL_PIXELS;
		}
	}
	writeWin1H(value) {
		this.win1Left = (value & 0xFF00) >> 8;
		this.win1Right = Math.min(this.HORIZONTAL_PIXELS, value & 0x00FF);
		if (this.win1Left > this.win1Right) {
			this.win1Right = this.HORIZONTAL_PIXELS;
		}
	}
	writeWin0V(value) {
		this.win0Top = (value & 0xFF00) >> 8;
		this.win0Bottom = Math.min(this.VERTICAL_PIXELS, value & 0x00FF);
		if (this.win0Top > this.win0Bottom) {
			this.win0Bottom = this.VERTICAL_PIXELS;
		}
	}
	writeWin1V(value) {
		this.win1Top = (value & 0xFF00) >> 8;
		this.win1Bottom = Math.min(this.VERTICAL_PIXELS, value & 0x00FF);
		if (this.win1Top > this.win1Bottom) {
			this.win1Bottom = this.VERTICAL_PIXELS;
		}
	}
	writeWindow(index, value) {
		var window = this.windows[index];
		window.enabled[0] = value & 0x01;
		window.enabled[1] = value & 0x02;
		window.enabled[2] = value & 0x04;
		window.enabled[3] = value & 0x08;
		window.enabled[4] = value & 0x10;
		window.special = value & 0x20;
	}
	writeWinIn(value) {
		this.writeWindow(0, value);
		this.writeWindow(1, value >> 8);
	}
	writeWinOut(value) {
		this.writeWindow(2, value);
		this.writeWindow(3, value >> 8);
	}
	writeBlendControl(value) {
		this.target1[0] = !!(value & 0x0001) * this.TARGET1_MASK;
		this.target1[1] = !!(value & 0x0002) * this.TARGET1_MASK;
		this.target1[2] = !!(value & 0x0004) * this.TARGET1_MASK;
		this.target1[3] = !!(value & 0x0008) * this.TARGET1_MASK;
		this.target1[4] = !!(value & 0x0010) * this.TARGET1_MASK;
		this.target1[5] = !!(value & 0x0020) * this.TARGET1_MASK;
		this.target2[0] = !!(value & 0x0100) * this.TARGET2_MASK;
		this.target2[1] = !!(value & 0x0200) * this.TARGET2_MASK;
		this.target2[2] = !!(value & 0x0400) * this.TARGET2_MASK;
		this.target2[3] = !!(value & 0x0800) * this.TARGET2_MASK;
		this.target2[4] = !!(value & 0x1000) * this.TARGET2_MASK;
		this.target2[5] = !!(value & 0x2000) * this.TARGET2_MASK;
		this.blendMode = (value & 0x00C0) >> 6;

		switch (this.blendMode) {
			case 1:
			// Alpha
			// Fall through
			case 0:
				// Normal
				this.palette.makeNormalPalettes();
				break;
			case 2:
				// Brighter
				this.palette.makeBrightPalettes(value & 0x3F);
				break;
			case 3:
				// Darker
				this.palette.makeDarkPalettes(value & 0x3F);
				break;
		}
	}
	setBlendEnabled(layer, enabled, override) {
		this.alphaEnabled = enabled && override == 1;
		if (enabled) {
			switch (override) {
				case 1:
				// Alpha
				// Fall through
				case 0:
					// Normal
					this.palette.makeNormalPalette(layer);
					break;
				case 2:
				// Brighter
				case 3:
					// Darker
					this.palette.makeSpecialPalette(layer);
					break;
			}
		}
		else {
			this.palette.makeNormalPalette(layer);
		}
	}
	writeBlendAlpha(value) {
		this.blendA = (value & 0x001F) / 16;
		if (this.blendA > 1) {
			this.blendA = 1;
		}
		this.blendB = ((value & 0x1F00) >> 8) / 16;
		if (this.blendB > 1) {
			this.blendB = 1;
		}
	}
	writeBlendY(value) {
		this.blendY = value;
		this.palette.setBlendY(value >= 16 ? 1 : (value / 16));
	}
	writeMosaic(value) {
		this.bgMosaicX = (value & 0xF) + 1;
		this.bgMosaicY = ((value >> 4) & 0xF) + 1;
		this.objMosaicX = ((value >> 8) & 0xF) + 1;
		this.objMosaicY = ((value >> 12) & 0xF) + 1;
	}
	resetLayers() {
		if (this.backgroundMode > 1) {
			this.bg[0].enabled = false;
			this.bg[1].enabled = false;
		}
		if (this.bg[2].enabled) {
			this.bg[2].drawScanline = this.bgModes[this.backgroundMode];
		}
		if ((this.backgroundMode == 0 || this.backgroundMode == 2)) {
			if (this.bg[3].enabled) {
				this.bg[3].drawScanline = this.bgModes[this.backgroundMode];
			}
		}
		else {
			this.bg[3].enabled = false;
		}
		this.drawLayers.sort(this.layerComparator);
	}
	layerComparator(a, b) {
		var diff = b.priority - a.priority;
		if (!diff) {
			if (a.bg && !b.bg) {
				return -1;
			}
			else if (!a.bg && b.bg) {
				return 1;
			}

			return b.index - a.index;
		}
		return diff;
	}
	accessMapMode0(base, size, x, yBase, out) {
		var offset = base + ((x >> 2) & 0x3E) + yBase;

		if (size & 1) {
			offset += (x & 0x100) << 3;
		}

		var mem = this.vram.loadU16(offset);
		out.tile = mem & 0x03FF;
		out.hflip = mem & 0x0400;
		out.vflip = mem & 0x0800;
		out.palette = (mem & 0xF000) >> 8; // This is shifted up 4 to make pushPixel faster
	}
	accessMapMode1(base, size, x, yBase, out) {
		var offset = base + (x >> 3) + yBase;

		out.tile = this.vram.loadU8(offset);
	}
	accessTile(base, tile, y) {
		var offset = base + (tile << 5);
		offset |= y << 2;

		return this.vram.load32(offset);
	}
	static pushPixel(layer, map, video, row, x, offset, backing, mask, raw) {
		var index;
		if (!raw) {
			if (this.multipalette) {
				index = (row >> (x << 3)) & 0xFF;
			}
			else {
				index = (row >> (x << 2)) & 0xF;
			}
			// Index 0 is transparent
			if (!index) {
				return;
			}
			else if (!this.multipalette) {
				index |= map.palette;
			}
		}

		var stencil = video.WRITTEN_MASK;
		var oldStencil = backing.stencil[offset];
		var blend = video.blendMode;
		if (video.objwinActive) {
			if (oldStencil & video.OBJWIN_MASK) {
				if (video.windows[3].enabled[layer]) {
					video.setBlendEnabled(layer, video.windows[3].special && video.target1[layer], blend);
					if (video.windows[3].special && video.alphaEnabled) {
						mask |= video.target1[layer];
					}
					stencil |= video.OBJWIN_MASK;
				}
				else {
					return;
				}
			}
			else if (video.windows[2].enabled[layer]) {
				video.setBlendEnabled(layer, video.windows[2].special && video.target1[layer], blend);
				if (video.windows[2].special && video.alphaEnabled) {
					mask |= video.target1[layer];
				}
			}
			else {
				return;
			}
		}

		if ((mask & video.TARGET1_MASK) && (oldStencil & video.TARGET2_MASK)) {
			video.setBlendEnabled(layer, true, 1);
		}

		var pixel = raw ? row : video.palette.accessColor(layer, index);

		if (mask & video.TARGET1_MASK) {
			video.setBlendEnabled(layer, !!blend, blend);
		}
		var highPriority = (mask & video.PRIORITY_MASK) < (oldStencil & video.PRIORITY_MASK);
		// Backgrounds can draw over each other, too.
		if ((mask & video.PRIORITY_MASK) == (oldStencil & video.PRIORITY_MASK)) {
			highPriority = mask & video.BACKGROUND_MASK;
		}

		if (!(oldStencil & video.WRITTEN_MASK)) {
			// Nothing here yet, just continue
			stencil |= mask;
		}
		else if (highPriority) {
			// We are higher priority
			if (mask & video.TARGET1_MASK && oldStencil & video.TARGET2_MASK) {
				pixel = video.palette.mix(video.blendA, pixel, video.blendB, backing.color[offset]);
			}
			// We just drew over something, so it doesn't make sense for us to be a TARGET1 anymore...
			stencil |= mask & ~video.TARGET1_MASK;
		}
		else if ((mask & video.PRIORITY_MASK) > (oldStencil & video.PRIORITY_MASK)) {
			// We're below another layer, but might be the blend target for it
			stencil = oldStencil & ~(video.TARGET1_MASK | video.TARGET2_MASK);
			if (mask & video.TARGET2_MASK && oldStencil & video.TARGET1_MASK) {
				pixel = video.palette.mix(video.blendB, pixel, video.blendA, backing.color[offset]);
			}
			else {
				return;
			}
		}
		else {
			return;
		}

		if (mask & video.OBJWIN_MASK) {
			// We ARE the object window, don't draw pixels!
			backing.stencil[offset] |= video.OBJWIN_MASK;
			return;
		}
		backing.color[offset] = pixel;
		backing.stencil[offset] = stencil;
	}
	identity(x) {
		return x;
	}
	drawScanlineBlank(backing) {
		for (var x = 0; x < this.HORIZONTAL_PIXELS; ++x) {
			backing.color[x] = 0xFFFF;
			backing.stencil[x] = 0;
		}
	}
	prepareScanline(backing) {
		for (var x = 0; x < this.HORIZONTAL_PIXELS; ++x) {
			backing.stencil[x] = this.target2[this.LAYER_BACKDROP];
		}
	}
	drawScanlineBGMode0(backing, bg, start, end) {
		var video = this.video;
		var x;
		var y = video.vcount;
		var offset = start;
		var xOff = bg.x;
		var yOff = bg.y;
		var localX;
		var localXLo;
		var localY = y + yOff;
		if (this.mosaic) {
			localY -= y % video.bgMosaicY;
		}
		var localYLo = localY & 0x7;
		var mosaicX;
		var screenBase = bg.screenBase;
		var charBase = bg.charBase;
		var size = bg.size;
		var index = bg.index;
		var map = video.sharedMap;
		var paletteShift = bg.multipalette ? 1 : 0;
		var mask = video.target2[index] | (bg.priority << 1) | video.BACKGROUND_MASK;
		if (video.blendMode == 1 && video.alphaEnabled) {
			mask |= video.target1[index];
		}

		var yBase = (localY << 3) & 0x7C0;
		if (size == 2) {
			yBase += (localY << 3) & 0x800;
		}
		else if (size == 3) {
			yBase += (localY << 4) & 0x1000;
		}

		var xMask;
		if (size & 1) {
			xMask = 0x1FF;
		}
		else {
			xMask = 0xFF;
		}

		video.accessMapMode0(screenBase, size, (start + xOff) & xMask, yBase, map);
		var tileRow = video.accessTile(charBase, map.tile << paletteShift, (!map.vflip ? localYLo : 7 - localYLo) << paletteShift);
		for (x = start; x < end; ++x) {
			localX = (x + xOff) & xMask;
			mosaicX = this.mosaic ? offset % video.bgMosaicX : 0;
			localX -= mosaicX;
			localXLo = localX & 0x7;
			if (!paletteShift) {
				if (!localXLo || (this.mosaic && !mosaicX)) {
					video.accessMapMode0(screenBase, size, localX, yBase, map);
					tileRow = video.accessTile(charBase, map.tile, !map.vflip ? localYLo : 7 - localYLo);
					if (!tileRow && !localXLo) {
						x += 7;
						offset += 8;
						continue;
					}
				}
			}
			else {
				if (!localXLo || (this.mosaic && !mosaicX)) {
					video.accessMapMode0(screenBase, size, localX, yBase, map);
				}
				if (!(localXLo & 0x3) || (this.mosaic && !mosaicX)) {
					tileRow = video.accessTile(charBase + (!!(localX & 0x4) == !map.hflip ? 4 : 0), map.tile << 1, (!map.vflip ? localYLo : 7 - localYLo) << 1);
					if (!tileRow && !(localXLo & 0x3)) {
						x += 3;
						offset += 4;
						continue;
					}
				}
			}
			if (map.hflip) {
				localXLo = 7 - localXLo;
			}
			bg.pushPixel(index, map, video, tileRow, localXLo, offset, backing, mask, false);
			offset++;
		}
	}
	drawScanlineBGMode2(backing, bg, start, end) {
		var video = this.video;
		var x;
		var y = video.vcount;
		var offset = start;
		var localX;
		var localY;
		var screenBase = bg.screenBase;
		var charBase = bg.charBase;
		var size = bg.size;
		var sizeAdjusted = 128 << size;
		var index = bg.index;
		var map = video.sharedMap;
		var color;
		var mask = video.target2[index] | (bg.priority << 1) | video.BACKGROUND_MASK;
		if (video.blendMode == 1 && video.alphaEnabled) {
			mask |= video.target1[index];
		}

		var yBase;

		for (x = start; x < end; ++x) {
			localX = bg.dx * x + bg.sx;
			localY = bg.dy * x + bg.sy;
			if (this.mosaic) {
				localX -= (x % video.bgMosaicX) * bg.dx + (y % video.bgMosaicY) * bg.dmx;
				localY -= (x % video.bgMosaicX) * bg.dy + (y % video.bgMosaicY) * bg.dmy;
			}
			if (bg.overflow) {
				localX &= sizeAdjusted - 1;
				if (localX < 0) {
					localX += sizeAdjusted;
				}
				localY &= sizeAdjusted - 1;
				if (localY < 0) {
					localY += sizeAdjusted;
				}
			}
			else if (localX < 0 || localY < 0 || localX >= sizeAdjusted || localY >= sizeAdjusted) {
				offset++;
				continue;
			}
			yBase = ((localY << 1) & 0x7F0) << size;
			video.accessMapMode1(screenBase, size, localX, yBase, map);
			color = this.vram.loadU8(charBase + (map.tile << 6) + ((localY & 0x7) << 3) + (localX & 0x7));
			bg.pushPixel(index, map, video, color, 0, offset, backing, mask, false);
			offset++;
		}
	}
	drawScanlineBGMode3(backing, bg, start, end) {
		var video = this.video;
		var x;
		var y = video.vcount;
		var offset = start;
		var localX;
		var localY;
		var index = bg.index;
		var map = video.sharedMap;
		var color;
		var mask = video.target2[index] | (bg.priority << 1) | video.BACKGROUND_MASK;
		if (video.blendMode == 1 && video.alphaEnabled) {
			mask |= video.target1[index];
		}

		var yBase;

		for (x = start; x < end; ++x) {
			localX = bg.dx * x + bg.sx;
			localY = bg.dy * x + bg.sy;
			if (this.mosaic) {
				localX -= (x % video.bgMosaicX) * bg.dx + (y % video.bgMosaicY) * bg.dmx;
				localY -= (x % video.bgMosaicX) * bg.dy + (y % video.bgMosaicY) * bg.dmy;
			}
			if (localX < 0 || localY < 0 || localX >= video.HORIZONTAL_PIXELS || localY >= video.VERTICAL_PIXELS) {
				offset++;
				continue;
			}
			color = this.vram.loadU16(((localY * video.HORIZONTAL_PIXELS) + localX) << 1);
			bg.pushPixel(index, map, video, color, 0, offset, backing, mask, true);
			offset++;
		}
	}
	drawScanlineBGMode4(backing, bg, start, end) {
		var video = this.video;
		var x;
		var y = video.vcount;
		var offset = start;
		var localX;
		var localY;
		var charBase = 0;
		if (video.displayFrameSelect) {
			charBase += 0xA000;
		}
		var size = bg.size;
		var index = bg.index;
		var map = video.sharedMap;
		var color;
		var mask = video.target2[index] | (bg.priority << 1) | video.BACKGROUND_MASK;
		if (video.blendMode == 1 && video.alphaEnabled) {
			mask |= video.target1[index];
		}

		var yBase;

		for (x = start; x < end; ++x) {
			localX = bg.dx * x + bg.sx;
			localY = 0 | bg.dy * x + bg.sy;
			if (this.mosaic) {
				localX -= (x % video.bgMosaicX) * bg.dx + (y % video.bgMosaicY) * bg.dmx;
				localY -= (x % video.bgMosaicX) * bg.dy + (y % video.bgMosaicY) * bg.dmy;
			}
			yBase = (localY << 2) & 0x7E0;
			if (localX < 0 || localY < 0 || localX >= video.HORIZONTAL_PIXELS || localY >= video.VERTICAL_PIXELS) {
				offset++;
				continue;
			}
			color = this.vram.loadU8(charBase + (localY * video.HORIZONTAL_PIXELS) + localX);
			bg.pushPixel(index, map, video, color, 0, offset, backing, mask, false);
			offset++;
		}
	}
	drawScanlineBGMode5(backing, bg, start, end) {
		var video = this.video;
		var x;
		var y = video.vcount;
		var offset = start;
		var localX;
		var localY;
		var charBase = 0;
		if (video.displayFrameSelect) {
			charBase += 0xA000;
		}
		var index = bg.index;
		var map = video.sharedMap;
		var color;
		var mask = video.target2[index] | (bg.priority << 1) | video.BACKGROUND_MASK;
		if (video.blendMode == 1 && video.alphaEnabled) {
			mask |= video.target1[index];
		}

		var yBase;

		for (x = start; x < end; ++x) {
			localX = bg.dx * x + bg.sx;
			localY = bg.dy * x + bg.sy;
			if (this.mosaic) {
				localX -= (x % video.bgMosaicX) * bg.dx + (y % video.bgMosaicY) * bg.dmx;
				localY -= (x % video.bgMosaicX) * bg.dy + (y % video.bgMosaicY) * bg.dmy;
			}
			if (localX < 0 || localY < 0 || localX >= 160 || localY >= 128) {
				offset++;
				continue;
			}
			color = this.vram.loadU16(charBase + ((localY * 160) + localX) << 1);
			bg.pushPixel(index, map, video, color, 0, offset, backing, mask, true);
			offset++;
		}
	}
	drawScanline(y) {
		var backing = this.scanline;
		if (this.forcedBlank) {
			this.drawScanlineBlank(backing);
			return;
		}
		this.prepareScanline(backing);
		var layer;
		var firstStart;
		var firstEnd;
		var lastStart;
		var lastEnd;
		this.vcount = y;
		// Draw lower priority first and then draw over them
		for (var i = 0; i < this.drawLayers.length; ++i) {
			layer = this.drawLayers[i];
			if (!layer.enabled) {
				continue;
			}
			this.objwinActive = false;
			if (!(this.win0 || this.win1 || this.objwin)) {
				this.setBlendEnabled(layer.index, this.target1[layer.index], this.blendMode);
				layer.drawScanline(backing, layer, 0, this.HORIZONTAL_PIXELS);
			}
			else {
				firstStart = 0;
				firstEnd = this.HORIZONTAL_PIXELS;
				lastStart = 0;
				lastEnd = this.HORIZONTAL_PIXELS;
				if (this.win0 && y >= this.win0Top && y < this.win0Bottom) {
					if (this.windows[0].enabled[layer.index]) {
						this.setBlendEnabled(layer.index, this.windows[0].special && this.target1[layer.index], this.blendMode);
						layer.drawScanline(backing, layer, this.win0Left, this.win0Right);
					}
					firstStart = Math.max(firstStart, this.win0Left);
					firstEnd = Math.min(firstEnd, this.win0Left);
					lastStart = Math.max(lastStart, this.win0Right);
					lastEnd = Math.min(lastEnd, this.win0Right);
				}
				if (this.win1 && y >= this.win1Top && y < this.win1Bottom) {
					if (this.windows[1].enabled[layer.index]) {
						this.setBlendEnabled(layer.index, this.windows[1].special && this.target1[layer.index], this.blendMode);
						if (!this.windows[0].enabled[layer.index] && (this.win1Left < firstStart || this.win1Right < lastStart)) {
							// We've been cut in two by window 0!
							layer.drawScanline(backing, layer, this.win1Left, firstStart);
							layer.drawScanline(backing, layer, lastEnd, this.win1Right);
						}
						else {
							layer.drawScanline(backing, layer, this.win1Left, this.win1Right);
						}
					}
					firstStart = Math.max(firstStart, this.win1Left);
					firstEnd = Math.min(firstEnd, this.win1Left);
					lastStart = Math.max(lastStart, this.win1Right);
					lastEnd = Math.min(lastEnd, this.win1Right);
				}
				// Do last two
				if (this.windows[2].enabled[layer.index] || (this.objwin && this.windows[3].enabled[layer.index])) {
					// WINOUT/OBJWIN
					this.objwinActive = this.objwin;
					this.setBlendEnabled(layer.index, this.windows[2].special && this.target1[layer.index], this.blendMode); // Window 3 handled in pushPixel
					if (firstEnd > lastStart) {
						layer.drawScanline(backing, layer, 0, this.HORIZONTAL_PIXELS);
					}
					else {
						if (firstEnd) {
							layer.drawScanline(backing, layer, 0, firstEnd);
						}
						if (lastStart < this.HORIZONTAL_PIXELS) {
							layer.drawScanline(backing, layer, lastStart, this.HORIZONTAL_PIXELS);
						}
						if (lastEnd < firstStart) {
							layer.drawScanline(backing, layer, lastEnd, firstStart);
						}
					}
				}

				this.setBlendEnabled(this.LAYER_BACKDROP, this.target1[this.LAYER_BACKDROP] && this.windows[2].special, this.blendMode);
			}
			if (layer.bg) {
				layer.sx += layer.dmx;
				layer.sy += layer.dmy;
			}
		}

		this.finishScanline(backing);
	}
	finishScanline(backing) {
		var color;
		var bd = this.palette.accessColor(this.LAYER_BACKDROP, 0);
		var xx = this.vcount * this.HORIZONTAL_PIXELS * 4;
		var isTarget2 = this.target2[this.LAYER_BACKDROP];
		for (var x = 0; x < this.HORIZONTAL_PIXELS; ++x) {
			if (backing.stencil[x] & this.WRITTEN_MASK) {
				color = backing.color[x];
				if (isTarget2 && backing.stencil[x] & this.TARGET1_MASK) {
					color = this.palette.mix(this.blendA, color, this.blendB, bd);
				}
				this.palette.convert16To32(color, this.sharedColor);
			}
			else {
				this.palette.convert16To32(bd, this.sharedColor);
			}
			this.pixelData.data[xx++] = this.sharedColor[0];
			this.pixelData.data[xx++] = this.sharedColor[1];
			this.pixelData.data[xx++] = this.sharedColor[2];
			xx++;
		}
	}
	startDraw() {
		// Nothing to do
	}
	finishDraw(caller) {
		this.bg[2].sx = this.bg[2].refx;
		this.bg[2].sy = this.bg[2].refy;
		this.bg[3].sx = this.bg[3].refx;
		this.bg[3].sy = this.bg[3].refy;
		caller.finishDraw(this.pixelData);
	}
}
class GameBoyAdvanceInterruptHandler {
	constructor() {
		// this.inherit();
		this.FREQUENCY = 0x1000000;

		this.cpu = null;
		this.enable = false;

		this.IRQ_VBLANK = 0x0;
		this.IRQ_HBLANK = 0x1;
		this.IRQ_VCOUNTER = 0x2;
		this.IRQ_TIMER0 = 0x3;
		this.IRQ_TIMER1 = 0x4;
		this.IRQ_TIMER2 = 0x5;
		this.IRQ_TIMER3 = 0x6;
		this.IRQ_SIO = 0x7;
		this.IRQ_DMA0 = 0x8;
		this.IRQ_DMA1 = 0x9;
		this.IRQ_DMA2 = 0xa;
		this.IRQ_DMA3 = 0xb;
		this.IRQ_KEYPAD = 0xc;
		this.IRQ_GAMEPAK = 0xd;

		this.MASK_VBLANK = 0x0001;
		this.MASK_HBLANK = 0x0002;
		this.MASK_VCOUNTER = 0x0004;
		this.MASK_TIMER0 = 0x0008;
		this.MASK_TIMER1 = 0x0010;
		this.MASK_TIMER2 = 0x0020;
		this.MASK_TIMER3 = 0x0040;
		this.MASK_SIO = 0x0080;
		this.MASK_DMA0 = 0x0100;
		this.MASK_DMA1 = 0x0200;
		this.MASK_DMA2 = 0x0400;
		this.MASK_DMA3 = 0x0800;
		this.MASK_KEYPAD = 0x1000;
		this.MASK_GAMEPAK = 0x2000;
	}
	clear() {
		this.enable = false;
		this.enabledIRQs = 0;
		this.interruptFlags = 0;

		this.dma = new Array();
		for (var i = 0; i < 4; ++i) {
			this.dma.push({
				source: 0,
				dest: 0,
				count: 0,
				nextSource: 0,
				nextDest: 0,
				nextCount: 0,
				srcControl: 0,
				dstControl: 0,
				repeat: false,
				width: 0,
				drq: false,
				timing: 0,
				doIrq: false,
				enable: false,
				nextIRQ: 0
			});
		}

		this.timersEnabled = 0;
		this.timers = new Array();
		for (var i = 0; i < 4; ++i) {
			this.timers.push({
				reload: 0,
				oldReload: 0,
				prescaleBits: 0,
				countUp: false,
				doIrq: false,
				enable: false,
				lastEvent: 0,
				nextEvent: 0,
				overflowInterval: 1
			});
		}

		this.nextEvent = 0;
		this.springIRQ = false;
		this.resetSP();
	}
	freeze() {
		return {
			enable: this.enable,
			enabledIRQs: this.enabledIRQs,
			interruptFlags: this.interruptFlags,
			dma: this.dma,
			timers: this.timers,
			nextEvent: this.nextEvent,
			springIRQ: this.springIRQ
		};
	}
	defrost(frost) {
		this.enable = frost.enable;
		this.enabledIRQs = frost.enabledIRQs;
		this.interruptFlags = frost.interruptFlags;
		this.dma = frost.dma;
		this.timers = frost.timers;
		this.timersEnabled = 0;
		if (this.timers[0].enable) {
			++this.timersEnabled;
		}
		if (this.timers[1].enable) {
			++this.timersEnabled;
		}
		if (this.timers[2].enable) {
			++this.timersEnabled;
		}
		if (this.timers[3].enable) {
			++this.timersEnabled;
		}
		this.nextEvent = frost.nextEvent;
		this.springIRQ = frost.springIRQ;
	}
	updateTimers() {
		if (this.nextEvent > this.cpu.cycles) {
			return;
		}

		if (this.springIRQ) {
			this.cpu.raiseIRQ();
			this.springIRQ = false;
		}

		this.video.updateTimers(this.cpu);
		this.audio.updateTimers();
		if (this.timersEnabled) {
			var timer = this.timers[0];
			if (timer.enable) {
				if (this.cpu.cycles >= timer.nextEvent) {
					timer.lastEvent = timer.nextEvent;
					timer.nextEvent += timer.overflowInterval;
					this.io.registers[this.io.TM0CNT_LO >> 1] = timer.reload;
					timer.oldReload = timer.reload;

					if (timer.doIrq) {
						this.raiseIRQ(this.IRQ_TIMER0);
					}

					if (this.audio.enabled) {
						if (
							this.audio.enableChannelA &&
							!this.audio.soundTimerA &&
							this.audio.dmaA >= 0
						) {
							this.audio.sampleFifoA();
						}

						if (
							this.audio.enableChannelB &&
							!this.audio.soundTimerB &&
							this.audio.dmaB >= 0
						) {
							this.audio.sampleFifoB();
						}
					}

					timer = this.timers[1];
					if (timer.countUp) {
						if (
							++this.io.registers[this.io.TM1CNT_LO >> 1] ==
							0x10000
						) {
							timer.nextEvent = this.cpu.cycles;
						}
					}
				}
			}

			timer = this.timers[1];
			if (timer.enable) {
				if (this.cpu.cycles >= timer.nextEvent) {
					timer.lastEvent = timer.nextEvent;
					timer.nextEvent += timer.overflowInterval;
					if (
						!timer.countUp ||
						this.io.registers[this.io.TM1CNT_LO >> 1] == 0x10000
					) {
						this.io.registers[this.io.TM1CNT_LO >> 1] =
							timer.reload;
					}
					timer.oldReload = timer.reload;

					if (timer.doIrq) {
						this.raiseIRQ(this.IRQ_TIMER1);
					}

					if (timer.countUp) {
						timer.nextEvent = 0;
					}

					if (this.audio.enabled) {
						if (
							this.audio.enableChannelA &&
							this.audio.soundTimerA &&
							this.audio.dmaA >= 0
						) {
							this.audio.sampleFifoA();
						}

						if (
							this.audio.enableChannelB &&
							this.audio.soundTimerB &&
							this.audio.dmaB >= 0
						) {
							this.audio.sampleFifoB();
						}
					}

					timer = this.timers[2];
					if (timer.countUp) {
						if (
							++this.io.registers[this.io.TM2CNT_LO >> 1] ==
							0x10000
						) {
							timer.nextEvent = this.cpu.cycles;
						}
					}
				}
			}

			timer = this.timers[2];
			if (timer.enable) {
				if (this.cpu.cycles >= timer.nextEvent) {
					timer.lastEvent = timer.nextEvent;
					timer.nextEvent += timer.overflowInterval;
					if (
						!timer.countUp ||
						this.io.registers[this.io.TM2CNT_LO >> 1] == 0x10000
					) {
						this.io.registers[this.io.TM2CNT_LO >> 1] =
							timer.reload;
					}
					timer.oldReload = timer.reload;

					if (timer.doIrq) {
						this.raiseIRQ(this.IRQ_TIMER2);
					}

					if (timer.countUp) {
						timer.nextEvent = 0;
					}

					timer = this.timers[3];
					if (timer.countUp) {
						if (
							++this.io.registers[this.io.TM3CNT_LO >> 1] ==
							0x10000
						) {
							timer.nextEvent = this.cpu.cycles;
						}
					}
				}
			}

			timer = this.timers[3];
			if (timer.enable) {
				if (this.cpu.cycles >= timer.nextEvent) {
					timer.lastEvent = timer.nextEvent;
					timer.nextEvent += timer.overflowInterval;
					if (
						!timer.countUp ||
						this.io.registers[this.io.TM3CNT_LO >> 1] == 0x10000
					) {
						this.io.registers[this.io.TM3CNT_LO >> 1] =
							timer.reload;
					}
					timer.oldReload = timer.reload;

					if (timer.doIrq) {
						this.raiseIRQ(this.IRQ_TIMER3);
					}

					if (timer.countUp) {
						timer.nextEvent = 0;
					}
				}
			}
		}

		var dma = this.dma[0];
		if (
			dma.enable &&
			dma.doIrq &&
			dma.nextIRQ &&
			this.cpu.cycles >= dma.nextIRQ
		) {
			dma.nextIRQ = 0;
			this.raiseIRQ(this.IRQ_DMA0);
		}

		dma = this.dma[1];
		if (
			dma.enable &&
			dma.doIrq &&
			dma.nextIRQ &&
			this.cpu.cycles >= dma.nextIRQ
		) {
			dma.nextIRQ = 0;
			this.raiseIRQ(this.IRQ_DMA1);
		}

		dma = this.dma[2];
		if (
			dma.enable &&
			dma.doIrq &&
			dma.nextIRQ &&
			this.cpu.cycles >= dma.nextIRQ
		) {
			dma.nextIRQ = 0;
			this.raiseIRQ(this.IRQ_DMA2);
		}

		dma = this.dma[3];
		if (
			dma.enable &&
			dma.doIrq &&
			dma.nextIRQ &&
			this.cpu.cycles >= dma.nextIRQ
		) {
			dma.nextIRQ = 0;
			this.raiseIRQ(this.IRQ_DMA3);
		}

		this.pollNextEvent();
	}
	resetSP() {
		this.cpu.switchMode(this.cpu.MODE_SUPERVISOR);
		this.cpu.gprs[this.cpu.SP] = 0x3007fe0;
		this.cpu.switchMode(this.cpu.MODE_IRQ);
		this.cpu.gprs[this.cpu.SP] = 0x3007fa0;
		this.cpu.switchMode(this.cpu.MODE_SYSTEM);
		this.cpu.gprs[this.cpu.SP] = 0x3007f00;
	}
	swi32(opcode) {
		this.swi(opcode >> 16);
	}
	swi(opcode) {
		if (this.core.mmu.bios.real) {
			this.cpu.raiseTrap();
			return;
		}

		switch (opcode) {
			case 0x00:
				// SoftReset
				var mem = this.core.mmu.memory[
					this.core.mmu.REGION_WORKING_IRAM
				];
				var flag = mem.loadU8(0x7ffa);
				for (var i = 0x7e00; i < 0x8000; i += 4) {
					mem.store32(i, 0);
				}
				this.resetSP();
				if (!flag) {
					this.cpu.gprs[this.cpu.LR] = 0x08000000;
				} else {
					this.cpu.gprs[this.cpu.LR] = 0x02000000;
				}
				this.cpu.switchExecMode(this.cpu.MODE_ARM);
				this.cpu.instruction.writesPC = true;
				this.cpu.gprs[this.cpu.PC] = this.cpu.gprs[this.cpu.LR];
				break;
			case 0x01:
				// RegisterRamReset
				var regions = this.cpu.gprs[0];
				if (regions & 0x01) {
					this.core.mmu.memory[
						this.core.mmu.REGION_WORKING_RAM
					] = new MemoryBlock(this.core.mmu.SIZE_WORKING_RAM, 9);
				}
				if (regions & 0x02) {
					for (
						var i = 0;
						i < this.core.mmu.SIZE_WORKING_IRAM - 0x200;
						i += 4
					) {
						this.core.mmu.memory[
							this.core.mmu.REGION_WORKING_IRAM
						].store32(i, 0);
					}
				}
				if (regions & 0x1c) {
					this.video.renderPath.clearSubsets(this.core.mmu, regions);
				}
				if (regions & 0xe0) {
					this.core.STUB("Unimplemented RegisterRamReset");
				}
				break;
			case 0x02:
				// Halt
				this.halt();
				break;
			case 0x05:
				// VBlankIntrWait
				this.cpu.gprs[0] = 1;
				this.cpu.gprs[1] = 1;
			// Fall through:
			case 0x04:
				// IntrWait
				if (!this.enable) {
					this.io.store16(this.io.IME, 1);
				}
				if (
					!this.cpu.gprs[0] &&
					this.interruptFlags & this.cpu.gprs[1]
				) {
					return;
				}
				this.dismissIRQs(0xffffffff);
				this.cpu.raiseTrap();
				break;
			case 0x06:
				// Div
				var result = (this.cpu.gprs[0] | 0) / (this.cpu.gprs[1] | 0);
				var mod = (this.cpu.gprs[0] | 0) % (this.cpu.gprs[1] | 0);
				this.cpu.gprs[0] = result | 0;
				this.cpu.gprs[1] = mod | 0;
				this.cpu.gprs[3] = Math.abs(result | 0);
				break;
			case 0x07:
				// DivArm
				var result = (this.cpu.gprs[1] | 0) / (this.cpu.gprs[0] | 0);
				var mod = (this.cpu.gprs[1] | 0) % (this.cpu.gprs[0] | 0);
				this.cpu.gprs[0] = result | 0;
				this.cpu.gprs[1] = mod | 0;
				this.cpu.gprs[3] = Math.abs(result | 0);
				break;
			case 0x08:
				// Sqrt
				var root = Math.sqrt(this.cpu.gprs[0]);
				this.cpu.gprs[0] = root | 0; // Coerce down to int
				break;
			case 0x0a:
				// ArcTan2
				var x = this.cpu.gprs[0] / 16384;
				var y = this.cpu.gprs[1] / 16384;
				this.cpu.gprs[0] = (Math.atan2(y, x) / (2 * Math.PI)) * 0x10000;
				break;
			case 0x0b:
				// CpuSet
				var source = this.cpu.gprs[0];
				var dest = this.cpu.gprs[1];
				var mode = this.cpu.gprs[2];
				var count = mode & 0x000fffff;
				var fill = mode & 0x01000000;
				var wordsize = mode & 0x04000000 ? 4 : 2;
				if (fill) {
					if (wordsize == 4) {
						source &= 0xfffffffc;
						dest &= 0xfffffffc;
						var word = this.cpu.mmu.load32(source);
						for (var i = 0; i < count; ++i) {
							this.cpu.mmu.store32(dest + (i << 2), word);
						}
					} else {
						source &= 0xfffffffe;
						dest &= 0xfffffffe;
						var word = this.cpu.mmu.load16(source);
						for (var i = 0; i < count; ++i) {
							this.cpu.mmu.store16(dest + (i << 1), word);
						}
					}
				} else {
					if (wordsize == 4) {
						source &= 0xfffffffc;
						dest &= 0xfffffffc;
						for (var i = 0; i < count; ++i) {
							var word = this.cpu.mmu.load32(source + (i << 2));
							this.cpu.mmu.store32(dest + (i << 2), word);
						}
					} else {
						source &= 0xfffffffe;
						dest &= 0xfffffffe;
						for (var i = 0; i < count; ++i) {
							var word = this.cpu.mmu.load16(source + (i << 1));
							this.cpu.mmu.store16(dest + (i << 1), word);
						}
					}
				}
				return;
			case 0x0c:
				// FastCpuSet
				var source = this.cpu.gprs[0] & 0xfffffffc;
				var dest = this.cpu.gprs[1] & 0xfffffffc;
				var mode = this.cpu.gprs[2];
				var count = mode & 0x000fffff;
				count = ((count + 7) >> 3) << 3;
				var fill = mode & 0x01000000;
				if (fill) {
					var word = this.cpu.mmu.load32(source);
					for (var i = 0; i < count; ++i) {
						this.cpu.mmu.store32(dest + (i << 2), word);
					}
				} else {
					for (var i = 0; i < count; ++i) {
						var word = this.cpu.mmu.load32(source + (i << 2));
						this.cpu.mmu.store32(dest + (i << 2), word);
					}
				}
				return;
			case 0x0e:
				// BgAffineSet
				var i = this.cpu.gprs[2];
				var ox, oy;
				var cx, cy;
				var sx, sy;
				var theta;
				var offset = this.cpu.gprs[0];
				var destination = this.cpu.gprs[1];
				var a, b, c, d;
				var rx, ry;
				while (i--) {
					// [ sx   0  0 ]   [ cos(theta)  -sin(theta)  0 ]   [ 1  0  cx - ox ]   [ A B rx ]
					// [  0  sy  0 ] * [ sin(theta)   cos(theta)  0 ] * [ 0  1  cy - oy ] = [ C D ry ]
					// [  0   0  1 ]   [     0            0       1 ]   [ 0  0     1    ]   [ 0 0  1 ]
					ox = this.core.mmu.load32(offset) / 256;
					oy = this.core.mmu.load32(offset + 4) / 256;
					cx = this.core.mmu.load16(offset + 8);
					cy = this.core.mmu.load16(offset + 10);
					sx = this.core.mmu.load16(offset + 12) / 256;
					sy = this.core.mmu.load16(offset + 14) / 256;
					theta =
						((this.core.mmu.loadU16(offset + 16) >> 8) / 128) *
						Math.PI;
					offset += 20;
					// Rotation
					a = d = Math.cos(theta);
					b = c = Math.sin(theta);
					// Scale
					a *= sx;
					b *= -sx;
					c *= sy;
					d *= sy;
					// Translate
					rx = ox - (a * cx + b * cy);
					ry = oy - (c * cx + d * cy);
					this.core.mmu.store16(destination, (a * 256) | 0);
					this.core.mmu.store16(destination + 2, (b * 256) | 0);
					this.core.mmu.store16(destination + 4, (c * 256) | 0);
					this.core.mmu.store16(destination + 6, (d * 256) | 0);
					this.core.mmu.store32(destination + 8, (rx * 256) | 0);
					this.core.mmu.store32(destination + 12, (ry * 256) | 0);
					destination += 16;
				}
				break;
			case 0x0f:
				// ObjAffineSet
				var i = this.cpu.gprs[2];
				var sx, sy;
				var theta;
				var offset = this.cpu.gprs[0];
				var destination = this.cpu.gprs[1];
				var diff = this.cpu.gprs[3];
				var a, b, c, d;
				while (i--) {
					// [ sx   0 ]   [ cos(theta)  -sin(theta) ]   [ A B ]
					// [  0  sy ] * [ sin(theta)   cos(theta) ] = [ C D ]
					sx = this.core.mmu.load16(offset) / 256;
					sy = this.core.mmu.load16(offset + 2) / 256;
					theta =
						((this.core.mmu.loadU16(offset + 4) >> 8) / 128) *
						Math.PI;
					offset += 6;
					// Rotation
					a = d = Math.cos(theta);
					b = c = Math.sin(theta);
					// Scale
					a *= sx;
					b *= -sx;
					c *= sy;
					d *= sy;
					this.core.mmu.store16(destination, (a * 256) | 0);
					this.core.mmu.store16(destination + diff, (b * 256) | 0);
					this.core.mmu.store16(
						destination + diff * 2,
						(c * 256) | 0
					);
					this.core.mmu.store16(
						destination + diff * 3,
						(d * 256) | 0
					);
					destination += diff * 4;
				}
				break;
			case 0x11:
				// LZ77UnCompWram
				this.lz77(this.cpu.gprs[0], this.cpu.gprs[1], 1);
				break;
			case 0x12:
				// LZ77UnCompVram
				this.lz77(this.cpu.gprs[0], this.cpu.gprs[1], 2);
				break;
			case 0x13:
				// HuffUnComp
				this.huffman(this.cpu.gprs[0], this.cpu.gprs[1]);
				break;
			case 0x14:
				// RlUnCompWram
				this.rl(this.cpu.gprs[0], this.cpu.gprs[1], 1);
				break;
			case 0x15:
				// RlUnCompVram
				this.rl(this.cpu.gprs[0], this.cpu.gprs[1], 2);
				break;
			case 0x1f:
				// MidiKey2Freq
				var key = this.cpu.mmu.load32(this.cpu.gprs[0] + 4);
				this.cpu.gprs[0] = key / Math.pow(2, (180 - this.cpu.gprs[1] - this.cpu.gprs[2] / 256) / 12) >>> 0;
				break;
			default:
				throw (
					"Unimplemented software interrupt: 0x" + opcode.toString(16)
				);
		}
	}
	masterEnable(value) {
		this.enable = value;

		if (this.enable && this.enabledIRQs & this.interruptFlags) {
			this.cpu.raiseIRQ();
		}
	}
	setInterruptsEnabled(value) {
		this.enabledIRQs = value;

		if (this.enabledIRQs & this.MASK_SIO) {
			this.core.STUB("Serial I/O interrupts not implemented");
		}

		if (this.enabledIRQs & this.MASK_KEYPAD) {
			this.core.STUB("Keypad interrupts not implemented");
		}

		if (this.enable && this.enabledIRQs & this.interruptFlags) {
			this.cpu.raiseIRQ();
		}
	}
	pollNextEvent() {
		var nextEvent = this.video.nextEvent;
		var test;

		if (this.audio.enabled) {
			test = this.audio.nextEvent;
			if (!nextEvent || test < nextEvent) {
				nextEvent = test;
			}
		}

		if (this.timersEnabled) {
			var timer = this.timers[0];
			test = timer.nextEvent;
			if (timer.enable && test && (!nextEvent || test < nextEvent)) {
				nextEvent = test;
			}

			timer = this.timers[1];
			test = timer.nextEvent;
			if (timer.enable && test && (!nextEvent || test < nextEvent)) {
				nextEvent = test;
			}
			timer = this.timers[2];
			test = timer.nextEvent;
			if (timer.enable && test && (!nextEvent || test < nextEvent)) {
				nextEvent = test;
			}
			timer = this.timers[3];
			test = timer.nextEvent;
			if (timer.enable && test && (!nextEvent || test < nextEvent)) {
				nextEvent = test;
			}
		}

		var dma = this.dma[0];
		test = dma.nextIRQ;
		if (
			dma.enable &&
			dma.doIrq &&
			test &&
			(!nextEvent || test < nextEvent)
		) {
			nextEvent = test;
		}

		dma = this.dma[1];
		test = dma.nextIRQ;
		if (
			dma.enable &&
			dma.doIrq &&
			test &&
			(!nextEvent || test < nextEvent)
		) {
			nextEvent = test;
		}

		dma = this.dma[2];
		test = dma.nextIRQ;
		if (
			dma.enable &&
			dma.doIrq &&
			test &&
			(!nextEvent || test < nextEvent)
		) {
			nextEvent = test;
		}

		dma = this.dma[3];
		test = dma.nextIRQ;
		if (
			dma.enable &&
			dma.doIrq &&
			test &&
			(!nextEvent || test < nextEvent)
		) {
			nextEvent = test;
		}

		this.core.ASSERT(
			nextEvent >= this.cpu.cycles,
			"Next event is before present"
		);
		this.nextEvent = nextEvent;
	}
	waitForIRQ() {
		var timer;
		var irqPending =
			this.testIRQ() ||
			this.video.hblankIRQ ||
			this.video.vblankIRQ ||
			this.video.vcounterIRQ;
		if (this.timersEnabled) {
			timer = this.timers[0];
			irqPending = irqPending || timer.doIrq;
			timer = this.timers[1];
			irqPending = irqPending || timer.doIrq;
			timer = this.timers[2];
			irqPending = irqPending || timer.doIrq;
			timer = this.timers[3];
			irqPending = irqPending || timer.doIrq;
		}
		if (!irqPending) {
			return false;
		}

		for (;;) {
			this.pollNextEvent();

			if (!this.nextEvent) {
				return false;
			} else {
				this.cpu.cycles = this.nextEvent;
				this.updateTimers();
				if (this.interruptFlags) {
					return true;
				}
			}
		}
	}
	testIRQ() {
		if (this.enable && this.enabledIRQs & this.interruptFlags) {
			this.springIRQ = true;
			this.nextEvent = this.cpu.cycles;
			return true;
		}
		return false;
	}
	raiseIRQ(irqType) {
		this.interruptFlags |= 1 << irqType;
		this.io.registers[this.io.IF >> 1] = this.interruptFlags;

		if (this.enable && this.enabledIRQs & (1 << irqType)) {
			this.cpu.raiseIRQ();
		}
	}
	dismissIRQs(irqMask) {
		this.interruptFlags &= ~irqMask;
		this.io.registers[this.io.IF >> 1] = this.interruptFlags;
	}
	dmaSetSourceAddress(dma, address) {
		this.dma[dma].source = address & 0xfffffffe;
	}
	dmaSetDestAddress(dma, address) {
		this.dma[dma].dest = address & 0xfffffffe;
	}
	dmaSetWordCount(dma, count) {
		this.dma[dma].count = count ? count : dma == 3 ? 0x10000 : 0x4000;
	}
	dmaWriteControl(dma, control) {
		var currentDma = this.dma[dma];
		var wasEnabled = currentDma.enable;
		currentDma.dstControl = (control & 0x0060) >> 5;
		currentDma.srcControl = (control & 0x0180) >> 7;
		currentDma.repeat = !!(control & 0x0200);
		currentDma.width = control & 0x0400 ? 4 : 2;
		currentDma.drq = !!(control & 0x0800);
		currentDma.timing = (control & 0x3000) >> 12;
		currentDma.doIrq = !!(control & 0x4000);
		currentDma.enable = !!(control & 0x8000);
		currentDma.nextIRQ = 0;

		if (currentDma.drq) {
			this.core.WARN("DRQ not implemented");
		}

		if (!wasEnabled && currentDma.enable) {
			currentDma.nextSource = currentDma.source;
			currentDma.nextDest = currentDma.dest;
			currentDma.nextCount = currentDma.count;
			this.cpu.mmu.scheduleDma(dma, currentDma);
		}
	}
	timerSetReload(timer, reload) {
		this.timers[timer].reload = reload & 0xffff;
	}
	timerWriteControl(timer, control) {
		var currentTimer = this.timers[timer];
		var oldPrescale = currentTimer.prescaleBits;
		switch (control & 0x0003) {
			case 0x0000:
				currentTimer.prescaleBits = 0;
				break;
			case 0x0001:
				currentTimer.prescaleBits = 6;
				break;
			case 0x0002:
				currentTimer.prescaleBits = 8;
				break;
			case 0x0003:
				currentTimer.prescaleBits = 10;
				break;
		}
		currentTimer.countUp = !!(control & 0x0004);
		currentTimer.doIrq = !!(control & 0x0040);
		currentTimer.overflowInterval =
			(0x10000 - currentTimer.reload) << currentTimer.prescaleBits;
		var wasEnabled = currentTimer.enable;
		currentTimer.enable = !!(((control & 0x0080) >> 7) << timer);
		if (!wasEnabled && currentTimer.enable) {
			if (!currentTimer.countUp) {
				currentTimer.lastEvent = this.cpu.cycles;
				currentTimer.nextEvent =
					this.cpu.cycles + currentTimer.overflowInterval;
			} else {
				currentTimer.nextEvent = 0;
			}
			this.io.registers[(this.io.TM0CNT_LO + (timer << 2)) >> 1] =
				currentTimer.reload;
			currentTimer.oldReload = currentTimer.reload;
			++this.timersEnabled;
		} else if (wasEnabled && !currentTimer.enable) {
			if (!currentTimer.countUp) {
				this.io.registers[(this.io.TM0CNT_LO + (timer << 2)) >> 1] =
					(currentTimer.oldReload +
						(this.cpu.cycles - currentTimer.lastEvent)) >>
					oldPrescale;
			}
			--this.timersEnabled;
		} else if (
			currentTimer.prescaleBits != oldPrescale &&
			!currentTimer.countUp
		) {
			// FIXME: this might be before present
			currentTimer.nextEvent =
				currentTimer.lastEvent + currentTimer.overflowInterval;
		}

		// We've changed the timers somehow...we need to reset the next event
		this.pollNextEvent();
	}
	timerRead(timer) {
		var currentTimer = this.timers[timer];
		if (currentTimer.enable && !currentTimer.countUp) {
			return (
				(currentTimer.oldReload +
					(this.cpu.cycles - currentTimer.lastEvent)) >>
				currentTimer.prescaleBits
			);
		} else {
			return this.io.registers[(this.io.TM0CNT_LO + (timer << 2)) >> 1];
		}
	}
	halt() {
		if (!this.enable) {
			throw "Requested HALT when interrupts were disabled!";
		}
		if (!this.waitForIRQ()) {
			throw "Waiting on interrupt forever.";
		}
	}
	lz77(source, dest, unitsize) {
		// TODO: move to a different file
		var remaining = (this.cpu.mmu.load32(source) & 0xffffff00) >> 8;
		// We assume the signature byte (0x10) is correct
		var blockheader;
		var sPointer = source + 4;
		var dPointer = dest;
		var blocksRemaining = 0;
		var block;
		var disp;
		var bytes;
		var buffer = 0;
		var loaded;
		while (remaining > 0) {
			if (blocksRemaining) {
				if (blockheader & 0x80) {
					// Compressed
					block =
						this.cpu.mmu.loadU8(sPointer) |
						(this.cpu.mmu.loadU8(sPointer + 1) << 8);
					sPointer += 2;
					disp =
						dPointer -
						(((block & 0x000f) << 8) | ((block & 0xff00) >> 8)) -
						1;
					bytes = ((block & 0x00f0) >> 4) + 3;
					while (bytes-- && remaining) {
						loaded = this.cpu.mmu.loadU8(disp++);
						if (unitsize == 2) {
							buffer >>= 8;
							buffer |= loaded << 8;
							if (dPointer & 1) {
								this.cpu.mmu.store16(dPointer - 1, buffer);
							}
						} else {
							this.cpu.mmu.store8(dPointer, loaded);
						}
						--remaining;
						++dPointer;
					}
				} else {
					// Uncompressed
					loaded = this.cpu.mmu.loadU8(sPointer++);
					if (unitsize == 2) {
						buffer >>= 8;
						buffer |= loaded << 8;
						if (dPointer & 1) {
							this.cpu.mmu.store16(dPointer - 1, buffer);
						}
					} else {
						this.cpu.mmu.store8(dPointer, loaded);
					}
					--remaining;
					++dPointer;
				}
				blockheader <<= 1;
				--blocksRemaining;
			} else {
				blockheader = this.cpu.mmu.loadU8(sPointer++);
				blocksRemaining = 8;
			}
		}
	}
	huffman(source, dest) {
		source = source & 0xfffffffc;
		var header = this.cpu.mmu.load32(source);
		var remaining = header >> 8;
		var bits = header & 0xf;
		if (32 % bits) {
			throw "Unimplemented unaligned Huffman";
		}
		var padding = (4 - remaining) & 0x3;
		remaining &= 0xfffffffc;
		// We assume the signature byte (0x20) is correct
		var tree = [];
		var treesize = (this.cpu.mmu.loadU8(source + 4) << 1) + 1;
		var block;
		var sPointer = source + 5 + treesize;
		var dPointer = dest & 0xfffffffc;
		var i;
		for (i = 0; i < treesize; ++i) {
			tree.push(this.cpu.mmu.loadU8(source + 5 + i));
		}
		var node;
		var offset = 0;
		var bitsRemaining;
		var readBits;
		var bitsSeen = 0;
		node = tree[0];
		while (remaining > 0) {
			var bitstream = this.cpu.mmu.load32(sPointer);
			sPointer += 4;
			for (
				bitsRemaining = 32;
				bitsRemaining > 0;
				--bitsRemaining, bitstream <<= 1
			) {
				if (typeof node === "number") {
					// Lazily construct tree
					var next = ((offset - 1) | 1) + ((node & 0x3f) << 1) + 2;
					node = {
						l: next,
						r: next + 1,
						lTerm: node & 0x80,
						rTerm: node & 0x40
					};
					tree[offset] = node;
				}

				if (bitstream & 0x80000000) {
					// Go right
					if (node.rTerm) {
						readBits = tree[node.r];
					} else {
						offset = node.r;
						node = tree[node.r];
						continue;
					}
				} else {
					// Go left
					if (node.lTerm) {
						readBits = tree[node.l];
					} else {
						offset = node.l;
						node = tree[offset];
						continue;
					}
				}

				block |= (readBits & ((1 << bits) - 1)) << bitsSeen;
				bitsSeen += bits;
				offset = 0;
				node = tree[0];
				if (bitsSeen == 32) {
					bitsSeen = 0;
					this.cpu.mmu.store32(dPointer, block);
					dPointer += 4;
					remaining -= 4;
					block = 0;
				}
			}
		}
		if (padding) {
			this.cpu.mmu.store32(dPointer, block);
		}
	}
	rl(source, dest, unitsize) {
		source = source & 0xfffffffc;
		var remaining = (this.cpu.mmu.load32(source) & 0xffffff00) >> 8;
		var padding = (4 - remaining) & 0x3;
		// We assume the signature byte (0x30) is correct
		var blockheader;
		var block;
		var sPointer = source + 4;
		var dPointer = dest;
		var buffer = 0;
		while (remaining > 0) {
			blockheader = this.cpu.mmu.loadU8(sPointer++);
			if (blockheader & 0x80) {
				// Compressed
				blockheader &= 0x7f;
				blockheader += 3;
				block = this.cpu.mmu.loadU8(sPointer++);
				while (blockheader-- && remaining) {
					--remaining;
					if (unitsize == 2) {
						buffer >>= 8;
						buffer |= block << 8;
						if (dPointer & 1) {
							this.cpu.mmu.store16(dPointer - 1, buffer);
						}
					} else {
						this.cpu.mmu.store8(dPointer, block);
					}
					++dPointer;
				}
			} else {
				// Uncompressed
				blockheader++;
				while (blockheader-- && remaining) {
					--remaining;
					block = this.cpu.mmu.loadU8(sPointer++);
					if (unitsize == 2) {
						buffer >>= 8;
						buffer |= block << 8;
						if (dPointer & 1) {
							this.cpu.mmu.store16(dPointer - 1, buffer);
						}
					} else {
						this.cpu.mmu.store8(dPointer, block);
					}
					++dPointer;
				}
			}
		}
		while (padding--) {
			this.cpu.mmu.store8(dPointer++, 0);
		}
	}
}

class GameBoyAdvanceKeypad {
	constructor() {
		this.KEYCODE_LEFT = 37;
		this.KEYCODE_UP = 38;
		this.KEYCODE_RIGHT = 39;
		this.KEYCODE_DOWN = 40;
		this.KEYCODE_START = 13;
		this.KEYCODE_SELECT = 220;
		this.KEYCODE_A = 90;
		this.KEYCODE_B = 88;
		this.KEYCODE_L = 65;
		this.KEYCODE_R = 83;

		this.GAMEPAD_LEFT = 14;
		this.GAMEPAD_UP = 12;
		this.GAMEPAD_RIGHT = 15;
		this.GAMEPAD_DOWN = 13;
		this.GAMEPAD_START = 9;
		this.GAMEPAD_SELECT = 8;
		this.GAMEPAD_A = 1;
		this.GAMEPAD_B = 0;
		this.GAMEPAD_L = 4;
		this.GAMEPAD_R = 5;
		this.GAMEPAD_THRESHOLD = 0.2;

		this.A = 0;
		this.B = 1;
		this.SELECT = 2;
		this.START = 3;
		this.RIGHT = 4;
		this.LEFT = 5;
		this.UP = 6;
		this.DOWN = 7;
		this.R = 8;
		this.L = 9;

		this.currentDown = 0x03ff;
		this.eatInput = false;

		this.gamepads = [];
	}
	keyboardHandler(e) {
		var toggle = 0;
		switch (e.keyCode) {
			case this.KEYCODE_START:
				toggle = this.START;
				break;
			case this.KEYCODE_SELECT:
				toggle = this.SELECT;
				break;
			case this.KEYCODE_A:
				toggle = this.A;
				break;
			case this.KEYCODE_B:
				toggle = this.B;
				break;
			case this.KEYCODE_L:
				toggle = this.L;
				break;
			case this.KEYCODE_R:
				toggle = this.R;
				break;
			case this.KEYCODE_UP:
				toggle = this.UP;
				break;
			case this.KEYCODE_RIGHT:
				toggle = this.RIGHT;
				break;
			case this.KEYCODE_DOWN:
				toggle = this.DOWN;
				break;
			case this.KEYCODE_LEFT:
				toggle = this.LEFT;
				break;
			default:
				return;
		}

		toggle = 1 << toggle;
		if (e.type == "keydown") {
			this.currentDown &= ~toggle;
		} else {
			this.currentDown |= toggle;
		}

		if (this.eatInput) {
			e.preventDefault();
		}
	}
	gamepadHandler(gamepad) {
		var value = 0;
		if (gamepad.buttons[this.GAMEPAD_LEFT] > this.GAMEPAD_THRESHOLD) {
			value |= 1 << this.LEFT;
		}
		if (gamepad.buttons[this.GAMEPAD_UP] > this.GAMEPAD_THRESHOLD) {
			value |= 1 << this.UP;
		}
		if (gamepad.buttons[this.GAMEPAD_RIGHT] > this.GAMEPAD_THRESHOLD) {
			value |= 1 << this.RIGHT;
		}
		if (gamepad.buttons[this.GAMEPAD_DOWN] > this.GAMEPAD_THRESHOLD) {
			value |= 1 << this.DOWN;
		}
		if (gamepad.buttons[this.GAMEPAD_START] > this.GAMEPAD_THRESHOLD) {
			value |= 1 << this.START;
		}
		if (gamepad.buttons[this.GAMEPAD_SELECT] > this.GAMEPAD_THRESHOLD) {
			value |= 1 << this.SELECT;
		}
		if (gamepad.buttons[this.GAMEPAD_A] > this.GAMEPAD_THRESHOLD) {
			value |= 1 << this.A;
		}
		if (gamepad.buttons[this.GAMEPAD_B] > this.GAMEPAD_THRESHOLD) {
			value |= 1 << this.B;
		}
		if (gamepad.buttons[this.GAMEPAD_L] > this.GAMEPAD_THRESHOLD) {
			value |= 1 << this.L;
		}
		if (gamepad.buttons[this.GAMEPAD_R] > this.GAMEPAD_THRESHOLD) {
			value |= 1 << this.R;
		}

		this.currentDown = ~value & 0x3ff;
	}
	gamepadConnectHandler(gamepad) {
		this.gamepads.push(gamepad);
	}
	gamepadDisconnectHandler(gamepad) {
		this.gamepads = self.gamepads.filter(function (other) {
			return other != gamepad;
		});
	}
	pollGamepads() {
		var navigatorList = [];
		if (navigator.webkitGetGamepads) {
			navigatorList = navigator.webkitGetGamepads();
		} else if (navigator.getGamepads) {
			navigatorList = navigator.getGamepads();
		}

		// Let's all give a shout out to Chrome for making us get the gamepads EVERY FRAME
		if (navigatorList.length) {
			this.gamepads = [];
		}
		for (var i = 0; i < navigatorList.length; ++i) {
			if (navigatorList[i]) {
				this.gamepads.push(navigatorList[i]);
			}
		}
		if (this.gamepads.length > 0) {
			this.gamepadHandler(this.gamepads[0]);
		}
	}
	registerHandlers() {
		window.addEventListener(
			"keydown",
			this.keyboardHandler.bind(this),
			true
		);
		window.addEventListener("keyup", this.keyboardHandler.bind(this), true);

		window.addEventListener(
			"gamepadconnected",
			this.gamepadConnectHandler.bind(this),
			true
		);
		window.addEventListener(
			"mozgamepadconnected",
			this.gamepadConnectHandler.bind(this),
			true
		);
		window.addEventListener(
			"webkitgamepadconnected",
			this.gamepadConnectHandler.bind(this),
			true
		);

		window.addEventListener(
			"gamepaddisconnected",
			this.gamepadDisconnectHandler.bind(this),
			true
		);
		window.addEventListener(
			"mozgamepaddisconnected",
			this.gamepadDisconnectHandler.bind(this),
			true
		);
		window.addEventListener(
			"webkitgamepaddisconnected",
			this.gamepadDisconnectHandler.bind(this),
			true
		);
	}
}

class GameBoyAdvanceSIO {
	constructor() {
		this.SIO_NORMAL_8 = 0;
		this.SIO_NORMAL_32 = 1;
		this.SIO_MULTI = 2;
		this.SIO_UART = 3;
		this.SIO_GPIO = 8;
		this.SIO_JOYBUS = 12;

		this.BAUD = [9600, 38400, 57600, 115200];
	}
	clear() {
		this.mode = this.SIO_GPIO;
		this.sd = false;

		this.irq = false;
		this.multiplayer = {
			baud: 0,
			si: 0,
			id: 0,
			error: 0,
			busy: 0,

			states: [0xffff, 0xffff, 0xffff, 0xffff]
		};

		this.linkLayer = null;
	}
	setMode(mode) {
		if (mode & 0x8) {
			mode &= 0xc;
		} else {
			mode &= 0x3;
		}
		this.mode = mode;

		this.core.INFO("Setting SIO mode to " + hex(mode, 1));
	}
	writeRCNT(value) {
		if (this.mode != this.SIO_GPIO) {
			return;
		}

		this.core.STUB("General purpose serial not supported");
	}
	writeSIOCNT(value) {
		switch (this.mode) {
			case this.SIO_NORMAL_8:
				this.core.STUB("8-bit transfer unsupported");
				break;
			case this.SIO_NORMAL_32:
				this.core.STUB("32-bit transfer unsupported");
				break;
			case this.SIO_MULTI:
				this.multiplayer.baud = value & 0x0003;
				if (this.linkLayer) {
					this.linkLayer.setBaud(this.BAUD[this.multiplayer.baud]);
				}

				if (!this.multiplayer.si) {
					this.multiplayer.busy = value & 0x0080;
					if (this.linkLayer && this.multiplayer.busy) {
						this.linkLayer.startMultiplayerTransfer();
					}
				}
				this.irq = value & 0x4000;
				break;
			case this.SIO_UART:
				this.core.STUB("UART unsupported");
				break;
			case this.SIO_GPIO:
				// This register isn't used in general-purpose mode
				break;
			case this.SIO_JOYBUS:
				this.core.STUB("JOY BUS unsupported");
				break;
		}
	}
	readSIOCNT() {
		var value = (this.mode << 12) & 0xffff;
		switch (this.mode) {
			case this.SIO_NORMAL_8:
				this.core.STUB("8-bit transfer unsupported");
				break;
			case this.SIO_NORMAL_32:
				this.core.STUB("32-bit transfer unsupported");
				break;
			case this.SIO_MULTI:
				value |= this.multiplayer.baud;
				value |= this.multiplayer.si;
				value |= !!this.sd << 3;
				value |= this.multiplayer.id << 4;
				value |= this.multiplayer.error;
				value |= this.multiplayer.busy;
				value |= !!this.multiplayer.irq << 14;
				break;
			case this.SIO_UART:
				this.core.STUB("UART unsupported");
				break;
			case this.SIO_GPIO:
				// This register isn't used in general-purpose mode
				break;
			case this.SIO_JOYBUS:
				this.core.STUB("JOY BUS unsupported");
				break;
		}
		return value;
	}
	read(slot) {
		switch (this.mode) {
			case this.SIO_NORMAL_32:
				this.core.STUB("32-bit transfer unsupported");
				break;
			case this.SIO_MULTI:
				return this.multiplayer.states[slot];
			case this.SIO_UART:
				this.core.STUB("UART unsupported");
				break;
			default:
				this.core.WARN(
					"Reading from transfer register in unsupported mode"
				);
				break;
		}
		return 0;
	}
}

class SRAMSavedata extends MemoryView {
	constructor(size) {
		super(new ArrayBuffer(size), 0);

		this.writePending = false;
	}
	store8(offset, value) {
		this.view.setInt8(offset, value);
		this.writePending = true;
	}
	store16(offset, value) {
		this.view.setInt16(offset, value, true);
		this.writePending = true;
	}
	store32(offset, value) {
		this.view.setInt32(offset, value, true);
		this.writePending = true;
	}
}

class FlashSavedata extends MemoryView {
	constructor(size) {
		super(new ArrayBuffer(size), 0);

		this.COMMAND_WIPE = 0x10;
		this.COMMAND_ERASE_SECTOR = 0x30;
		this.COMMAND_ERASE = 0x80;
		this.COMMAND_ID = 0x90;
		this.COMMAND_WRITE = 0xa0;
		this.COMMAND_SWITCH_BANK = 0xb0;
		this.COMMAND_TERMINATE_ID = 0xf0;

		this.ID_PANASONIC = 0x1b32;
		this.ID_SANYO = 0x1362;

		this.bank0 = new DataView(this.buffer, 0, 0x00010000);
		if (size > 0x00010000) {
			this.id = this.ID_SANYO;
			this.bank1 = new DataView(this.buffer, 0x00010000);
		} else {
			this.id = this.ID_PANASONIC;
			this.bank1 = null;
		}
		this.bank = this.bank0;

		this.idMode = false;
		this.writePending = false;

		this.first = 0;
		this.second = 0;
		this.command = 0;
		this.pendingCommand = 0;
	}
	load8(offset) {
		if (this.idMode && offset < 2) {
			return (this.id >> (offset << 3)) & 0xff;
		} else if (offset < 0x10000) {
			return this.bank.getInt8(offset);
		} else {
			return 0;
		}
	}
	load16(offset) {
		return (this.load8(offset) & 0xff) | (this.load8(offset + 1) << 8);
	}
	load32(offset) {
		return (
			(this.load8(offset) & 0xff) |
			(this.load8(offset + 1) << 8) |
			(this.load8(offset + 2) << 16) |
			(this.load8(offset + 3) << 24)
		);
	}
	loadU8(offset) {
		return this.load8(offset) & 0xff;
	}
	loadU16(offset) {
		return (this.loadU8(offset) & 0xff) | (this.loadU8(offset + 1) << 8);
	}
	store8(offset, value) {
		switch (this.command) {
			case 0:
				if (offset == 0x5555) {
					if (this.second == 0x55) {
						switch (value) {
							case this.COMMAND_ERASE:
								this.pendingCommand = value;
								break;
							case this.COMMAND_ID:
								this.idMode = true;
								break;
							case this.COMMAND_TERMINATE_ID:
								this.idMode = false;
								break;
							default:
								this.command = value;
								break;
						}
						this.second = 0;
						this.first = 0;
					} else {
						this.command = 0;
						this.first = value;
						this.idMode = false;
					}
				} else if (offset == 0x2aaa && this.first == 0xaa) {
					this.first = 0;
					if (this.pendingCommand) {
						this.command = this.pendingCommand;
					} else {
						this.second = value;
					}
				}
				break;
			case this.COMMAND_ERASE:
				switch (value) {
					case this.COMMAND_WIPE:
						if (offset == 0x5555) {
							for (var i = 0; i < this.view.byteLength; i += 4) {
								this.view.setInt32(i, -1);
							}
						}
						break;
					case this.COMMAND_ERASE_SECTOR:
						if ((offset & 0x0fff) == 0) {
							for (var i = offset; i < offset + 0x1000; i += 4) {
								this.bank.setInt32(i, -1);
							}
						}
						break;
				}
				this.pendingCommand = 0;
				this.command = 0;
				break;
			case this.COMMAND_WRITE:
				this.bank.setInt8(offset, value);
				this.command = 0;

				this.writePending = true;
				break;
			case this.COMMAND_SWITCH_BANK:
				if (this.bank1 && offset == 0) {
					if (value == 1) {
						this.bank = this.bank1;
					} else {
						this.bank = this.bank0;
					}
				}
				this.command = 0;
				break;
		}
	}
	store16(offset, value) {
		throw new Error('Unaligned save to flash!');
	}
	store32(offset, value) {
		throw new Error('Unaligned save to flash!');
	}
	replaceData(memory) {
		var bank = this.view === this.bank1;
		MemoryView.prototype.replaceData.call(this, memory, 0);

		this.bank0 = new DataView(this.buffer, 0, 0x00010000);
		if (memory.byteLength > 0x00010000) {
			this.bank1 = new DataView(this.buffer, 0x00010000);
		} else {
			this.bank1 = null;
		}
		this.bank = bank ? this.bank1 : this.bank0;
	}
}

class EEPROMSavedata extends MemoryView {
	constructor(size, mmu) {
		super(new ArrayBuffer(size), 0);

		this.writeAddress = 0;
		this.readBitsRemaining = 0;
		this.readAddress = 0;

		this.command = 0;
		this.commandBitsRemaining = 0;

		this.realSize = 0;
		this.addressBits = 0;
		this.writePending = false;

		this.dma = mmu.core.irq.dma[3];

		this.COMMAND_NULL = 0;
		this.COMMAND_PENDING = 1;
		this.COMMAND_WRITE = 2;
		this.COMMAND_READ_PENDING = 3;
		this.COMMAND_READ = 4;
	}
	load8(offset) {
		throw new Error('Unsupported 8-bit access!');
	}
	load16(offset) {
		return this.loadU16(offset);
	}
	loadU8(offset) {
		throw new Error('Unsupported 8-bit access!');
	}
	loadU16(offset) {
		if (this.command != this.COMMAND_READ || !this.dma.enable) {
			return 1;
		}
		--this.readBitsRemaining;
		if (this.readBitsRemaining < 64) {
			var step = 63 - this.readBitsRemaining;
			var data =
				this.view.getUint8((this.readAddress + step) >> 3, false) >>
				(0x7 - (step & 0x7));
			if (!this.readBitsRemaining) {
				this.command = this.COMMAND_NULL;
			}
			return data & 0x1;
		}
		return 0;
	}
	load32(offset) {
		throw new Error('Unsupported 32-bit access!');
	}
	store8(offset, value) {
		throw new Error('Unsupported 8-bit access!');
	}
	store16(offset, value) {
		switch (this.command) {
			// Read header
			case this.COMMAND_NULL:
			default:
				this.command = value & 0x1;
				break;
			case this.COMMAND_PENDING:
				this.command <<= 1;
				this.command |= value & 0x1;
				if (this.command == this.COMMAND_WRITE) {
					if (!this.realSize) {
						var bits = this.dma.count - 67;
						this.realSize = 8 << bits;
						this.addressBits = bits;
					}
					this.commandBitsRemaining = this.addressBits + 64 + 1;
					this.writeAddress = 0;
				} else {
					if (!this.realSize) {
						var bits = this.dma.count - 3;
						this.realSize = 8 << bits;
						this.addressBits = bits;
					}
					this.commandBitsRemaining = this.addressBits + 1;
					this.readAddress = 0;
				}
				break;
			// Do commands
			case this.COMMAND_WRITE:
				// Write
				if (--this.commandBitsRemaining > 64) {
					this.writeAddress <<= 1;
					this.writeAddress |= (value & 0x1) << 6;
				} else if (this.commandBitsRemaining <= 0) {
					this.command = this.COMMAND_NULL;
					this.writePending = true;
				} else {
					var current = this.view.getUint8(this.writeAddress >> 3);
					current &= ~(1 << (0x7 - (this.writeAddress & 0x7)));
					current |=
						(value & 0x1) << (0x7 - (this.writeAddress & 0x7));
					this.view.setUint8(this.writeAddress >> 3, current);
					++this.writeAddress;
				}
				break;
			case this.COMMAND_READ_PENDING:
				// Read
				if (--this.commandBitsRemaining > 0) {
					this.readAddress <<= 1;
					if (value & 0x1) {
						this.readAddress |= 0x40;
					}
				} else {
					this.readBitsRemaining = 68;
					this.command = this.COMMAND_READ;
				}
				break;
		}
	}
	store32(offset, value) {
		throw new Error('Unsupported 32-bit access!');
	}
	replaceData(memory) {
		MemoryView.prototype.replaceData.call(this, memory, 0);
	}
}

class GameBoyAdvanceGPIO {
	constructor(core, rom) {
		this.core = core;
		this.rom = rom;

		this.readWrite = 0;
		this.direction = 0;

		this.device = new GameBoyAdvanceRTC(this); // TODO: Support more devices
	}
	store16(offset, value) {
		switch (offset) {
			case 0xc4:
				this.device.setPins(value & 0xf);
				break;
			case 0xc6:
				this.direction = value & 0xf;
				this.device.setDirection(this.direction);
				break;
			case 0xc8:
				this.readWrite = value & 1;
				break;
			default:
				throw new Error(
					"BUG: Bad offset passed to GPIO: " + offset.toString(16)
				);
		}
		if (this.readWrite) {
			var old = this.rom.view.getUint16(offset, true);
			old &= ~this.direction;
			this.rom.view.setUint16(
				offset,
				old | (value & this.direction),
				true
			);
		}
	}
	outputPins(nybble) {
		if (this.readWrite) {
			var old = this.rom.view.getUint16(0xc4, true);
			old &= this.direction;
			this.rom.view.setUint16(
				0xc4,
				old | (nybble & ~this.direction & 0xf),
				true
			);
		}
	}
}
class GameBoyAdvanceRTC {
	constructor(gpio) {
		this.gpio = gpio;

		// PINOUT: SCK | SIO | CS | -
		this.pins = 0;
		this.direction = 0;

		this.totalBytes = [
		0, // Force reset
		0, // Empty
		7, // Date/Time
		0, // Force IRQ
		1, // Control register
		0, // Empty
		3, // Time
			0 // Empty
		];
		this.bytesRemaining = 0;

		// Transfer sequence:
		// == Initiate
		// > HI | - | LO | -
		// > HI | - | HI | -
		// == Transfer bit (x8)
		// > LO | x | HI | -
		// > HI | - | HI | -
		// < ?? | x | ?? | -
		// == Terminate
		// >  - | - | LO | -
		this.transferStep = 0;

		this.reading = 0;
		this.bitsRead = 0;
		this.bits = 0;
		this.command = -1;

		this.control = 0x40;
		this.time = [
		0, // Year
		0, // Month
		0, // Day
		0, // Day of week
		0, // Hour
		0, // Minute
			0 // Second
		];
	}
	setPins(nybble) {
		switch (this.transferStep) {
			case 0:
				if ((nybble & 5) == 1) {
					this.transferStep = 1;
				}
				break;
			case 1:
				if (nybble & 4) {
					this.transferStep = 2;
				}
				break;
			case 2:
				if (!(nybble & 1)) {
					this.bits &= ~(1 << this.bitsRead);
					this.bits |= ((nybble & 2) >> 1) << this.bitsRead;
				} else {
					if (nybble & 4) {
						// SIO direction should always != this.read
						if (this.direction & 2 && !this.read) {
							++this.bitsRead;
							if (this.bitsRead == 8) {
								this.processByte();
							}
						} else {
							this.gpio.outputPins(
								5 | (this.sioOutputPin() << 1)
							);
							++this.bitsRead;
							if (this.bitsRead == 8) {
								--this.bytesRemaining;
								if (this.bytesRemaining <= 0) {
									this.command = -1;
								}
								this.bitsRead = 0;
							}
						}
					} else {
						this.bitsRead = 0;
						this.bytesRemaining = 0;
						this.command = -1;
						this.transferStep = 0;
					}
				}
				break;
		}

		this.pins = nybble & 7;
	}
	setDirection(direction) {
		this.direction = direction;
	}
	processByte() {
		--this.bytesRemaining;
		switch (this.command) {
			case -1:
				if ((this.bits & 0x0f) == 0x06) {
					this.command = (this.bits >> 4) & 7;
					this.reading = this.bits & 0x80;

					this.bytesRemaining = this.totalBytes[this.command];
					switch (this.command) {
						case 0:
							this.control = 0;
							break;
						case 2:
						case 6:
							this.updateClock();
							break;
					}
				} else {
					this.gpio.core.WARN(
						"Invalid RTC command byte: " + this.bits.toString(16)
					);
				}
				break;
			case 4:
				// Control
				this.control = this.bits & 0x40;
				break;
		}
		this.bits = 0;
		this.bitsRead = 0;
		if (!this.bytesRemaining) {
			this.command = -1;
		}
	}
	sioOutputPin() {
		var outputByte = 0;
		switch (this.command) {
			case 4:
				outputByte = this.control;
				break;
			case 2:
			case 6:
				outputByte = this.time[7 - this.bytesRemaining];
				break;
		}
		var output = (outputByte >> this.bitsRead) & 1;
		return output;
	}
	updateClock() {
		var date = new Date();
		this.time[0] = this.bcd(date.getFullYear());
		this.time[1] = this.bcd(date.getMonth() + 1);
		this.time[2] = this.bcd(date.getDate());
		this.time[3] = date.getDay() - 1;
		if (this.time[3] < 0) {
			this.time[3] = 6;
		}
		if (this.control & 0x40) {
			// 24 hour
			this.time[4] = this.bcd(date.getHours());
		} else {
			this.time[4] = this.bcd(date.getHours() % 2);
			if (date.getHours() >= 12) {
				this.time[4] |= 0x80;
			}
		}
		this.time[5] = this.bcd(date.getMinutes());
		this.time[6] = this.bcd(date.getSeconds());
	}
	bcd(binary) {
		var counter = binary % 10;
		binary /= 10;
		counter += binary % 10 << 4;
		return counter;
	}
}

class GameBoyAdvance {
	constructor() {
		this.LOG_ERROR = 1;
		this.LOG_WARN = 2;
		this.LOG_STUB = 4;
		this.LOG_INFO = 8;
		this.LOG_DEBUG = 16;

		this.SYS_ID = "com.endrift.gbajs";

		this.logLevel = this.LOG_ERROR | this.LOG_WARN;

		this.rom = null;

		this.cpu = new ARMCore();
		this.mmu = new GameBoyAdvanceMMU();
		this.irq = new GameBoyAdvanceInterruptHandler();
		this.io = new GameBoyAdvanceIO();
		this.audio = new GameBoyAdvanceAudio();
		this.video = new GameBoyAdvanceVideo();
		this.keypad = new GameBoyAdvanceKeypad();
		this.sio = new GameBoyAdvanceSIO();

		// TODO: simplify this graph
		this.cpu.mmu = this.mmu;
		this.cpu.irq = this.irq;

		this.mmu.cpu = this.cpu;
		this.mmu.core = this;

		this.irq.cpu = this.cpu;
		this.irq.io = this.io;
		this.irq.audio = this.audio;
		this.irq.video = this.video;
		this.irq.core = this;

		this.io.cpu = this.cpu;
		this.io.audio = this.audio;
		this.io.video = this.video;
		this.io.keypad = this.keypad;
		this.io.sio = this.sio;
		this.io.core = this;

		this.audio.cpu = this.cpu;
		this.audio.core = this;

		this.video.cpu = this.cpu;
		this.video.core = this;

		this.keypad.core = this;

		this.sio.core = this;

		this.keypad.registerHandlers();
		this.doStep = this.waitFrame;
		this.paused = false;

		this.seenFrame = false;
		this.seenSave = false;
		this.lastVblank = 0;

		this.queue = null;
		this.reportFPS = null;
		this.throttle = 16; // This is rough, but the 2/3ms difference gives us a good overhead

		var self = this;
		window.queueFrame = function (f) {
			self.queue = window.setTimeout(f, self.throttle);
		};

		window.URL = window.URL || window.webkitURL;

		this.video.vblankCallback = function () {
			self.seenFrame = true;
		};
	}
	setCanvas(canvas) {
		if (canvas.offsetWidth != 240 || canvas.offsetHeight != 160) {
			var self = this;
			this.indirectCanvas = document.createElement("canvas");
			this.indirectCanvas.setAttribute("height", "160");
			this.indirectCanvas.setAttribute("width", "240");
			this.targetCanvas = canvas;
			this.setCanvasDirect(this.indirectCanvas);
			var targetContext = canvas.getContext("2d");
			this.video.drawCallback = function () {
				targetContext.drawImage(
					self.indirectCanvas,
					0,
					0,
					canvas.width,
					canvas.height
				);
			};
		} else {
			this.setCanvasDirect(canvas);
			var self = this;
		}
	}
	setCanvasDirect(canvas) {
		this.context = canvas.getContext("2d");
		this.video.setBacking(this.context);
	}
	setBios(bios, real) {
		this.mmu.loadBios(bios, real);
	}
	setRom(rom) {
		this.reset();

		this.rom = this.mmu.loadRom(rom, true);
		if (!this.rom) {
			return false;
		}
		this.retrieveSavedata();
		return true;
	}
	hasRom() {
		return !!this.rom;
	}
	loadRomFromFile(romFile, callback) {
		var reader = new FileReader();
		var self = this;
		reader.onload = function (e) {
			var result = self.setRom(e.target.result);
			if (callback) {
				callback(result);
			}
		};
		reader.readAsArrayBuffer(romFile);
	}
	reset() {
		this.audio.pause(true);

		this.mmu.clear();
		this.io.clear();
		this.audio.clear();
		this.video.clear();
		this.sio.clear();

		this.mmu.mmap(this.mmu.REGION_IO, this.io);
		this.mmu.mmap(
			this.mmu.REGION_PALETTE_RAM,
			this.video.renderPath.palette
		);
		this.mmu.mmap(this.mmu.REGION_VRAM, this.video.renderPath.vram);
		this.mmu.mmap(this.mmu.REGION_OAM, this.video.renderPath.oam);

		this.cpu.resetCPU(0);
	}
	step() {
		while (this.doStep()) {
			this.cpu.step();
		}
	}
	waitFrame() {
		var seen = this.seenFrame;
		this.seenFrame = false;
		return !seen;
	}
	pause() {
		this.paused = true;
		this.audio.pause(true);
		if (this.queue) {
			clearTimeout(this.queue);
			this.queue = null;
		}
	}
	advanceFrame() {
		this.step();
		if (this.seenSave) {
			if (!this.mmu.saveNeedsFlush()) {
				this.storeSavedata();
				this.seenSave = false;
			} else {
				this.mmu.flushSave();
			}
		} else if (this.mmu.saveNeedsFlush()) {
			this.seenSave = true;
			this.mmu.flushSave();
		}
	}
	runStable() {
		if (this.interval) {
			return; // Already running
		}
		var self = this;
		var timer = 0;
		var frames = 0;
		var runFunc;
		var start = Date.now();
		this.paused = false;
		this.audio.pause(false);

		if (this.reportFPS) {
			runFunc = function () {
				try {
					timer += Date.now() - start;
					if (self.paused) {
						return;
					} else {
						queueFrame(runFunc);
					}
					start = Date.now();
					self.advanceFrame();
					++frames;
					if (frames == 60) {
						self.reportFPS((frames * 1000) / timer);
						frames = 0;
						timer = 0;
					}
				} catch (exception) {
					self.ERROR(exception);
					if (exception.stack) {
						self.logStackTrace(exception.stack.split("\n"));
					}
					throw exception;
				}
			};
		} else {
			runFunc = function () {
				try {
					if (self.paused) {
						return;
					} else {
						queueFrame(runFunc);
					}
					self.advanceFrame();
				} catch (exception) {
					self.ERROR(exception);
					if (exception.stack) {
						self.logStackTrace(exception.stack.split("\n"));
					}
					throw exception;
				}
			};
		}
		queueFrame(runFunc);
	}
	setSavedata(data) {
		this.mmu.loadSavedata(data);
	}
	loadSavedataFromFile(saveFile) {
		var reader = new FileReader();
		var self = this;
		reader.onload = function (e) {
			self.setSavedata(e.target.result);
		};
		reader.readAsArrayBuffer(saveFile);
	}
	decodeSavedata(string) {
		this.setSavedata(this.decodeBase64(string));
	}
	decodeBase64(string) {
		var length = (string.length * 3) / 4;
		if (string[string.length - 2] == "=") {
			length -= 2;
		} else if (string[string.length - 1] == "=") {
			length -= 1;
		}
		var buffer = new ArrayBuffer(length);
		var view = new Uint8Array(buffer);
		var bits = string.match(/..../g);
		for (var i = 0; i + 2 < length; i += 3) {
			var s = atob(bits.shift());
			view[i] = s.charCodeAt(0);
			view[i + 1] = s.charCodeAt(1);
			view[i + 2] = s.charCodeAt(2);
		}
		if (i < length) {
			var s = atob(bits.shift());
			view[i++] = s.charCodeAt(0);
			if (s.length > 1) {
				view[i++] = s.charCodeAt(1);
			}
		}

		return buffer;
	}
	encodeBase64(view) {
		var data = [];
		var b;
		var wordstring = [];
		var triplet;
		for (var i = 0; i < view.byteLength; ++i) {
			b = view.getUint8(i, true);
			wordstring.push(String.fromCharCode(b));
			while (wordstring.length >= 3) {
				triplet = wordstring.splice(0, 3);
				data.push(btoa(triplet.join("")));
			}
		}
		if (wordstring.length) {
			data.push(btoa(wordstring.join("")));
		}
		return data.join("");
	}
	downloadSavedata() {
		var sram = this.mmu.save;
		if (!sram) {
			this.WARN("No save data available");
			return null;
		}
		if (window.URL) {
			var url = window.URL.createObjectURL(
				new Blob([sram.buffer], { type: "application/octet-stream" })
			);
			window.open(url);
		} else {
			var data = this.encodeBase64(sram.view);
			window.open(
				"data:application/octet-stream;base64," + data,
				this.rom.code + ".sav"
			);
		}
	}
	storeSavedata() {
		var sram = this.mmu.save;
		try {
			var storage = window.localStorage;
			storage[this.SYS_ID + "." + this.mmu.cart.code] = this.encodeBase64(
				sram.view
			);
		} catch (e) {
			this.WARN("Could not store savedata! " + e);
		}
	}
	retrieveSavedata() {
		try {
			var storage = window.localStorage;
			var data = storage[this.SYS_ID + "." + this.mmu.cart.code];
			if (data) {
				this.decodeSavedata(data);
				return true;
			}
		} catch (e) {
			this.WARN("Could not retrieve savedata! " + e);
		}
		return false;
	}
	freeze() {
		return {
			cpu: this.cpu.freeze(),
			mmu: this.mmu.freeze(),
			irq: this.irq.freeze(),
			io: this.io.freeze(),
			audio: this.audio.freeze(),
			video: this.video.freeze()
		};
	}
	defrost(frost) {
		this.cpu.defrost(frost.cpu);
		this.mmu.defrost(frost.mmu);
		this.audio.defrost(frost.audio);
		this.video.defrost(frost.video);
		this.irq.defrost(frost.irq);
		this.io.defrost(frost.io);
	}
	log(level, message) {}
	setLogger(logger) {
		this.log = logger;
	}
	logStackTrace(stack) {
		var overflow = stack.length - 32;
		this.ERROR("Stack trace follows:");
		if (overflow > 0) {
			this.log(-1, "> (Too many frames)");
		}
		for (var i = Math.max(overflow, 0); i < stack.length; ++i) {
			this.log(-1, "> " + stack[i]);
		}
	}
	ERROR(error) {
		if (this.logLevel & this.LOG_ERROR) {
			this.log(this.LOG_ERROR, error);
		}
	}
	WARN(warn) {
		if (this.logLevel & this.LOG_WARN) {
			this.log(this.LOG_WARN, warn);
		}
	}
	STUB(func) {
		if (this.logLevel & this.LOG_STUB) {
			this.log(this.LOG_STUB, func);
		}
	}
	INFO(info) {
		if (this.logLevel & this.LOG_INFO) {
			this.log(this.LOG_INFO, info);
		}
	}
	DEBUG(info) {
		if (this.logLevel & this.LOG_DEBUG) {
			this.log(this.LOG_DEBUG, info);
		}
	}
	ASSERT_UNREACHED(err) {
		throw new Error("Should be unreached: " + err);
	}
	ASSERT(test, err) {
		if (!test) {
			throw new Error("Assertion failed: " + err);
		}
	}
}

