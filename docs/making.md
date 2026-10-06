# Making collections

The app can create a collection and mint its NFTs without a computer. This page
says how that works and what to be careful with.

## What happens when you make one

**Create**, at the foot of the Collections tab on Home, starts one; the map of
the app is in [app.md](app.md). You give a name, choose a template and say how
many of each kind. For every NFT the phone then does two things:

1. **Draws it.** The template draws a square picture, and the phone writes into
   the picture what the NFT is: its name, its kind, its number, its
   collection, and a guest's name if it has one. That is the metadata of
   NUT-XX, in the picture's colour profile, which a mint keeps.
2. **Mints it** on the site, exactly as the site's "Add an image" does.

The grid is decided before the first NFT is drawn: each has its place from the
start. The screen begins close on the first place and draws back each time the
number made reaches a power of two, to take in twice as many. A place whose
NFT is not made yet is a field of dots that swell and shrink, darker for the
one being made now. Each NFT takes its place as it is minted. One takes about three seconds: the
site allows an address 240 requests a minute and a mint is nine of them, so
the phone paces itself. A hundred tickets take about five minutes. If the
network drops, what is left stays asked for and **Carry on** makes it.

The phone remembers the template, the ticket types and the last number, so
**Mint more NFTs**, under **More** on the collection's page, adds more later
and numbers them on. The site lists an NFT under a title like `VIP · 12 of 50`. Before carrying on after a failure, and before
issuing more, the phone finishes whatever the last attempt left half done and
reads the collection's page: an NFT that was minted while its answer was lost
is counted, and the next number is one more than the highest the page lists.

## Two pages, kept apart

Drawing and minting run in two hidden web pages that cannot reach each other.

- **The template's page** is at an address no network has,
  `https://<template>.studio.app.invalid`. The app serves it the template's
  own files and three.js and answers every other address with "not found". It
  holds no key and cannot reach the site or the minting page, so a template
  can neither mint nor take anything from the collection. That is what makes
  it reasonable to run one that somebody else, or an AI, wrote.

  It is not a perfect cage. A WebView cannot switch off WebRTC, the one way a
  page reaches the network that is not a request for an address. The page
  removes what it can of it before a template runs, but a script written to
  get around that could send what it was given to draw: the collection's
  name, its kinds and numbers, and a guest's name and photo. Read a pasted
  script, or have it read, before giving it real guests' photos.
- **The minting page** is at the site's own address, and runs the site's own
  wallet code, built into the app from `cashu-nft`. The collection's key is
  made there and kept where the site keeps a browser's keys. Being on the
  site's address is also what lets an upload pass the site's check that it
  comes from a browser.

`studio/Press.kt` is both pages. `app/src/main/assets/studio` is built, not
written: `node scripts/tickets/studio.mjs ../cashu-nft-app/app/src/main/assets/studio`
in `cashu-nft` makes it from `scripts/tickets/studio` and
`scripts/tickets/templates`. Build it again whenever the site's wallet code or
a template changes, and never edit it here.

## The key

A collection made on the phone has one key, and it is on the phone. Whoever
has it can send every NFT the collection still holds and issue more.

**Copy backup key**, under **More** on the collection's page, puts it on the
clipboard, marked so that keyboards do not show it. Keep it somewhere safe.
The **Copy backup key** in Settings is another key, the account's: it brings
back what the account holds, not the collections it made. On the site, **Import a
key** opens the collection in any browser, which is how you sell or send its
tickets from a computer. Removing the app, or clearing its data, destroys the
key: without a copy, the tickets not yet handed over are lost with it.
Tickets already handed over are their holders' and are not affected.

## Selling or sending one

Tap an NFT that was just minted, or use **Sell or send** on the collection's
page or on its door. The steps are in [selling.md](selling.md).

## What an NFT says about itself

Each NFT carries its metadata inside its picture (NUT-XX, `nft-metadata/2`).
Every NFT says the same few things in the same places: its name, its kind
("VIP", "Fox"), its number in the collection and what that is out of, what
its run is called, and which collection it is of, by the
collection's key. Whatever else it says is an attribute with a type: text, a
whole number (with decimal places, a unit and what it is out of, when it has
them), yes or no, or a date. A guest's name, when one was given, is the
attribute `Guest`. It may also name things elsewhere that go with it, which
**Details** lists and the phone's browser opens when asked; the app never
fetches one by itself.

Being a ticket is not part of that: it is an extension, `ticket`, that an NFT
of a collection of tickets carries beside the rest. It says the door of the
NFT's collection admits it, so that a guest's phone lists it with their
tickets and knows which door it is for with nobody to ask. A picture written
in the older `nft-metadata/1` says nothing to the app and is shown plain.

The form shows a picture of one NFT of each ticket type side by side, and
brings the one being changed into view.

## Numbering

Every NFT's title and metadata say its number and what it is of: "Crew · 3 of
40". The "of" is the collection's first run and never changes. One minted later
counts past it, "41 of 40", as a card printed after a set does, so that no
picture already in somebody's wallet is made untrue. Where the app shows the
number outside the picture, in **Details**, it says what the collection has
really come to: "41 of 48". A collection made before the app kept this count
takes its "of" from its first NFT.

## Deleting a collection

**More** on the collection's page, then **Delete collection**. A sheet says what
will happen, and its button has to be held for 1.6 seconds, timed by
the clock and not by an animation: a tap does nothing, and neither does a
phone with animations switched off.

