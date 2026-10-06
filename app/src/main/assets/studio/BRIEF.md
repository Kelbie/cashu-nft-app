# Brief: write a template that draws NFTs

You are writing one JavaScript module, `scene.js`. It draws a square picture for each NFT
of a collection: each ticket of an event, or each piece of a set of collectibles. Reply with
the module's code and nothing else.

## What the module exports

```js
// Optional. What the collection is made of, when it is more than tickets of one kind.
export const template = {
  ticket: false,
  edition: 'Series one',
  kinds: [
    { label: 'Fox', count: 3, options: { color: '#ff6a1f' } },
    { label: 'Owl', count: 3, options: { color: '#6a5cff' } },
  ],
};

export async function renderTicketArt({ serial, total, index, seed, entropy, collection, category, person, options }) {
  // draw, then:
  return {
    png: canvas.toDataURL('image/png'),
    // Optional. What this NFT says it is.
    nft: { name: 'Fox cub', description: 'The first of three.', attributes: [{ trait_type: 'Stage', value: 1, max_value: 3 }] },
  };
}
```

## What a collection is made of

`template` is optional. Without it the NFTs are tickets of one kind, and whoever makes the
collection names the kinds and says how many of each.

- `ticket` is `false` for NFTs that are not tickets: art, characters, badges. Such an NFT
  does not say it opens a door. Left out, they are tickets.
- `kinds` are the kinds of NFT, in the order they are made: at least one, 40 at most, no
  two with the same label. Each has a `label` of up to 24 characters, a `count` of how many
  to make at first (0 to 200), and `options`, an object of your own that is handed back to
  you with every NFT of that kind.
- `edition` is a short line under the collection's name, 28 characters at most.

Whoever makes the collection can change a label or a count, add a kind and mint more later.
So draw any number you are given, not only the ones you planned for, and any kind: one that
was added comes with the `options` of one of yours, or with none.

## What each NFT is given

- `serial` is this NFT's number in the whole collection, from 1. `total` is how many the
  collection's first run has; one minted later counts past it.
- `index` is its place among its own kind, from 1: the first of every kind has `index` 1,
  whatever its `serial`. It counts on when more of a kind are minted later.
- `seed` is 64 hex characters, different for every NFT and the same every time that NFT
  is drawn.
- `entropy` is 64 hex characters that are new every time anything is drawn and never come
  again.
- `collection.name` is the collection's name and `collection.edition` the line under it, or ''.
- `category.label` is the kind of NFT, such as "General admission" or "Fox".
- `person` is null, or has a `name` and sometimes an `image`, a data URL of their portrait.
- `options` is this kind's settings. `options.color`, when present, is a colour like `#c8f53c`
  that should set this kind apart from the others.

Decide each thing from the input that fits it:

- What an NFT **is** (which design, which stage of it) comes from `category`, `options`,
  `index` or `serial`, so that a given place always holds the same design.
- What merely differs from one NFT to the next (a tilt, a pattern, freckles) comes from
  `seed`, so that the same NFT can be drawn again as it was.
- What must be a surprise (a rare variant nobody can predict from its number) comes from
  `entropy`. A preview and the NFT that is minted get different `entropy`. An NFT is drawn
  once to be minted and that picture is the NFT, so what `entropy` decided for it stays.

Do not use `Math.random()` or the date.

## What an NFT says it is

`nft` is optional. Every NFT already carries the collection's name, its kind, its number and
the edition, and a guest's name when it has one. Return `nft` to say more:

- `name` is what this NFT is called, up to 80 characters. Left out, it is the collection's name.
- `description` is up to 1000 characters, and may have line breaks.
- `attributes` are up to 32 facts, most important first (31 for an NFT with a guest, whose
  name the app adds after yours). Each has a `trait_type` of up to 40 characters and a
  `value` that is one of:
  - text, up to 120 characters: `{ trait_type: 'Element', value: 'Fire' }`;
  - `true` or `false`: `{ trait_type: 'Shiny', value: true }`, not "Yes" or "No";
  - a whole number: `{ trait_type: 'Stage', value: 2, max_value: 3 }`.
- A number is always whole. For a decimal, write the digits and say where the point goes with
  `dp`, 0 to 9: 12.5 kg is `{ trait_type: 'Weight', value: 125, dp: 1, unit: 'kg' }`.
  `max_value` is whole too and is read with the same `dp`. `unit` is up to 12 characters.
  `max_value`, `dp` and `unit` go only with a number.
- A date is a whole number of seconds since 1970 (UTC) with `type: 'date'`:
  `{ trait_type: 'Born', value: 1767225600, type: 'date' }`. It takes no `max_value`, `dp`
  or `unit`. Otherwise leave `type` out.
- `links` is optional: 1 to 8 things elsewhere that go with the NFT, each
  `{ rel, url, type?, sha256?, title? }`. `rel` says what it is: `external` (a page about
  it), `animation`, `audio`, `video`, `model`, `document`, `license` or `source`. `url` is
  an `https://` or `ipfs://` address with a lowercase host. `type` is a media type such as
  `video/mp4`, `sha256` the hash of what the address serves (give it for anything that is
  content), and `title` up to 80 characters. Nothing is fetched while the NFT is made.

Do not return `kind`, `edition`, `collection`, `must` or `ext`: the app writes those.

Text is a single line with something in it; only `description` may break its lines. An NFT
that breaks one of these rules is not made, and the error says which.

None of this is drawn on the picture for you. What an NFT says goes inside its file, for
wallets to show beside it; the picture is all yours, so write on it whatever it should show.

## What the module may use

- The DOM canvas (`document.createElement('canvas')`, 2D or WebGL) and anything else built
  into a browser.
- three.js: `import * as THREE from 'three'` and add-ons from `three/addons/…`
  (TextGeometry, FontLoader, RoomEnvironment, RoundedBoxGeometry, BufferGeometryUtils).
  With WebGL, create the renderer with `preserveDrawingBuffer: true`, call
  `renderer.render` once per NFT, and reuse one renderer for all of them.
- No network. Nothing can be fetched: no fonts, images or scripts from an address. Use the
  system's fonts (`sans-serif`, `serif`, `monospace`) or draw letter shapes yourself.

## What makes a good one

- The picture is a **2160 × 2160** PNG data URL. Draw on a canvas of that size.
- Text fits: measure it and shrink or wrap a long name.
- It reads at the size of a thumbnail: one strong shape and a few words. A ticket shows its
  number large. It is shown whole, often with its corners rounded, so keep what matters a
  little in from the edges.
- Every NFT is recognisably of the same collection, and no two are identical.
- It draws in well under a second. NFTs are drawn one at a time, so one canvas can be
  used for them all.
- If something goes wrong, throw an Error that says what; do not return a blank picture.
