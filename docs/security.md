# What the ticketing rests on

An audit of the cryptography and the assumptions around it, from October 2026.
Two reviewers read the mint's credential code, the draft NUT, the reference
door and this app without seeing each other's work, one of them a different
model family; three more hunted for defects in the app. This page is what they
found, what was changed because of it, and what is still open.

The app has since been rearranged around accounts, each with a wallet; what
that is and where things are is in [app.md](app.md). What it changes here was
read from the code afterwards by one reader, not by the reviewers above, and
is in [its own section](#accounts-the-wallet-and-deleting).

## The short answers

**Is it sound?** Yes, for what it claims. Nobody found a way to forge a proof,
to use one twice, to use one at another door, or to turn one into a transfer.
What a proof does not give, and never claimed to, is single use: that is the
door's own record, and today that record is one phone's.

**Can a guest be offline?** Yes, and now they can. A proof was always made on
the guest's phone from what it holds; only its delivery used the network. A
guest now holds their phone to the door's, or shows the door a code, and needs
no network. The door needs one.

**Could this be done with ordinary ecash proofs?** For plain general admission,
yes, and more simply. The NFT earns its place when a ticket has to be a
particular thing. The reasoning is [below](#nfts-or-ordinary-proofs).

## What a proof proves

An admitted answer proves that whoever made it knew the owner secret of a
credential the mint had not seen spent, for an NFT the collection lists, and
made the proof for this one request of this door.

It does not prove that the person at the door made it: a proof can be relayed
while the request is alive. It does not prove the ticket is unused: only the
door's record says that. And "not spent" is as of when the mint was asked, not
when the door opened.

## Checked and found sound

- **A showing cannot be spent.** Every presentation is bound to one purpose
  inside its Fiat-Shamir challenge. A transfer binds the new owner's point, a
  burn its tag, a showing `Cashu_PS_Showing_v1` and its context. The mint
  refuses a showing at every spend endpoint, so answering a door, even a fake
  one, cannot move or destroy a ticket.
- **A proof fits one request.** Its context is the whole request text: the
  random id, the collection and the door's key. Another door, another
  collection, another id or a later time all fail before the mint is asked.
- **A previous owner is refused.** A transfer spends the old nullifier and there
  is one live credential for each asset.
- **The mint rejects malformed points**: off-subgroup, non-canonical, infinity,
  out-of-range scalars.
- **The door fails closed** on every malformed, missing or slow answer, and
  writes an admission before it shows anything.
- **The nostr chain is verified**: gift-wrap signature, seal signature, and the
  rumor's key equal to the seal's, before a sender is believed.
- **An offline proof is the same proof.** The bytes the mint is asked about are
  identical whether the answer came over nostr, by a tap or as a code.

## Findings

| | Finding | Now |
|---|---|---|
| High | Single use holds for one phone. Two door phones for one event each admit a ticket once. | **Open.** Stated on this page and in [door.md](door.md). Needs a shared record: see the open items. |
| Medium | The door verifies nothing itself. Valid, unspent, active and listed are all one site's word, and a pasted look-alike address would have been believed. | Partly fixed: a door is only a door for collections on the app's own site. Verifying the pairing on the phone is open. |
| Medium | Anybody who could see the door's code could keep the door shut: junk shaped like an answer spent the code, put up a checking screen and made the door start again. | Fixed. An answer from the network spends nothing and shows nothing until the mint bears it out, and only so many are looked at in a minute. |
| Medium | A ticket that is for sale could still come in, and then be sold as an unused one. | Reduced: the app takes a ticket off sale when its holder shows it at a door. A buyer still cannot see whether a ticket was used. |
| Medium | "Owns it now" overstated a check and an admission that are separate steps. | Reworded in the NUT. The door's record makes the gap harmless on one phone. |
| Medium | The reference door could lose its whole record if it stopped while writing it. | Fixed: the record is replaced whole. |
| Low | A guest could be greeted by name on the strength of an NFT the door then refused, and the claim's context was checked by its start only. | Fixed: exact context, and only for an NFT that got in. |
| Low | The door's twenty seconds of patience were measured on a clock that can be set. | Fixed. |
| Low | The proof's challenge does not hash the asset hash, `v'` or the keyset. Not exploitable against one honest key: the pairing equation fixes them. | Open, for a next version of the scheme. It needs the mint. |
| Low | The mint verifies against its current key only, so rotating that key would refuse every ticket already sold. | Open. It needs the mint. |
| Note | A transfer signs the new credential after its transaction has closed, so a crash there could spend the old one and deliver nothing. | Open. It needs the mint, and a human decision. |
| Note | The NUT's example carried an amount the text forbids, said "signs" for a showing, and claimed more against relays than it has. | Fixed in the NUT. |
| Medium | Deleting could throw money away that the page did not count: ecash an account took out and had not said it had, and the wallet of an account the site could not be asked about. | Fixed. Whatever could not be counted is not deleted, ecash still kept is a line of its own that holds the button back, and `Guest.destroy` and `Sales.destroy` count again and refuse if more is there than was agreed to. |
| Medium | Every account's key, and every collection's, is kept in one place in the app, apart only by name. Changing account asks nothing, and copying a key asks only for **Copy** on a sheet: nothing proves who holds the phone. | **Open.** One phone is one trust: accounts keep things apart, they do not keep them from each other. |
| Low | Another app can hand the app text as if it had been scanned, and a link on the web can hand it a gift or a sale. Ecash and a given NFT are taken in with no button. | Partly fixed. Only an opened link or a tapped door is read (`HomeActivity.given`), and an invoice that comes that way is not asked about until a button is pressed. Nothing leaves the phone without a button; what comes in is still not asked about. |
| Low | Ecash that has left a wallet is kept as plain text in the app's preferences until it is put somewhere. | Open. Android's backup is off; nothing else protects it. |
| Note | Deleting removes nothing from the site but what is for sale and the NFTs still held. The page, its history and the wallet's encrypted backup stay. | Stated here. The site has no way to remove them. |

The app's own defects that the same review found are fixed and are not listed
here. The largest were a guest able to pay twice when a purchase stalled, a
picture of an odd shape that crashed the door, a door that could be left deaf
by a quick tap, and "No link could be made": an NFT that was for sale could not
be sent, and work the app had stopped waiting for carried on underneath it.

## Accounts, the wallet and deleting

What the code does, with the file and function that does it.

### Keys of several accounts

An account's key, and the key of each collection it made, are made and kept by
the site's own wallet code in a page the app loads on the site's address
(`studio/Press.kt` `Minter`, `mint.js` `create`). The page keeps every key in
one entry of that address's local storage, `cashu-nft-keys-v1`: a list of
public key and secret, in plain text. Each wallet's own state is kept beside
it under names that contain its public key. All accounts on the phone share
that one storage.

What separates one account from another is naming. The app keeps which public
key is whose in its preferences, in a file named for the account
(`Accounts.kt` `Account.store`, `guest/Guest.kt` `me`), and names that key in
every call it makes to the page. The page acts for whichever key it is told,
and `held` and `key` hand out any of them. Changing account asks for nothing
(`Accounts.kt` `use`). One key cannot be given to two accounts on a phone
(`Guest.adopt`, a check the app makes), and that is the only check between
them.

So an account is not a boundary. Whoever holds the unlocked phone has every
account on it, and any script that ran on the site's address inside the app
could read every key at once. The app serves `mint.html` and its script from
its own files; whatever else that page asks for comes from the site.

**Copy backup key** in Settings puts the secret key of the account in use on
the clipboard: 64 letters and digits, which open that account's NFTs and its
wallet from any browser (`SettingsActivity.copyKey`, `Guest.key`). On a
collection's page it puts that collection's key there (`TicketsActivity`),
which can send what the collection still holds, mint more, and spend what it
has been paid. Each asks first with a sheet, **Copy backup key?**, and copies
on **Copy**. Both are marked sensitive, so that Android does not show them when
they are copied. Nothing clears the clipboard afterwards. An account's
key does not cover the collections it made: each has its own.

### What comes in from outside

Three things in the app can be started by another app: Home, the screen that
looks a collection up (`CollectionActivity`, by a shared text or a `/p/` link)
and the door's NFC service, which only the system's NFC may bind. Home takes
the data of whatever started it as if it were scanned text, whatever the
action was (`HomeActivity.given`, then `GuestSheets.kt` `took`). A link on the
web reaches it only as a `/claim/` or `/market/` address of nonfungible.cash;
another app on the phone can hand it any text at all. On a phone with no
account yet the text is kept until a name is given (`Config.pending`).
Everything below is done for the account in use, which the sender cannot
choose.

| What arrives | With nobody agreeing | Waits for a button |
|---|---|---|
| An ecash token | Taken into the wallet at once (`takeEcash`, `Guest.receive`). An account the site has not heard of gets a page there first. | Nothing. |
| A Lightning invoice | The mint in view is asked what paying it would cost (`payInvoice`, `Guest.quote`). An account the site has not heard of gets a page there first. | Paying. The button names the amount; the sheet shows the fee and the mint. |
| A `/claim/` link: an NFT that was sent | Taken at once (`takeGift`, `Guest.claim`): the NFT is the account's, and shows on its page on the site. An account the site has not heard of gets a page there first. | Nothing. |
| A `/market/` link | The listing is read from the site and shown (`buy`, `Guest.listing`). | Paying. A link can say at which mint; `Guest.buy` refuses one that is neither the account's own nor named by the site. |
| A door's request, as text or from a tap that opened the app | The phone works out which of its NFTs the door would take, and may ask the site (`atDoor`, `Guest.asked`). | Showing. Nothing goes to the door before it. |
| A collection's address | It is opened to look through. | Buying anything. |

A given NFT and ecash cost the account nothing. They do change what it holds,
and what its page on the site lists, at the word of anybody who can get a link
opened.

One thing answers with no button and is not an intent. While Home is on screen
and the phone is held to something that asks as a door does, the app shows the
account's ticket for that door at once, if it holds exactly one and knows it
is for that door (`HomeActivity.answer`). With several, or none it is sure of,
a sheet asks first. What is given is a showing for that one request, which
cannot be spent.

### The wallet takes ecash from named mints only

`mint.js` `receive` reads the token's mint and unit before anything is done
with it, and refuses a unit that is not sats and a mint that is not among
those the site names at that moment: its test mints and its listed ones
(`mints`, from `/api/market/config`). The check is made in the page. The app
passes the token through and does not look at its mint (`Guest.receive`).

It keeps a wallet from holding money at a mint somebody else chose. It does
not make the named mints trustworthy, and the list is the site's to change.
It is the only way ecash comes in: scanned, pasted, opened from another app,
or moved from a collection.

### Ecash that has left a wallet

**As ecash** takes sats out of the wallet as a token (`Guest.takeOut`,
`GuestSheets.kt` `sendSats`). From then the token is the money. It is written
to the account's preferences, whole and in plain text, before the sheet shows
it, and Send shows it again until **I have it** is pressed and a hold confirms
it; then it is removed (`showEcash`). **Move to wallet** does the same with
what a collection has been paid (`studio/Sales.kt` `cashOut`, `App.earned`):
the token is kept in that collection's prices until the wallet of the account
in use has taken it. A move that is cut short is finished the next time it is
asked for.

The manifest turns Android's backup off (`allowBackup="false"`), so these
tokens, and the keys, are not in a cloud or adb backup. It names no data
extraction rules, so what a transfer from phone to phone carries is left to
the system. Anything that can read the app's own files can spend a kept token.

### Deleting

A key is forgotten only when the site shows nothing left under it.

- A collection (`Sales.destroy`): refused while it holds sats at any mint or a
  token not yet moved, unless **Delete the … sats too** was switched on. Then
  everything comes off sale, the site's list is read, each NFT the collection
  still holds is burned, and the list is read again. A list that cannot be
  read stops it. Only with none left is the name set to "[deleted]" and the
  key forgotten.
- An account (`Guest.destroy`): the same, for the NFTs it holds, after every
  collection it made has gone (`DeleteActivity.begin`).
- Anything that fails keeps its key and its line on the page, to be tried
  again. An account with a collection that stayed is kept too.
- The hold button is quiet until the counting has finished
  (`DeleteActivity.ready`). Real sats keep it quiet until they are moved out
  or the switch says to delete them. Test sats never do. A collection whose
  money could not be counted is not deleted with money in it, whatever the
  switch says.
- When no account is left the app clears its preferences, all WebView
  storage and cookies, the pictures it fetched and its templates, and starts
  again (`DeleteActivity.wipe`).

Two things are not counted. Ecash an account took out and has not said it has
is deleted with the account's preferences and is on no line of the page
(`DeleteActivity.count`, `Guest.destroy`). And an account whose wallet could
not be asked about is noted as unreached, but only collections are held back
for that: `Guest.destroy` checks no money and is run all the same
(`DeleteActivity.begin`).

### What deleting leaves on the site

Deleting takes what is for sale off sale, burns the NFTs still held, and
renames the page. `mint.js` `forget` then removes the key and that wallet's
storage from the phone, and asks the site for nothing. The page stays under
its key with the name "[deleted]"; NFTs that were sold or sent stay with their
holders and name the collection they came from; past sales stay public. The
site's wallet code keeps an encrypted copy of each wallet on the server, and
nothing here asks for it to be removed. With the key forgotten it can be
opened only by somebody who copied the backup key first.

The renaming is tried once. If it fails the key is forgotten all the same,
and the page keeps its old name with nobody able to change it.

## NFTs or ordinary proofs

A Cashu developer asked whether this could be done with standard proofs: one
keyset for the event, every proof worth one.

**It can, where a ticket is just "one admission".** The guest hands the proof
to the door and the door spends it. The mint itself then enforces single use,
across any number of doors, with cryptography that has been in use for years
and with real privacy: the mint cannot link the ticket it issued to the one
redeemed. For large, anonymous general admission that is the better design.

**"Prove you own it without handing it over" is not what the NFT adds.** A
proof locked to the holder's key (NUT-11) with its DLEQ (NUT-12) can be shown,
and a signature over the door's challenge proves the holder has the key,
without giving the door anything it can spend.

**What that cannot do is stay the same ticket.** A door that does not spend
the proof can only remember it, and ecash is built so that one swap gives the
ticket a new, unlinkable secret. A holder walks in, swaps, hands it back out,
and the door has never seen it before. So with ordinary proofs a ticket has to
be spent at the door: it is gone afterwards, a fake door that is handed it
keeps it, and coming back in needs a second token.

**The NFT is a name that survives changing hands.** The asset hash is the same
for every owner, and the mint signs it. From that one fact come the things a
fungible proof cannot have: a ticket that is number 11 of 26 with a picture, a
door record that outlasts a resale, a ticket still in the wallet after the
event, re-entry with the same ticket, and an event anybody can create without
the mint's operator making a keyset.

**The cost is the same fact.** That name is public and permanent. A
collection's page shows each NFT beside its owner's key, sales are public, and
a door that has seen a ticket knows it again. The NFT also brings pairings and
a signature scheme that have had far less scrutiny than Cashu's own, and no
other wallet speaks it.

So: plain proofs for anonymous admission at scale; the NFT where the ticket's
identity is the product. This app is for the second.

## A guest with no network

Three ways were weighed.

- **Held to the door (NFC).** The door says its request, the phone answers in
  the same tap. Sound: it is the same protocol over another channel. It is the
  hardest to relay, because that needs somebody beside the guest and a link in
  both directions while the phones touch. It is not impossible: a phone cannot
  measure distance. **Built, as the main way.**
- **A code shown back.** The guest scans the door's code and shows the answer
  as a code. Sound, and it works on any phone with a camera. Anybody who
  photographs the answer could race it to that door for that one request.
  **Built, as the fallback.**
- **A code that changes with the time**, with no question from the door. This
  is how Ticketmaster's rotating barcode is reported to work: secrets fetched
  beforehand, codes made on the phone every fifteen seconds or so, no
  connection needed at the gate. Transit cards answer the gate's own question
  with a key on the card. A time code is bound to no door, so whoever sees the
  screen can use it at any door until it changes. **Not built**: the other two
  are stronger and no harder for a guest.

For a phone to know with nobody to ask which ticket a door wants, an NFT now
says in its own metadata that it is a ticket and for which collection. That is
the picture's word and decides nothing at the door.

## Still open, most important first

1. **A record doors share.** Without it one event is one door phone. Door
   phones could tell each other of each admission over the relays they already
   use; that would be soon, not atomic. A small service the doors all ask
   would be atomic and is a backend. The mint's own lock on a credential might
   be made to serve, so that the mint enforces single use and a buyer can see
   a used ticket; nobody has tried it.
2. **One storage for every key.** `mint.js` `keys` holds all of them in one
   plain entry and `key` gives any of them to whoever asks the page;
   `Accounts.use` asks for nothing, and copying a key, in `SettingsActivity`
   or on the collection's page, asks only for **Copy** on a sheet, with no
   lock of the phone's own before it. Either accounts are said plainly to be
   a convenience on one person's phone, or keys want a lock of the phone's own and a storage
   each.
3. **Two phones.** Nobody has yet held one phone to another with this build.
4. **Verify proofs on the door's phone**, against a key fetched once. It takes
   the arithmetic out of what the site is trusted for, and makes junk free.
5. **What comes in with no button.** `HomeActivity.given` reads an opened link
   or a tapped door, and `takeEcash` and `takeGift` in `GuestSheets.kt` act on
   it at once, for whichever account is in use, making it a page on the site
   if it had none. A sheet that asks first would cost one press. An invoice
   that comes this way is asked about only after a press, because asking sets
   sats aside. `HomeActivity.answer` shows a lone ticket to anything that asks
   as a door does while Home is up, which is what a relay needs.

6. **Production.** Minting from the app has not been tried against the live
   site and its browser check.
7. **The mint**: hash the whole statement into the challenge, keep old keysets
   verifiable, and write a transfer's new credential inside its transaction.
   These are changes to cryptographic code and need a person's review.
8. **Ecash kept in the clear.** `Guest.takenOut` and `Prices.takenOut` are
   plain preferences, and the manifest has no data extraction rules beside
   `allowBackup="false"`. In `App.earned` a token the wallet took in but whose
   answer was lost is kept and offered again; nothing was found that clears
   it, and while it is kept `Sales.destroy` refuses the collection. This last
   is read from the code and has not been tried.
9. **The mint list is checked in the page.** `mint.js` `receive` is the only
    place a token's mint is looked at; `Guest.receive` passes it on. The app
    could check it too, as `Guest.buy` does for a sale.
10. **What the site keeps.** A deleted page, its history and the wallet's
    encrypted backup stay. Removing them needs the site. (A rename that fails
    in `Sales.destroy` or `Guest.destroy` keeps the key, so that it can be
    told again.)
11. **What deleting agrees to.** The switch on the delete page agrees to one
    sum for each thing, not to an amount at each mint, and ecash that was taken
    out is a line without its amount. `Guest.destroy` and `Sales.destroy` count
    again before a key goes and refuse if more is there, but sats swapped for
    the same number of other sats between the count and the hold would pass.
12. **A quote that outlives its screen.** Asking what an invoice costs sets
    sats aside (`mint.js` `quote`). They are let go of when the sheet closes and
    again when the wallet is next opened by a fresh page, but not in between if
    the app is killed while the sheet is up.
