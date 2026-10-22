// Canvas drawing shared by the screens: viewfinder brackets and the top-down "cabin plan".
// The plan mirrors Planform in the Android and iOS apps.

/** Rounded corner brackets around a rectangle, like a viewfinder. */
export function drawBrackets(ctx, x, y, w, h, color = "#fff", lineWidth = 4) {
  const short = Math.min(w, h);
  const arm = short * 0.18;
  const r = short * 0.07;
  ctx.save();
  ctx.strokeStyle = color;
  ctx.lineWidth = lineWidth;
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.beginPath();
  ctx.moveTo(x, y + arm); ctx.lineTo(x, y + r); ctx.quadraticCurveTo(x, y, x + r, y); ctx.lineTo(x + arm, y);
  ctx.moveTo(x + w - arm, y); ctx.lineTo(x + w - r, y); ctx.quadraticCurveTo(x + w, y, x + w, y + r); ctx.lineTo(x + w, y + arm);
  ctx.moveTo(x + w, y + h - arm); ctx.lineTo(x + w, y + h - r); ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h); ctx.lineTo(x + w - arm, y + h);
  ctx.moveTo(x + arm, y + h); ctx.lineTo(x + r, y + h); ctx.quadraticCurveTo(x, y + h, x, y + h - r); ctx.lineTo(x, y + h - arm);
  ctx.stroke();
  ctx.restore();
}

const SHAPES = {
  wide: [0.42, 0.98, true],
  narrow: [0.32, 0.96, true],
  regional: [0.28, 0.86, true],
  turboprop: [0.26, 0.80, false],
  business: [0.24, 0.74, true],
  classicProp: [0.28, 0.80, false],
  transport: [0.34, 0.86, false],
  fighter: [0.20, 0.70, true],
  light: [0.20, 0.52, false],
};

