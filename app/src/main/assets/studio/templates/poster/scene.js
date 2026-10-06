// A poster in the look of nonfungible.cash: one flat colour, heavy type, hard shadows. It draws
// with a plain canvas, so it needs nothing but its two typefaces. `options.color` is the ground.
const SIZE = 2160, INK = '#111111', PAPER = '#fffdf7';
const canvas = Object.assign(document.createElement('canvas'), { width: SIZE, height: SIZE });
const ctx = canvas.getContext('2d');
await Promise.all([['Display', 'Display'], ['Mono', 'Mono']].map(async ([family, file]) => document.fonts.add(await new FontFace(family, `url(/assets/${file}.ttf)`).load())));

// The collection's seed makes each ticket's shapes its own, and the same every time.
function random(seed) {
  let state = Number.parseInt(seed.slice(0, 8), 16) >>> 0;
  return () => { state += 0x6d2b79f5; let value = state; value = Math.imul(value ^ value >>> 15, value | 1); value ^= value + Math.imul(value ^ value >>> 7, value | 61); return ((value ^ value >>> 14) >>> 0) / 4294967296; };
}
const dark = colour => { const [r, g, b] = [1, 3, 5].map(at => parseInt(colour.slice(at, at + 2), 16)); return 0.299 * r + 0.587 * g + 0.114 * b < 110; };
// The largest size at which the words fit a width, wrapped to at most `lines` lines.
function fitted(text, family, width, lines, most) {
  for (let size = most; size > 40; size -= 8) {
    ctx.font = `${size}px ${family}`;
    const rows = [''];
    for (const word of text.split(/\s+/)) {
      const tried = rows.at(-1) ? `${rows.at(-1)} ${word}` : word;
      if (ctx.measureText(tried).width <= width || !rows.at(-1)) rows[rows.length - 1] = tried; else rows.push(word);
    }
    if (rows.length <= lines && rows.every(row => ctx.measureText(row).width <= width)) return { size, rows };
  }
  return { size: 40, rows: [text] };
}
// Anything pressable on the site stands on a hard shadow; so does everything here.
function standing(draw, offset = 22) {
  ctx.save(); ctx.translate(offset, offset); draw(INK, INK); ctx.restore();
  draw();
}
function pill(text, x, y, size, fill, ink, turn = 0) {
  ctx.save(); ctx.translate(x, y); ctx.rotate(turn);
  ctx.font = `${size}px Display`;
  const width = ctx.measureText(text).width + size * 1.1, height = size * 1.7;
  standing((face = fill, edge = INK) => {
    ctx.beginPath(); ctx.roundRect(0, 0, width, height, height / 2); ctx.fillStyle = face; ctx.fill();
    ctx.lineWidth = 10; ctx.strokeStyle = edge; ctx.stroke();
  }, 14);
  ctx.fillStyle = ink; ctx.textBaseline = 'middle'; ctx.fillText(text, size * 0.55, height / 2 + size * 0.04);
  ctx.restore();
  return width;
}

export async function renderTicketArt({ serial, total, seed, collection, category, person, options }) {
  const ground = /^#[0-9a-f]{6}$/i.test(options?.color ?? '') ? options.color : '#c8f53c';
  const ink = dark(ground) ? PAPER : INK, next = random(seed);
  ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.textAlign = 'left';
  ctx.fillStyle = ground; ctx.fillRect(0, 0, SIZE, SIZE);

  // Shapes behind everything: the site's stickers, scattered.
  const colours = ['#ff6a1f', '#ffd23d', PAPER, INK, '#c8f53c'].filter(colour => colour.toLowerCase() !== ground.toLowerCase());
  // They keep to the top right, clear of the name and the number.
  for (let shape = 0; shape < 4; shape++) {
    const x = 1330 + next() * 560, y = 300 + next() * 700, r = 110 + next() * 150, colour = colours[Math.floor(next() * colours.length)], kind = Math.floor(next() * 3);
    standing((face = colour, edge = INK) => {
      ctx.beginPath();
      if (kind === 0) ctx.arc(x, y, r, 0, Math.PI * 2);
      else if (kind === 1) ctx.roundRect(x - r, y - r * 0.6, r * 2, r * 1.2, 48);
      else for (let point = 0; point < 16; point++) { const a = point * Math.PI / 8, d = point % 2 ? r * 0.55 : r; ctx.lineTo(x + Math.cos(a) * d, y + Math.sin(a) * d); }
      ctx.closePath(); ctx.fillStyle = face; ctx.fill(); ctx.lineWidth = 12; ctx.strokeStyle = edge; ctx.stroke();
    });
  }

  // Somebody's own ticket carries their face.
  if (person?.image) {
    const face = await createImageBitmap(await (await fetch(person.image)).blob()), x = 1560, y = 760, r = 380;
    standing((fill = PAPER, edge = INK) => { ctx.beginPath(); ctx.arc(x, y, r + 16, 0, Math.PI * 2); ctx.fillStyle = fill === INK ? INK : PAPER; ctx.fill(); ctx.lineWidth = 14; ctx.strokeStyle = edge; ctx.stroke(); }, 26);
    ctx.save(); ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.clip();
    const scale = 2 * r / Math.min(face.width, face.height);
    ctx.drawImage(face, x - face.width * scale / 2, y - face.height * scale / 2, face.width * scale, face.height * scale);
    ctx.restore(); face.close();
  }

  // The event, top left, as large as it fits.
  const name = fitted(collection.name, 'Display', 1000, 4, 230);
  ctx.font = `${name.size}px Display`; ctx.fillStyle = ink; ctx.textBaseline = 'top';
  name.rows.forEach((row, line) => ctx.fillText(row, 150, 150 + line * name.size * 0.98));
  let below = 150 + name.rows.length * name.size * 0.98 + 50;
  if (collection.edition) { ctx.font = '64px Mono'; ctx.fillText(collection.edition, 156, below); below += 110; }
  pill(category.label, 150, below + 20, 92, INK, PAPER, -0.035);
  if (person?.name) pill(person.name, 150, below + 240, 92, PAPER, INK, 0.02);

  // The number, the biggest thing on the page.
  const digits = String(serial).padStart(3, '0');
  ctx.font = '880px Display'; ctx.textBaseline = 'alphabetic';
  const wide = Math.min(1, 1500 / ctx.measureText(digits).width);
  ctx.save(); ctx.translate(130, 1960); ctx.scale(wide, 1); ctx.lineJoin = 'round';
  ctx.fillStyle = INK; ctx.fillText(digits, 26, 26);
  ctx.lineWidth = 44; ctx.strokeStyle = INK; ctx.strokeText(digits, 0, 0);
  ctx.fillStyle = PAPER; ctx.fillText(digits, 0, 0);
  ctx.restore();
  ctx.font = '76px Mono'; ctx.fillStyle = ink; ctx.textAlign = 'right';
  ctx.fillText(`of ${total}`, 2010, 1990);
  return { png: canvas.toDataURL('image/png'), info: { template: 'poster/1' } };
}
