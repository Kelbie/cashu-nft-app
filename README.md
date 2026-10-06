# nonfungible.cash for Android

One app for both sides of a door at an event whose tickets are image NFTs. It
asks only for a name the first time it opens, and there are no sides to choose
between.

**An account** holds its NFTs, shows a ticket at a door, and buys, sells or
sends them; it has a wallet; and it mints collections of NFT tickets on the
phone, prices them, sells or sends them, and keeps their doors. A phone can
have several accounts. [The map of the app](docs/app.md) says where everything
is, and [docs/guest.md](docs/guest.md) what an account does with what it holds.

At the door nothing changes hands. The guest's phone proves it holds a ticket
of the collection, and the door keeps a record of the ones it has let in. The
guest needs no network to do it: only the door does.

It began as [Numo](https://github.com/cashubtc/numo), the Cashu point of sale,
with the payment terminal taken out. What remains of Numo is its nostr
transport, its scanner, and the way a phone held to it reads a request.
[What the ticketing rests on](docs/security.md) is the audit of the design.

## At the door

1. On the Collections tab, **Add a door** finds a collection by its name or by
   a pasted link, and **Create** makes one on the phone. **Open door**, on the
   collection's page, makes the phone its door.
2. The door says **Hold your phone here**. A guest opens their ticket and holds
   their phone to the door's. A door with nothing to hold a phone to shows a
   code that changes every half minute, and the guest scans it.
3. The door says which NFT it is checking, and then answers in one of three
   colours: **Admitted**, **Already in** with when and where, or **Refused**
   with the reason.
4. After one guest is admitted the door comes back by itself. Anything else
   waits for **Next guest**.

One answer can show up to ten NFTs, for a group whose tickets one of them
holds: each is decided alone and shown with its own answer. A guest who
chooses to say who they are is greeted by name.

An admitted screen takes its colours from the NFT's picture, and **Details**
slides what the phone knows about it into view under the picture. The door admits any NFT of the
collection, with or without [metadata](docs/door.md#showing-the-nft).

A collection's page lists every NFT it has ever held, sold on or not, and those
are what the door admits: a ticket bought from someone else comes in like any
other, and nothing has to be decided before the tickets are sold. An event
wants a collection of its own, because whatever else the collection has held
would come in too.

The count under the door opens the list of them all, in the order of their
numbers, with a tab for each ticket type and counts of who is in. Opening one
marks it admitted or takes its admission back. For a collection minted on the
phone, **Sell or send** on the door asks which ticket type and takes the next
one: sold for its price or sent free, by a code the guest scans. The
collection's page mints more and sets prices, under **More**; the Collections
tab keeps those minted on the phone apart from those only added.

An NFT comes in once at this phone. **Reset admissions**, under **More** on the
collection's page, empties the record, and removing the collection does too;
either lets the same NFTs come in again. Two phones do not share a record yet,
so for now one event means one door phone.

[How the door decides](docs/door.md) explains what a request is, what the door
checks, what it trusts and what it does not do yet.

## Creating a collection

**Create**, on the Collections tab, asks for a name, a template and how many of
each ticket type. The phone draws every NFT with the template and mints it on
the site; when the last is minted, finishing opens the collection's page.
**Mint more NFTs**, under **More** on that page, adds to a collection the phone
minted, numbered on from the last: a single NFT can carry a guest's name and
photo.

**Prices**, in the same place, sets what each ticket type costs today and from
later days, in sats or in dollars or euros, and lists the collection for sale on the
site's market. While the app is open it changes prices on their day and takes
payments by itself; a sale sends the NFT to its buyer as the sats arrive. A
ticket a guest is selling can be bought back.
[Prices and selling](docs/selling.md) explains all of it.

The collection's key stays on the phone. Copy it from the collection's page and
keep it safe: it opens the collection on the site from any browser, and it is
the only copy. [Making collections](docs/making.md) explains templates, how to have a
new one written, where the key lives and what has not been tried against the
live site.

## Requirements

- Android 8.0 (API 26) or later
- A network connection for the door, which asks the collection's site about
  every ticket. A guest at the door needs none.
- NFC on both phones to enter by holding one to the other. Without it the door
  shows a code and the guest scans it.

## Build and install

```bash
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On a phone the app's id is `cash.nonfungible`. A build from before the id
changed is another app to the phone, with its own collections and keys: this
one does not install over it, and starts with nothing.

This is the debug build, for trying the door out. It can be pointed at a site
and a relay on the development machine;
[the door's notes](docs/door.md#trying-it-without-the-live-site) say how. A door
for an event should be a release build, `./gradlew assembleRelease`, signed with
your own key.
Contributors and coding agents follow [AGENTS.md](AGENTS.md).

## License

MIT. See [LICENSE](LICENSE).
