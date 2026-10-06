// A paper ticket with a stub to tear off: the kind on a band of colour, the number on both
// halves. A plain canvas and two typefaces. `options.color` is the band.
const SIZE = 2160, INK = '#111111', PAPER = '#fffdf7', GROUND = '#f4f0e6';
const canvas = Object.assign(document.createElement('canvas'), { width: SIZE, height: SIZE });
const ctx = canvas.getContext('2d');
await Promise.all(['Display', 'Mono'].map(async family => document.fonts.add(await new FontFace(family, `url(/assets/${family}.ttf)`).load())));

function random(seed) {
  let state = Number.parseInt(seed.slice(0, 8), 16) >>> 0;
  return () => { state += 0x6d2b79f5; let value = state; value = Math.imul(value ^ value >>> 15, value | 1); value ^= value + Math.imul(value ^ value >>> 7, value | 61); return ((value ^ value >>> 14) >>> 0) / 4294967296; };
}
const dark = colour => { const [r, g, b] = [1, 3, 5].map(at => parseInt(colour.slice(at, at + 2), 16)); return 0.299 * r + 0.587 * g + 0.114 * b < 110; };

export async function renderTicketArt({ serial, total, seed, collection, category, person, options }) {
  const band = /^#[0-9a-f]{6}$/i.test(options?.color ?? '') ? options.color : '#c8f53c', next = random(seed);
  const number = String(serial).padStart(3, '0'), tilt = (next() - 0.5) * 0.05;
  ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.textAlign = 'left'; ctx.globalAlpha = 1;
  ctx.fillStyle = GROUND; ctx.fillRect(0, 0, SIZE, SIZE);
  // The ticket lies on the table a little askew, each one differently.
  ctx.translate(SIZE / 2, SIZE / 2); ctx.rotate(tilt); ctx.translate(-SIZE / 2, -SIZE / 2);
  const x = 180, y = 430, w = 1800, h = 1300, tear = x + 1300;
  const outline = () => {
    ctx.beginPath(); ctx.roundRect(x, y, w, h, 70);
    // A bite out of each edge where the stub tears off.
    ctx.moveTo(tear + 60, y); ctx.arc(tear, y, 60, 0, Math.PI, false);
    ctx.moveTo(tear + 60, y + h); ctx.arc(tear, y + h, 60, 0, Math.PI, true);
  };
  ctx.save(); ctx.translate(26, 26); outline(); ctx.fillStyle = INK; ctx.fill('evenodd'); ctx.restore();
  outline(); ctx.fillStyle = PAPER; ctx.fill('evenodd');
  ctx.save(); outline(); ctx.clip('evenodd'); ctx.fillStyle = band; ctx.fillRect(x, y, w, 330); ctx.restore();
  outline(); ctx.lineWidth = 14; ctx.strokeStyle = INK; ctx.stroke();
  ctx.setLineDash([26, 26]); ctx.lineWidth = 8; ctx.beginPath(); ctx.moveTo(tear, y + 90); ctx.lineTo(tear, y + h - 90); ctx.stroke(); ctx.setLineDash([]);
  ctx.beginPath(); ctx.moveTo(x, y + 330); ctx.lineTo(x + w, y + 330); ctx.lineWidth = 10; ctx.stroke();

  const onBand = dark(band) ? PAPER : INK;
  ctx.fillStyle = onBand; ctx.textBaseline = 'middle';
  let tall = 130; ctx.font = `${tall}px Display`;
  while (ctx.measureText(category.label).width > 1160 && tall > 50) ctx.font = `${tall -= 6}px Display`;
  ctx.fillText(category.label, x + 80, y + 170);
  ctx.font = '60px Mono'; ctx.textAlign = 'center'; ctx.fillText('ADMIT ONE', tear + 250, y + 170);
  ctx.textAlign = 'left'; ctx.fillStyle = INK; ctx.textBaseline = 'top';
  let size = 170; ctx.font = `${size}px Display`;
  const words = collection.name.split(/\s+/), rows = [''];
  const wrap = () => { rows.length = 1; rows[0] = ''; for (const word of words) { const tried = rows.at(-1) ? `${rows.at(-1)} ${word}` : word; if (ctx.measureText(tried).width <= 1140 || !rows.at(-1)) rows[rows.length - 1] = tried; else rows.push(word); } };
  for (wrap(); (rows.length > 3 || rows.some(row => ctx.measureText(row).width > 1140)) && size > 60; wrap()) ctx.font = `${size -= 8}px Display`;
  rows.forEach((row, line) => ctx.fillText(row, x + 80, y + 400 + line * size));
  ctx.font = '58px Mono';
  [person?.name, collection.edition].filter(Boolean).forEach((line, row) => ctx.fillText(line, x + 84, y + 430 + rows.length * size + row * 84));
  ctx.textBaseline = 'alphabetic'; ctx.font = '300px Display'; ctx.fillText(number, x + 70, y + h - 90);
  ctx.font = '64px Mono'; ctx.fillText(`of ${total}`, x + 90 + ctx.measureText('').width + 640, y + h - 110);
  // The stub says the number again, on its side, for whoever keeps it.
  ctx.save(); ctx.translate(tear + 330, y + h - 110); ctx.rotate(-Math.PI / 2); ctx.font = '230px Display'; ctx.fillText(number, 0, 0); ctx.restore();
  if (person?.image) {
    const face = await createImageBitmap(await (await fetch(person.image)).blob()), cx = tear + 250, cy = y + 560, r = 170, scale = 2 * r / Math.min(face.width, face.height);
    ctx.save(); ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2); ctx.clip();
    ctx.drawImage(face, cx - face.width * scale / 2, cy - face.height * scale / 2, face.width * scale, face.height * scale);
    ctx.restore(); face.close();
    ctx.lineWidth = 12; ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2); ctx.stroke();
  }
  return { png: canvas.toDataURL('image/png'), info: { template: 'stub/1' } };
}
