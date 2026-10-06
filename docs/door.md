# How the door decides

The protocol is not the app's. Its one home is the **Entry** section of NUT-XX,
"Metadata and tickets in image NFTs", in the `nuts` repository, and the
reference for both sides is `scripts/tickets/entry.py` in `cashu-nft`. The app
is both sides of it on a phone. Where this page and the NUT disagree, the NUT
is right. What the design rests on, and where it is weak, is in
[security.md](security.md).

## Opening and closing the door

A door is some collection's. It is opened from the collection's page, with
**Open door**, and closed by leaving it: back, or to the collection's page. The
phone remembers an open door: one that was a door when the app was put away is
a door again when the app is opened, for as long as it still keeps that
collection. While no door is open the phone answers no tap as one. A
collection made on this phone whose NFTs are not tickets has no door.

The door's name, the sound for each result and **Return to the door
automatically** are in Settings, under **Doors on this phone**. The map of the
app is in [app.md](app.md).

## A request, not a scan

A ticket's public code can be copied by anyone who has seen it, so the door
never reads a code off a ticket. It asks a question that has not been asked
before, and only someone holding the ticket's credential can answer it.

The question is an ordinary NUT-18 payment request for `ticket`s from the
collection. Every request has a new id, and the proof is a showing of the whole
request text. An answer therefore fits one request of one door: it cannot be
saved for later or used at another door.

The request changes every half minute, as an authenticator's code does. It may
still be answered for one more period after it is replaced, so a holder who
read it just before it changed is let in all the same: at most a minute passes
between a request appearing and its last use. An answer to an older one admits
nobody; the door says it had expired. A request is answered once, and after any
answer the door acts on it asks a new one.

## Two ways to answer

**Held to the door.** This is the main way. The door's phone answers as an NFC
tag whose message is the request, the way a Cashu point of sale shows a payment
request to a wallet that taps it. The guest opens their ticket and holds their
phone to the door's; their phone reads the request, makes the proof, and writes
it back in the same tap. Their phone needs no network: the proof is made from
the credential it holds and the request, and nothing else. `tap/DoorTag.kt` is
the door's side and `tap/Tapped.kt` the guest's, and a test runs one against
the other command for command.

A phone held to the door with the app closed opens the app at that door, and
the guest is asked before anything is shown. On Android 15 and later the door
stops looking for cards of its own while it is on screen, so that a guest's
phone is never taken for one.

**Scanned.** A door with nothing to hold a phone to shows the request as a code,
with a bar that counts its time down. The guest scans it and their phone sends
the answer over nostr, to the key in the request. If their phone has no
network, it shows the answer back as a code and the door scans that, with
**Scan guest**. It is the same answer either way.

Under the door are the three ways in, each a button with its picture, for the
doorkeeper to choose between: **Tap**, where the door says **Hold your phone
here**; **Show code**, where it shows its code; and **Scan guest**, its own
camera, for the code a guest with no network shows back. The door remembers
which of the first two its keeper chose. On a phone with NFC switched off,
**Tap** opens the setting that switches it on; on one with none it is greyed.

One nostr key serves for as long as the door is on screen, so the phone joins
its relays once. What changes from request to request is its id.

A request is a few hundred characters, so its code is dense. The door listens
on few relays, because each one lengthens the request. The code is drawn with
every module a whole number of pixels, because a scaled, blurred code stops
reading well before it looks bad. And the status bar is hidden, because a
reader was seen to take its icons for part of a code.

## What the door trusts

The collection's site, and nothing else. The site's mint signed every
credential and keeps the record of which are spent, so it already decides which
tickets exist. The phone carries no pairing cryptography: the mint's verify
endpoint is the check, and the door takes the mint's word for the arithmetic as
well as for the state. A door is therefore only a door for collections on the
site the app is of. A page pasted or shared from somewhere else is not added.

The door admits when the answer is to a request it still accepts, the mint
verifies the proof as a showing of exactly that request and reports the
credential unspent and the asset active, and the collection lists the asset.
The checks that cost nothing run first, so an answer to another request never
reaches the mint.

**Anything the door cannot check refuses.** A timeout, an error or an answer
from the site that is not exactly what was expected admits nobody, and the
doorkeeper is told that the ticket was not checked rather than that it is bad.
A site that is busy, or a network that drops, is asked again a couple of times
first, within the twenty seconds a holder is made to wait at most. The
doorkeeper can then **Mark admitted** themselves.

## Junk

Anyone who can see the door's code can write to its nostr key. A message is
ignored unless it reads as an answer to a request the door still accepts: that
request's id and one to ten well-formed proofs of that request's text, no two
of the same NFT.

Someone can still shape a message as an answer without holding an NFT. So an
answer that comes over the network changes nothing the guest can see, and
spends no request, until the mint has borne one of its proofs out. The door
looks at three such answers at a time and two dozen in a minute, and at each
relayed copy of an event once. A door that is being pestered may miss a guest's
answer over the network; it keeps its whole allowance at the site for a phone
held to it, which nobody can do from across the room.

An answer handed to the door is from somebody standing at it. The door says at
once that it is checking, and if the mint disowns the proof says so in a line.

## Groups, and who came in

An answer may show up to ten NFTs, for a group that arrives together when one
of them holds every ticket. The mint is asked about all of them at once, each
is decided alone, and the result shows every one with its own answer: three
may be admitted while a fourth was already in. A group stays on screen until
the doorkeeper sends it away, to be counted through.

A holder who chooses to be named says which collection they are. Over nostr
their phone signs the message with that collection's key, and the door looks
for a name only then. It reads that collection's page and greets them by its
name only when the page bears them out: a card that has not been sent on,
whose public showing has exactly the context a collection writes for that key,
asset and keyset, is signed by that key, and carries the same nullifier as a
proof this answer got in with. A name greets a guest; it is not a check of who
they are, and the door decides the same with or without one. Looking it up is
given two and a half seconds and no more.

