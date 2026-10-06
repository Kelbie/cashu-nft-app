// Rings on a night sky: each ticket gets its own set from the collection's seed, in the colour
// of its kind. A plain canvas and two typefaces. `options.color` is the colour of the rings.
const SIZE = 2160;
const canvas = Object.assign(document.createElement('canvas'), { width: SIZE, height: SIZE });
const ctx = canvas.getContext('2d');
await Promise.all(['Display', 'Mono'].map(async family => document.fonts.add(await new FontFace(family, `url(/assets/${family}.ttf)`).load())));

function random(seed) {
  let state = Number.parseInt(seed.slice(0, 8), 16) >>> 0;
  return () => { state += 0x6d2b79f5; let value = state; value = Math.imul(value ^ value >>> 15, value | 1); value ^= value + Math.imul(value ^ value >>> 7, value | 61); return ((value ^ value >>> 14) >>> 0) / 4294967296; };
}

export async function renderTicketArt({ serial, total, seed, collection, category, person, options }) {
  const colour = /^#[0-9a-f]{6}$/i.test(options?.color ?? '') ? options.color : '#c8f53c', next = random(seed);
  ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.textAlign = 'left'; ctx.globalAlpha = 1;
  const sky = ctx.createLinearGradient(0, 0, 0, SIZE);
  sky.addColorStop(0, '#0d0d12'); sky.addColorStop(1, '#1c1b22');
  ctx.fillStyle = sky; ctx.fillRect(0, 0, SIZE, SIZE);
  // Stars, then the rings around a point that moves a little from ticket to ticket.
  for (let star = 0; star < 220; star++) { ctx.globalAlpha = 0.15 + next() * 0.6; ctx.fillStyle = '#f4f0e6'; ctx.fillRect(next() * SIZE, next() * SIZE, 5, 5); }
  const x = 1250 + next() * 420, y = 760 + next() * 300;
  for (let ring = 0; ring < 26; ring++) {
    const r = 70 + ring * (34 + next() * 14), from = next() * Math.PI * 2;
    ctx.globalAlpha = 0.18 + next() * 0.8; ctx.strokeStyle = ring % 7 === 3 ? '#f4f0e6' : colour; ctx.lineWidth = 5 + next() * 22; ctx.lineCap = 'round';
    ctx.beginPath(); ctx.arc(x, y, r, from, from + Math.PI * (0.5 + next() * 1.5)); ctx.stroke();
  }
  ctx.globalAlpha = 1;
  if (person?.image) {
    const face = await createImageBitmap(await (await fetch(person.image)).blob()), r = 300, scale = 2 * r / Math.min(face.width, face.height);
    ctx.save(); ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.clip();
    ctx.drawImage(face, x - face.width * scale / 2, y - face.height * scale / 2, face.width * scale, face.height * scale);
    ctx.restore(); face.close();
    ctx.strokeStyle = colour; ctx.lineWidth = 16; ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.stroke();
  }
  ctx.fillStyle = '#f4f0e6'; ctx.textBaseline = 'top';
  let size = 190; ctx.font = `${size}px Display`;
  while (ctx.measureText(collection.name).width > 1860 && size > 60) ctx.font = `${size -= 6}px Display`;
  ctx.fillText(collection.name, 150, 140);
  ctx.font = '68px Mono'; ctx.fillStyle = colour;
  ctx.fillText([category.label, person?.name, collection.edition].filter(Boolean).join('  ·  ').toUpperCase(), 156, 150 + size * 1.08);
  ctx.textBaseline = 'alphabetic'; ctx.fillStyle = '#f4f0e6'; ctx.font = '620px Display';
  ctx.fillText(String(serial).padStart(3, '0'), 130, 1990);
  ctx.font = '76px Mono'; ctx.fillStyle = colour; ctx.textAlign = 'right';
  ctx.fillText(`${serial} / ${total}`, 2010, 1990);
  return { png: canvas.toDataURL('image/png'), info: { template: 'rings/1' } };
}
