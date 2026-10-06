# The map of the app

Where everything is, and what belongs to whom. The detail of each part is in
its own page: [guest.md](guest.md) for what an account does with what it holds
and for its wallet, [making.md](making.md) for making a collection,
[selling.md](selling.md) for prices and selling, [door.md](door.md) for the
door, and [security.md](security.md) for what all of it rests on.

## Accounts

An account is somebody on this phone: a name, and a picture drawn from 64
random hex digits made with it. An account holds NFTs, has a wallet, makes
collections and keeps doors. There are no sides to choose between: keeping a
door is something done with a collection, not something an account is.

Kept per account:

- what it holds: its NFTs, its wallet, the mint in view and the mint it is paid
  at;
- its list of collections: those it made and those it only added to keep a door
  for. Which is which is said in the list. A collection is its maker's: another
  account on the same phone that adds it keeps its door and nothing more.

Kept per phone, whoever's account is in use:

- the door settings: the door's name, the sound, and whether the door comes
  back by itself;
- where the app was left: the tab Home was showing, and the door that was open;
- each collection's own records, under the collection's name and not the
  account's: its list of NFTs, who has come in, what it was made from, its
  prices. They go when the last account that keeps the collection lets it go;
- the pictures the phone has fetched, and the keys, which the site's own pages
  keep in the app's web storage.

### First run

The app asks one thing before it can be used: **What should we call you?** The
name is trimmed to forty characters; **Continue** makes the account and opens
Home. No key is shown, no mint chosen. A link or a door's tap that opened the
app before there was an account is kept, and done once there is one.

**I have a backup key** on the same screen brings back an account from the key
copied out of its Settings: its NFTs and its wallet come back with it. It is
called what was typed, or else what the site calls it. A key is kept only once
the site has said it knows it; a key the site does not know, or one the phone
already holds, is refused and nothing changes. Collections an account made have
keys of their own and do not come back this way. A phone that already held
things before it had accounts is not offered this until its first account is
named: what it holds is that account's.

### Switching and adding

The chip at the top of Home shows the account in use. Touching it opens
**Accounts**: every account with its picture, how many NFTs it holds and how
many collections it has. Touching another switches to it: an open door is
closed, and Home starts again on the NFTs tab. **New account** asks for a name
and opens Home on an account that holds nothing.

### What the site knows

Nothing, at first. An account's profile on the site is a collection of its own,
and it is made the first time the account needs one: to hold an NFT or sats,
or to ask a mint what paying an invoice would cost. Until
then the account exists only on the phone. The site is given the account's
name when the profile is made, and a changed name the next time it is asked
anything.

## An install that updates

Nothing is moved. An install from before accounts has no account, so it opens
on the same question as a new one. If the phone kept anything, there are two
differences: the name field holds the name the phone's guest had given, and a
line says **Everything this phone already holds, its NFTs, its collections and
its sats, stays with this account.** The account made there is the first, and
the first account keeps its things under the names the app used before it had
accounts. There is no step that moves or copies anything. So the NFTs held,
the wallet and every collection on the phone are that account's without
anything being copied. It opens on the NFTs tab with no door open.

A still older build kept each collection's records under names two collections
could share. Those are moved once, when the list of collections is first read,
to names they cannot.

## Home

Home is what the launcher opens. At the top are the account chip and Settings;
under them three tabs. The phone remembers the tab, so the app opens where it
was left. What can be done with the tab in view is at the foot.

| Tab | Shows | At the foot |
|---|---|---|
| **NFTs** | What the account holds, tickets first in the order of their numbers, then the rest. One that is for sale says so, with its price. Touching one opens it. | **Find NFTs**, **Scan** |
| **Collections** | **Made by you**, then **Doors you keep**. Each row has its name, its numbers, a few pictures, and **On sale** while it is selling. Touching one opens the collection's page. | **Add a door**, **Create** |
| **Wallet** | What can be spent at the mint in view, each mint with what is there, **Earned by your collections** with **Move to wallet**, and the last twelve movements. | **Send**, **Receive** |

