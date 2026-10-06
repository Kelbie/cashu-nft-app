// A sun coming up over stripes of colour, a little different for every NFT. A plain canvas and
// two typefaces. `options.color` is the sun.
const SIZE = 2160, INK = '#111111', PAPER = '#fffdf7';
const canvas = Object.assign(document.createElement('canvas'), { width: SIZE, height: SIZE });
const ctx = canvas.getContext('2d');
await Promise.all(['Display', 'Mono'].map(async family => document.fonts.add(await new FontFace(family, `url(/assets/${family}.ttf)`).load())));

function random(seed) {
  let state = Number.parseInt(seed.slice(0, 8), 16) >>> 0;
  return () => { state += 0x6d2b79f5; let value = state; value = Math.imul(value ^ value >>> 15, value | 1); value ^= value + Math.imul(value ^ value >>> 7, value | 61); return ((value ^ value >>> 14) >>> 0) / 4294967296; };
}

export async function renderTicketArt({ serial, total, seed, collection, category, person, options }) {
  const sun = /^#[0-9a-f]{6}$/i.test(options?.color ?? '') ? options.color : '#ff6a1f', next = random(seed);
  ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.textAlign = 'left'; ctx.globalAlpha = 1;
  ctx.fillStyle = '#f4f0e6'; ctx.fillRect(0, 0, SIZE, SIZE);
  // The sun sits somewhere along the horizon, with rings going out from it.
  const x = 700 + next() * 760, horizon = 1240, rings = 5 + Math.floor(next() * 4);
  ctx.save(); ctx.beginPath(); ctx.rect(0, 0, SIZE, horizon); ctx.clip();
  for (let ring = rings; ring >= 0; ring--) {
    ctx.beginPath(); ctx.arc(x, horizon, 300 + ring * (120 + next() * 30), 0, Math.PI * 2);
    ctx.fillStyle = ring === 0 ? sun : ring % 2 ? '#ffd23d' : PAPER; ctx.fill(); ctx.lineWidth = 12; ctx.strokeStyle = INK; ctx.stroke();
  }
  ctx.restore();
  // Stripes below the horizon: the sea, or the road in.
  const stripes = ['#111111', sun, '#ffd23d', '#c8f53c', '#111111', PAPER];
  for (let stripe = 0, y = horizon; y < SIZE; stripe++) {
    const tall = 70 + next() * 150;
    ctx.fillStyle = stripes[(stripe + Math.floor(next() * 2)) % stripes.length]; ctx.fillRect(0, y, SIZE, tall + 2);
    ctx.fillStyle = INK; ctx.fillRect(0, y, SIZE, 10); y += tall;
  }
  if (person?.image) {
    const face = await createImageBitmap(await (await fetch(person.image)).blob()), r = 290, scale = 2 * r / Math.min(face.width, face.height);
    ctx.save(); ctx.beginPath(); ctx.arc(x, horizon - 330, r, 0, Math.PI * 2); ctx.clip();
    ctx.drawImage(face, x - face.width * scale / 2, horizon - 330 - face.height * scale / 2, face.width * scale, face.height * scale);
    ctx.restore(); face.close();
    ctx.lineWidth = 14; ctx.strokeStyle = INK; ctx.beginPath(); ctx.arc(x, horizon - 330, r, 0, Math.PI * 2); ctx.stroke();
  }
  // The name on a strip of sky kept clear for it.
  let size = 170; ctx.font = `${size}px Display`;
  while (ctx.measureText(collection.name).width > 1860 && size > 60) ctx.font = `${size -= 6}px Display`;
  ctx.fillStyle = PAPER; ctx.fillRect(0, 0, SIZE, 150 + size * 1.08 + 110); ctx.fillStyle = INK; ctx.fillRect(0, 150 + size * 1.08 + 110, SIZE, 10);
  ctx.textBaseline = 'top'; ctx.fillText(collection.name, 150, 110);
  ctx.font = '64px Mono'; ctx.fillText([category.label, person?.name, collection.edition].filter(Boolean).join('  ·  '), 156, 120 + size * 1.08);
  // The number on a plate of its own, so it reads over any stripe.
  ctx.font = '380px Display'; const digits = String(serial).padStart(3, '0'), wide = ctx.measureText(digits).width + 130;
  ctx.fillStyle = INK; ctx.beginPath(); ctx.roundRect(150 + 22, 1560 + 22, wide, 440, 60); ctx.fill();
  ctx.fillStyle = PAPER; ctx.beginPath(); ctx.roundRect(150, 1560, wide, 440, 60); ctx.fill(); ctx.lineWidth = 12; ctx.strokeStyle = INK; ctx.stroke();
  ctx.fillStyle = INK; ctx.textBaseline = 'alphabetic'; ctx.fillText(digits, 210, 1915);
  ctx.font = '64px Mono'; ctx.textAlign = 'right';
  ctx.fillStyle = PAPER; ctx.fillRect(1760, 1880, 260, 100); ctx.strokeRect(1760, 1880, 260, 100);
  ctx.fillStyle = INK; ctx.fillText(`of ${total}`, 1990, 1954);
  return { png: canvas.toDataURL('image/png'), info: { template: 'sunrise/1' } };
}
