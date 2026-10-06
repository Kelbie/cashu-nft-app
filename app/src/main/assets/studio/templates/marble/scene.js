// Marbled paper. Every picture is drawn from fresh randomness and not from the seed, so no two
// are ever the same, not even two made from one collection twice, and a mint never turns one
// away as a picture it has minted before. `options.color` leads the inks.
const SIZE = 2160, INK = '#111111', PAPER = '#fffdf7';
const canvas = Object.assign(document.createElement('canvas'), { width: SIZE, height: SIZE });
const ctx = canvas.getContext('2d');
await Promise.all(['Display', 'Mono'].map(async family => document.fonts.add(await new FontFace(family, `url(/assets/${family}.ttf)`).load())));

const fresh = () => crypto.getRandomValues(new Uint32Array(1))[0] / 4294967296;
const INKS = ['#c8f53c', '#ff6a1f', '#ffd23d', '#7cc7ff', '#f4f0e6', '#e5383b', '#1b1b1b', '#9b8cff'];

export async function renderTicketArt({ serial, total, collection, category, person, options }) {
  const lead = /^#[0-9a-f]{6}$/i.test(options?.color ?? '') ? options.color : INKS[Math.floor(fresh() * INKS.length)];
  ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.globalAlpha = 1; ctx.textAlign = 'left';
  ctx.fillStyle = PAPER; ctx.fillRect(0, 0, SIZE, SIZE);
  // Bands of ink, each a wave of its own, laid one over another from the top down.
  for (let band = 0; band < 46; band++) {
    const base = -200 + band * 56 + fresh() * 60, colour = band % 3 === 0 ? lead : INKS[Math.floor(fresh() * INKS.length)];
    const waves = [0, 1, 2].map(() => ({ reach: 20 + fresh() * 110, length: 260 + fresh() * 900, shift: fresh() * Math.PI * 2 }));
    ctx.beginPath(); ctx.moveTo(0, SIZE);
    for (let x = 0; x <= SIZE; x += 12) ctx.lineTo(x, base + waves.reduce((y, wave) => y + wave.reach * Math.sin(x / wave.length * Math.PI * 2 + wave.shift), 0));
    ctx.lineTo(SIZE, SIZE); ctx.closePath();
    ctx.globalAlpha = 0.55 + fresh() * 0.45; ctx.fillStyle = colour; ctx.fill();
    ctx.globalAlpha = 1; ctx.lineWidth = 5; ctx.strokeStyle = INK; ctx.stroke();
  }
  // Drops, the last thing to fall.
  for (let drop = 0; drop < 14; drop++) {
    ctx.beginPath(); ctx.arc(fresh() * SIZE, fresh() * SIZE, 14 + fresh() * 70, 0, Math.PI * 2);
    ctx.fillStyle = INKS[Math.floor(fresh() * INKS.length)]; ctx.fill(); ctx.lineWidth = 5; ctx.strokeStyle = INK; ctx.stroke();
  }
  if (person?.image) {
    const face = await createImageBitmap(await (await fetch(person.image)).blob()), x = 1640, y = 520, r = 330, scale = 2 * r / Math.min(face.width, face.height);
    ctx.save(); ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.clip();
    ctx.drawImage(face, x - face.width * scale / 2, y - face.height * scale / 2, face.width * scale, face.height * scale);
    ctx.restore(); face.close();
    ctx.lineWidth = 16; ctx.strokeStyle = INK; ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.stroke();
  }
  // What it is, on a label pasted over the paper.
  let size = 150; ctx.font = `${size}px Display`;
  while (ctx.measureText(collection.name).width > 1620 && size > 56) ctx.font = `${size -= 6}px Display`;
  const named = ctx.measureText(collection.name).width;
  const lines = [category.label, person?.name, collection.edition].filter(Boolean).join('  ·  ');
  ctx.font = '60px Mono'; const wide = Math.max(1100, Math.min(1760, Math.max(named, ctx.measureText(lines).width) + 140));
  ctx.fillStyle = INK; ctx.beginPath(); ctx.roundRect(150 + 20, 1330 + 20, wide, 680, 56); ctx.fill();
  ctx.fillStyle = PAPER; ctx.beginPath(); ctx.roundRect(150, 1330, wide, 680, 56); ctx.fill(); ctx.lineWidth = 12; ctx.strokeStyle = INK; ctx.stroke();
  ctx.fillStyle = INK; ctx.textBaseline = 'top'; ctx.font = `${size}px Display`; ctx.fillText(collection.name, 220, 1390);
  ctx.font = '60px Mono'; ctx.fillText(lines, 224, 1400 + size * 1.1);
  ctx.textBaseline = 'alphabetic'; ctx.font = '330px Display'; ctx.fillText(String(serial).padStart(3, '0'), 210, 1960);
  ctx.font = '64px Mono'; ctx.textAlign = 'right'; ctx.fillText(`of ${total}`, 150 + wide - 70, 1950);
  return { png: canvas.toDataURL('image/png'), info: { template: 'marble/1' } };
}