function rgba(color, alpha) {
  // color is "#rrggbb"
  const n = parseInt(color.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${alpha})`;
}

/**
 * A top-down drawing in the style of a cabin layout plan, nose to the left: wings, engines and
 * fuselage outline, with seat rows filled in (premium rows first) for passenger types.
 * colors: { ink, skin, premium, economy } as "#rrggbb".
 */
export function drawPlanform(canvas, specs, colors) {
  const dpr = window.devicePixelRatio || 1;
  const W = canvas.clientWidth, H = canvas.clientHeight;
  canvas.width = Math.round(W * dpr);
  canvas.height = Math.round(H * dpr);
  const ctx = canvas.getContext("2d");
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, W, H);

  const { ink, skin, premium, economy } = colors;
  const [bodyWidth, bodyLength, swept] = SHAPES[specs.kind];
  const fighter = specs.kind === "fighter";
  const len = W * bodyLength;
  const bw = H * bodyWidth;
  const x0 = (W - len) / 2;
  const cy = H / 2;
  const noseLen = bw * (fighter ? 2.2 : 1.0);
  const tailLen = bw * 1.7;
  ctx.lineWidth = 1.5;
  ctx.lineJoin = "round";

  const poly = (pts, fill, stroke) => {
    ctx.beginPath();
    pts.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
    ctx.closePath();
    ctx.fillStyle = fill; ctx.fill();
    ctx.strokeStyle = stroke; ctx.stroke();
  };

  // Wings from about 40% of the length, reaching the edges of the drawing; tailplane at the back.
  const wingRoot = x0 + len * (fighter ? 0.48 : 0.40);
  const rootChord = len * (fighter ? 0.40 : 0.20);
  const tipChord = len * (swept ? 0.07 : 0.11);
  const sweep = swept ? len * (fighter ? 0.30 : 0.17) : len * 0.02;
  const span = H / 2;
  for (const side of [-1, 1]) {
    poly([[wingRoot, cy + side * bw / 2], [wingRoot + sweep, cy + side * span], [wingRoot + sweep + tipChord, cy + side * span], [wingRoot + rootChord, cy + side * bw / 2]],
      rgba(ink, 0.06), rgba(ink, 0.3));
    const tx = x0 + len - tailLen * 0.75;
    poly([[tx, cy + side * bw * 0.3], [tx + tailLen * 0.35, cy + side * bw * 1.25], [tx + tailLen * 0.55, cy + side * bw * 1.25], [tx + tailLen * 0.6, cy + side * bw * 0.3]],
      rgba(ink, 0.06), rgba(ink, 0.3));
  }

  // Engines
  if (!fighter && specs.kind !== "light") {
    const podLen = len * 0.11;
    const podW = Math.min(bw * 0.36, (span - bw / 2) * 0.3); // four engines must fit on each half-span
    const pod = (cx, py) => {
      ctx.beginPath();
      ctx.roundRect(cx - podLen / 2, py - podW / 2, podLen, podW, podW / 2);
      ctx.fillStyle = rgba(ink, 0.16); ctx.fill();
      ctx.lineWidth = 1; ctx.strokeStyle = rgba(ink, 0.45); ctx.stroke();
      if (specs.engineType !== "turbofan") { // propeller disc in front of the nacelle
        ctx.beginPath(); ctx.moveTo(cx - podLen / 2 - 1, py - podW * 1.3); ctx.lineTo(cx - podLen / 2 - 1, py + podW * 1.3);
        ctx.lineWidth = 1.5; ctx.strokeStyle = rgba(ink, 0.4); ctx.stroke();
      }
    };
    const wingEngines = specs.kind === "wide" && specs.engines === 3 ? 2 : (specs.tailEngines ? 0 : specs.engines);
    const perSide = Math.floor(wingEngines / 2);
    for (const side of [-1, 1]) {
      for (let i = 0; i < perSide; i++) {
        const frac = perSide === 1 ? 0.42 : 0.28 + i * 0.42;
        const y = cy + side * (bw / 2 + (span - bw / 2) * frac);
        const leading = wingRoot + sweep * ((y - cy) * side - bw / 2) / (span - bw / 2);
        pod(leading + podLen * 0.05, y);
      }
    }
    if (specs.tailEngines) {
      const x = x0 + len - tailLen * 1.05;
      if (specs.kind !== "wide") { pod(x, cy - bw / 2 - podW / 2); pod(x, cy + bw / 2 + podW / 2); }
      if (specs.engines === 3) {
        ctx.beginPath(); ctx.arc(x0 + len - tailLen * 0.55, cy, podW * 0.45, 0, Math.PI * 2);
        ctx.fillStyle = rgba(ink, 0.25); ctx.fill();
      }
    }
  }

  // Fuselage: rounded nose, straight cabin, tapering tail cone.
  ctx.beginPath();
  ctx.moveTo(x0 + noseLen, cy - bw / 2);
  ctx.lineTo(x0 + len - tailLen, cy - bw / 2);
  ctx.lineTo(x0 + len, cy - bw * 0.08);
  ctx.lineTo(x0 + len, cy + bw * 0.08);
  ctx.lineTo(x0 + len - tailLen, cy + bw / 2);
  ctx.lineTo(x0 + noseLen, cy + bw / 2);
  ctx.bezierCurveTo(x0 + noseLen * 0.35, cy + bw / 2, x0, cy + bw * 0.2, x0, cy);
  ctx.bezierCurveTo(x0, cy - bw * 0.2, x0 + noseLen * 0.35, cy - bw / 2, x0 + noseLen, cy - bw / 2);
  ctx.closePath();
  ctx.fillStyle = skin; ctx.fill();
  ctx.lineWidth = 1.5; ctx.strokeStyle = rgba(ink, 0.55); ctx.stroke();

  const cabinStart = x0 + noseLen + bw * 0.25;
  const cabinEnd = x0 + len - tailLen;
  const seatBox = (x, y, s, color) => {
    ctx.beginPath(); ctx.roundRect(x, y, s, s, s * 0.25); ctx.fillStyle = rgba(color, 0.85); ctx.fill();
  };
  if (fighter || specs.kind === "light") {
    // Canopy and seats; seatGroups lists seats per row, front to back.
    const rows = specs.seatGroups;
    const seat = bw * 0.26;
    const x = x0 + noseLen * 0.9;
    const canopyLen = seat * (rows.length * 1.5 + 0.8);
    ctx.beginPath(); ctx.ellipse(x + canopyLen / 2, cy, canopyLen / 2, bw * 0.3, 0, 0, Math.PI * 2);
    ctx.fillStyle = rgba(ink, 0.12); ctx.fill();
    rows.forEach((n, r) => {
      const sx = x + seat * 0.6 + r * seat * 1.5;
      for (let i = 0; i < n; i++) seatBox(sx, cy - n * seat * 0.6 + i * seat * 1.2 + seat * 0.1, seat, economy);
    });
  } else if (specs.kind === "transport") {
    ctx.save();
    ctx.setLineDash([5, 4]); ctx.lineWidth = 1; ctx.strokeStyle = rgba(ink, 0.35);
    ctx.beginPath(); ctx.roundRect(cabinStart, cy - bw * 0.32, cabinEnd - cabinStart, bw * 0.64, 4); ctx.stroke();
    ctx.restore();
  } else {
    // Seat rows across the cabin: groups separated by aisles, premium rows at the front.
    const groups = specs.seatGroups;
    const seats = groups.reduce((a, b) => a + b, 0);
    const aisles = groups.length - 1;
    const unit = Math.min(bw * 0.78 / (seats + aisles * 0.9), 9);
    const seat = unit * 0.82;
    const pitch = unit * 1.3;
    const rows = Math.floor((cabinEnd - cabinStart) / pitch);
    const premiumRows = Math.max(2, Math.floor(rows / 7));
    const total = unit * (seats + aisles * 0.9);
    for (let row = 0; row < rows; row++) {
      const x = cabinStart + row * pitch;
      let y = cy - total / 2;
      groups.forEach((n, g) => {
        for (let i = 0; i < n; i++) { seatBox(x, y + (unit - seat) / 2, seat, row < premiumRows ? premium : economy); y += unit; }
        if (g < groups.length - 1) y += unit * 0.9;
      });
    }
  }
}