The wallet has the mints the site names, each with its own balance; sums at
different mints are never added, because one of them is test money. What a
collection has been paid stays with the collection until **Move to wallet**
brings it into the account's wallet. [guest.md](guest.md) has the rest.

While Home is in front, what the account has up for sale goes on selling.

### What comes in from outside

- A link to `https://nonfungible.cash/claim/…` or `/market/…` opens Home.
- A door the phone is held to opens Home, if the app was closed.
- **Scan** reads a door's code, a claim or sale link, a collection's address,
  an ecash token, which is taken in, and a Lightning invoice, which is asked
  about and then paid. For anything else a sheet says the code is none of
  these.

All three are read the same way, and Home turns to the NFTs tab for them. With
Home up, a phone held to a door answers it without asking when the account
holds exactly one ticket for it; otherwise its holder is asked first. See
[guest.md](guest.md) and [door.md](door.md).

A link to a collection, `/p/…`, and text shared to the app open the search for
a collection instead.

## A collection's page

Touching a collection on the Collections tab opens its page: every NFT of it in
the order of their numbers, with search, a tab for each type when there is more
than one, and counts that narrow the list when touched: in and not in, and for
a collection the account made, listed and sold. A status line under the name
says how many NFTs there are and, for one the account made, how many are sold
and how its prices stand; for one only added, how many have come in. Pulling
down reads the collection from the site again.

At the foot:

| The collection | At the foot | Behind **More**, the dots beside them |
|---|---|---|
| Tickets, made by the account | **Sell or send**, **Open door** | **Mint more NFTs**, **Prices**, **Copy backup key**, **Reset admissions**, **Delete collection** |
| Not tickets, made by the account | **Prices**, **Sell or send** | **Mint more NFTs**, **Copy backup key**, **Reset admissions**, **Delete collection** |
| Only added | **Open door** | **Reset admissions**, **Remove from this phone** |

**Reset admissions** is there only once somebody has come in. Whether a
collection is tickets is decided by the template it was made from; one that was
only added is taken to be tickets, since it is kept for its door.
[making.md](making.md) and [selling.md](selling.md) explain minting, prices and
selling.

## The door

The door is opened from a collection's page with **Open door**, and closed by
leaving it. The phone remembers which collection's door is open: put away with
a door open, the app is that door again when it is next opened, as long as the
account in use still has the collection. A link or a tap that opens the app is
done first instead. Switching account, adding one and deleting close the door.

The door's name, **Play a sound for each result** and **Return to the door
automatically** are in Settings and hold for every door on the phone.
[door.md](door.md) says how the door decides.

## Settings

Settings is reached from the top of Home. It has:

- the account's name, which **Save** changes;
- **Copy backup key**, the key to the account's NFTs and wallet, once the
  account has a profile on the site. A sheet says what the key is before it is
  copied, and the clipboard is told not to show it;
- **Doors on this phone**: the three door settings above;
- in a debug build, the site and relays to use instead of the public ones;
- last and apart, in red, **Delete _name_…**, when there is more than one
  account, and **Delete everything…**.

Nothing about any one collection is in Settings. That is on the collection's
page.

## Deleting

### One collection

**Delete collection** under **More** opens a sheet that says what will happen
and has one button, **Hold to delete**.

For a collection the account made:

1. If the collection still holds sats, at any mint, or ecash taken out of it
   has not yet reached the wallet, nothing is deleted and the sheet says so.
   **Move to wallet** on the Wallet tab empties it.
2. Everything it has for sale comes off sale, and it stops selling for good.
3. Every NFT it still holds is burned at the mint and dropped from the site
   with its picture, a few at a time, with a count as it goes. What is burned
   is what the site lists at that moment, not what the phone remembers.
4. NFTs it sent or sold are not touched. They stay with their holders.
5. When the site shows nothing left, its name there becomes `[deleted]`,
   because the site has no way to remove a collection. The phone forgets its
   key and its records.

If some could not be burned, the collection stays on the phone with its key,
the sheet says how many and why, and the button can be held again. If nothing
could be done, the sheet says why and the button comes back.