## Single use is the door's own record

A proof shows who owned a ticket when the mint was asked. It cannot show that
the ticket has not been used: a holder who is inside can send the NFT to a
friend, who then answers honestly. Single use comes only from the door's record
of admitted tickets. It is keyed by asset hash, which stays the same when a
ticket changes hands, and it is written before the door says "Admitted".

**The record is this phone's.** Two phones at one event do not share it, so
each would admit a ticket once. Today one event means one door phone.
**Reset admissions**, under **More** on the collection's page, empties it.

A collection is known by the address of its page, and the same page has one
address: `https://site:443/p/…` is `https://site/p/…`, so writing it another
way does not start a second record.

## The tickets are the collection's page

A collection's page lists every NFT the collection has held, including those it
has sent on, and a sent NFT stays listed for good. That list is the door's list
of tickets. So a ticket sold on is still a ticket, whoever holds it now, and
nothing has to be settled before the tickets are sold. The page also lists
whatever else the collection has held, which is why an event has a collection
of its own.

The phone keeps the list for each collection it has been given: it is what the
collection's page in the app shows, and it means a check does not download the
site's page again. It
is read when the collection is added, when the list is pulled down, and when
someone shows an NFT that is not in it, which is how one minted later gets in.

The collection's page in the app ([app.md](app.md)) is in the order of the
NFTs' numbers, with a search, a tab for each
ticket type and counts that are also filters: in, not in, and for a collection
made on this phone, listed and sold.

## Showing the NFT

The decision needs nothing from an NFT's metadata, and the door admits any NFT
of the collection, with or without it. What is admitted is recorded first, and
nothing that goes wrong afterwards can undo that.

A check that has begun finishes whatever becomes of the screen that started it,
and its decision waits until a door screen is in front to show it. If the app
itself is killed in that moment, the admission stands unseen; the holder's next
showing says "Already in", with the door and the time.

A wait says what is being waited for, in dots that swell and shrink. The moment
a handed answer arrives the door shows the NFT it names, from what the phone
already holds, and that it is checking with the site. To hold it, the phone
fetches each NFT's asset once, when the collection is read: it checks that the
bytes hash to the asset, keeps a small picture, and reads what the NFT says
about itself. Nothing is downloaded at the moment of entry.

The answer is a band in one of three colours, because each means a different
thing to do next. Under it an admitted NFT's page takes its ground from the
picture's most common colour, and paper is thrown in its livelier ones.
A refusal stays plain and its picture grey.

Metadata is untrusted input. The app reads it in `entry/Metadata.kt`, by the
rules of NUT-XX, and that reader answers to the same vectors as the Python and
browser readers: `app/src/test/resources/nft_metadata_vectors.json` is a copy
of `cashu-nft`'s `tests/nft_metadata_vectors.json`, to be copied again
whenever the rules change. The app shows the name and the facts as text, reads
whether the NFT says it is a ticket, and draws nothing from a document's
`style`.

## Where things live

- `entry/` is the protocol and the decision, with no screens in it. Its tests
  run against vectors made by the reference implementation, in
  `app/src/test/resources`; `app/src/test/make-vectors.py` makes them again
  when the protocol changes.
- `tap/` is the door as an NFC tag and the guest's reading of it.
- `studio/` and `app/src/main/assets/studio` make collections and sell from
  them: [making.md](making.md), [selling.md](selling.md).
- `guest/` is what an account holds and its wallet: [guest.md](guest.md).
- `nostr/` is the transport: gift-wrapped messages over relays.
- The screens sit at the package root and hold no rules of their own:
  [app.md](app.md) is the map of them. `DoorActivity` is the door, and
  `TicketsActivity` the collection's page it is opened from.
- The look is the site's, taken from its `style.css`: two palettes, a border
  with a hard offset shadow, pills. The typefaces are the site's own files as
  fixed-weight TTFs in `res/font`, under the SIL Open Font License;
  [fonts.md](fonts.md) says where each came from. Waiting is dots (`Dots`),
  success is a tick drawn in dots (`Tick`), and a list that scrolls under a
  heading dissolves into it through dots (`Halftone`).

## Trying it without the live site

Debug builds differ from release builds in two ways, both so that a site and a
relay on the development machine can stand in for the real ones: they allow
cleartext `http://` and `ws://`, and Settings has fields for the relays to use
instead of the public ones and for the site to search and mint on.
**Delete everything** leaves a debug build pointed at the site and relays it
was testing on.

Only relays that are `ws://` or `wss://` addresses are used, and a door is never
left without one: with none that qualify it falls back to the public relays.

On an emulator, `adb reverse tcp:<port> tcp:<port>` makes `localhost` mean the
development machine on both sides. Debug builds log each request they show, so
a test can answer one without a camera. An emulator has no NFC: the tap is
tested in `TapTest`, and on two phones.

## Not done yet

- **Nobody has held one phone to another with this build.** The two sides of
  the tap are tested against each other in code, and the answer a phone makes
  with no network has been read off a screen and admitted by the reference
  door. The radio in between has not been tried.
- Doors do not share their record of admitted NFTs.
- The door does not verify a proof itself. It asks the mint.
- The door does not show whether its relays are connected.
- The site has no small pictures, so the phone downloads each NFT's whole asset
  once, a few a second, when a collection is read. A site allows one address
  240 requests a minute, so add a large collection before the doors open: a
  door that shares the venue's network with holders shares that allowance.
- The door does not authenticate to relays (NIP-42). A relay that serves gift
  wraps only to an authenticated listener never delivers an answer, so the
  built-in relays are ones that do not ask.