For a collection this phone minted, deleting does what the site's own "Delete"
does to one NFT, to every NFT the collection still holds: the mint burns its
credential, so a link to it or a transfer file of it is dead too, and the site
drops its card and its picture. Before that everything it has for sale comes off
sale. What is burned is what the site lists as still the collection's at that
moment, not what the phone remembers. Four things it does not do:

- **What was sent or sold stays.** Those NFTs are their holders', and only a
  holder can burn one.
- **The collection is not removed from the site.** The site has no way to
  remove a collection. Once nothing unsold is left under it, its name there
  becomes "[deleted]".
- **Money is not thrown away.** A collection that still holds sats at any mint,
  spendable or tied up in a sale, or sats taken out of it and not yet in the
  wallet, is not deleted from its page while it holds them. **Move to wallet**
  takes out what it holds at the mint it receives payments at: see
  [selling.md](selling.md).
- **A sale that is settling is not cut short.** The site will not take such an
  NFT off sale, so nothing is burned and the sheet says why. Once the sale is
  done that NFT is the buyer's, and holding again deletes the rest.

The phone forgets the collection's key, and takes the collection off the phone,
only when the site's page shows nothing unsold left under it. Until then the
collection stays on the phone with its key, the sheet says how many were burned
and how many could not be, and holding again tries the rest. A page that could
not be read afterwards is not taken for an empty one: the collection is kept
and holding again finishes.

A large collection takes a while. A site lets one address ask it 240 things in
a minute and refuses the rest, and it is two requests to take an NFT off sale
and two to burn one. So while a collection is deleted, everything the app's
page asks the site waits its turn: forty at once, then two a second, about
fifty NFTs a minute. Something the site refuses for asking too much, because
another phone on the same network was asking too, is asked again a quarter of
a minute later. The sheet counts as it goes, and holding again carries on with
whatever is left.

What the site shows afterwards: the market and the collection's page are never
cached, so the NFTs are gone from both at once. A link to an old listing says it
is no longer for sale. The collection's small preview picture is kept by
browsers for five minutes.

A collection that was only added, to keep a door for it, has **Remove from
this phone** in the same place: it is taken off the phone
and nothing changes on the site.

Deleting an account, or everything, does the same to each collection the
account made, and only there can a collection's sats be deleted with it, by a
switch that says so: see [app.md](app.md).

## Templates

The app brings five, all of them tickets. **Poster**, **Rings**, **Stub** and
**Sunrise** draw with a plain canvas, each a little different for every NFT.
**Marble** draws from fresh randomness and never makes the same picture twice,
so a mint never refuses one as a picture it has already minted.

Two more are kept in `cashu-nft`'s `scripts/tickets/examples` and are not in
the app unless it is built with them, by naming their folders when the studio
is built: **bitcoin++ Seoul**, which builds each ticket in three.js, and
**Pocket Critters**, which is not tickets but a set of seven creatures in three
stages each, where an NFT's place among its kind decides its stage and chance
makes a few of them shiny. A phone that made a collection with one of them can
mint more of it only while its app still has that template. Whatever is
bundled, the ticket templates are listed first, so a new collection starts as
tickets.

A template is a folder with a `scene.js` that draws one picture for each NFT
and a `template.json` that names it and suggests kinds; their home is
`cashu-nft`'s `scripts/tickets/templates`, and `TEMPLATE.md` there is the
contract. The same templates draw a whole collection on a computer with
`generate.py`.

**+ Your own** takes a `scene.js` you paste. **Copy instructions** puts the
instructions for writing one on the clipboard: give them to an AI with a
description of what you want, and paste back what it writes. The
preview draws at once; if the script fails, the screen says why, in the
script's own words, which is what to send back to whoever wrote it. A pasted
template has no files of its own, so it uses the phone's fonts.

A script need not draw tickets. It can say what its collection is made of:
its kinds, how many of each to make at first, and that its NFTs are not
tickets, in which case none of them names a door. The form is filled in from
that when the script is pasted, and stays yours to change. A script can also
name each NFT, describe it and give it attributes and links of its own, which
the picture carries with the rest of what it says; its kind, number and
collection are the form's to say. The site still lists it by its kind and number, "Embit ·
5 of 21", because a price is set for a kind.

To decide what to draw, a script is told each NFT's number in the collection,
its place among its own kind, a seed that is that NFT's for good, and entropy
that is new with every drawing. So a design can be fixed to a place, as the
third of a kind being its final form, while a rare variant is left to chance.
The preview of a kind shows the first of it that would be made, with the
number it would have. What chance decides differs between a preview and the
NFT that is minted.

One NFT can be somebody's own: when exactly one is being made, the form takes
a name and a photo, and the template is given both.

## What has not been tried

- **Minting on the live site.** Everything above was run on an emulator
  against a local copy of the site. The live site checks uploads with
  Cloudflare Turnstile, which a local copy does not. The minting page loads
  Turnstile as the site does, from the site's address; whether Cloudflare
  passes a phone's WebView is not known until it is tried.
- **A real phone's graphics.** The three.js template ran in the emulator's
  WebView. A phone's own GPU should only be faster.
- **Very large batches.** The form allows 200 of a kind at a time. The site
  keeps at most 100 unfinished mints for a collection and has a storage limit
  for all collections together.