For a collection that was only added the button is **Hold to remove**. Its list
of NFTs and its record of who came in are deleted from the phone. Nothing
changes on the site.

### One account, or everything

**Delete _name_…** and **Delete everything…** open the same page, for one
account or for all of them. Each account is a card, with a line for everything
of it that will go:

| Line | What becomes of it |
|---|---|
| The NFTs it holds | Burned. Its name on the site becomes `[deleted]` and the phone forgets its key. An account the site never heard of is only forgotten. |
| Sats at a mint, or what a collection was paid | Lost with the key. Test sats are said to be worth nothing. Real sats are in red, with **Move out first**, which opens that account's wallet at that mint. |
| Each collection it made | As for one collection: unsold NFTs burned, sold or sent ones stay with their holders, name becomes `[deleted]`, key forgotten. |
| Each door it keeps | Removed from this phone only. |

Ecash that was taken out of a wallet and is still kept in the app is a line of
its own, in red: deleting would lose the only copy.

The money is counted each time the page comes to the front, and the button
cannot be held until it is. While real sats or a kept ecash copy are at stake
it also cannot be held until they are moved out or the switch **Delete the _N_
sats too** is on. An account or a collection whose site could not be asked says
so in red, and is not deleted at all: it stays, with its key. What the switch
agrees to is the sum the page showed. Each wallet is counted again just before
its key would go, and one that has come to hold more is left as it is.

An account or a collection with a payment still settling is not counted, and
so stays: what the payment will give back is in no balance yet.

When everything is to go, the page also looks for keys the phone still holds
that no account lists, such as a collection that was made here and later only
taken off a list. They are shown on a card of their own, **Kept from before**,
and go with the rest; they are put on nobody's list. The app's storage is wiped
at the end only when the site says nothing is left under any key the phone
still holds. If the site cannot be asked, the keys are kept and the page says
so.

There is one button, **Hold to delete** or **Hold to delete everything**. Once
it has been held, the page cannot be left until it is done. Accounts go one
after another, and within each: its collections, then its doors, then the
account itself. Each line says how far it has got.

What fails stays. A collection with NFTs that could not be burned stays with
its key, and so does its account. An account whose own NFTs could not all be
burned keeps its key. The page then says **Everything else is gone. What is
marked in red is still here, with its key, and can be tried again**, with
**Try again** and **Leave them**.

When one account is gone, the app goes on with the others. When the last is
gone, leaving the page clears everything else the app kept: its settings, the
site's pages' storage and cookies, where the keys were, the fetched pictures and the
templates. The app then starts again as it does the first time. A debug build
stays pointed at its site and relays.

### What cannot be undone, and what the site keeps

Burning cannot be undone, and neither can forgetting a key. The site keeps what
it cannot remove: each name, as `[deleted]`, and the NFTs that were sold or
sent, which are their holders' now.

## How it looks and behaves

- **What can be done is at the foot.** Home and a collection's page each have
  two buttons at the foot for what is done most. What is done seldom is under
  **More** or in Settings.
- **One sheet.** Every sheet is made in one place and opens whole, never half:
  a title, a paragraph, and its buttons.
- **Confirmations are sheets.** A question before something is done is a sheet
  with what it is, what it means, **Cancel** and the action.
- **Holding is for what cannot be undone, and for nothing else.** The red
  button is held for about a second and a half while a fill runs across it;
  let go sooner and it runs back. Its label is drawn twice and cut at the
  fill's edge, red ahead of the fill and white on it, so it can be read
  throughout. It is used to delete a collection, an account or everything, to
  remove a collection, to reset admissions, and to delete the kept copy of
  ecash that was taken out.
- **A disabled button is quiet.** It keeps an outline, takes the page's colour
  or a pale wash of its own, and mutes its label. A hold button that cannot be
  held yet has a faint outline and a quiet label.
- **Red means deleting.** The rows that delete are labelled in red, and so is
  whatever on the delete page would be lost or has failed.
- **A note above the foot** says what belongs to no sheet, and goes with the
  next thing done.
- A screen that shows one NFT takes its colours from the NFT's picture; see
  [door.md](door.md).
