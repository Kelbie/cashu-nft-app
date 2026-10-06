# What an account holds, and its wallet

An account has NFTs and a wallet. The **NFTs** tab of Home is what it holds,
tickets first. Opening a ticket and holding the phone to a door gets its holder
in. **Scan** reads whatever code they are shown and does what it is for. The
**Wallet** tab is the sats it can spend, mint by mint. Where each of these is
in the app, and what an account is, is in [app.md](app.md).

It is not the website in a frame. The screens are the app's own, and the work
is done by the site's protocol: the site's own wallet and market code runs in
a page nobody sees (see [making.md](making.md)), and
[`guest/Guest.kt`](../app/src/main/java/cash/nonfungible/app/guest/Guest.kt)
is what the screens ask.

## An account's NFTs

The NFTs tab lists what the account holds as the phone last knew it. A ticket
is listed with the tickets and anything else after them; the two headings are
there only when the account holds both kinds. A row says one thing about an
NFT of the account's own: **For sale · N sats**, when it is. At the foot are
**Find NFTs** and **Scan**. With nothing held the tab says **No NFTs yet**.

## An NFT's page

Opening an NFT gives it a page coloured from its picture. What is known about it
is under the picture, not in a sheet over it: **Details**, or a touch on the
picture, slides the page down until the table sits under the picture's foot,
and pushing it back up far enough returns to the picture. Let go between the two
and the page goes to one of them.

What there is to do stays at the foot of the page, whichever is in view. For one
of the account's own, the phone can be held to a door while the page is up, and
under that are:

- **Scan door code**, for a door there is nothing to hold a phone to.
- **Sell**: a price, and it is listed on the site's market.
- **Send**: a code a friend scans, or a link sent to them. The sheet says what
  that means before any link is made, because whoever opens one first has
  the NFT.

For somebody else's, found by looking through a collection, there is one thing
to do when it is for sale: **Buy for N sats**, with who has it listed. One that
is not for sale can still be looked at.

## What is a ticket

An NFT says in its own metadata which collection it is of, by the collection's
key, and one that is a ticket carries the `ticket` extension beside that: the
door of its collection admits it (NUT-XX). The app lists those under
**Tickets**, in the order of their numbers, and anything else under **Other
NFTs**. It is also how a
phone knows which ticket a door wants with nobody to ask. An NFT that says
nothing can still be shown at a door: the door decides, not the picture.

## At a door, with or without a network

**Held to the door.** With a ticket open, or on the list with one ticket for
that door, the phone reads what the door asks and hands back its proof in the
same tap. Nothing is asked of the site, the mint or a relay: the proof is made
from what the phone holds. A guest with no network gets in as long as the door
has one. Somebody holding a group's tickets chooses, before the tap, to show
all of them or the one.

Held to a door with the app closed, the phone opens the app at that door, and
the guest is asked first.

**Scanned.** The guest scans the door's code, is told which door asks and for
what, and **Show ticket** sends the proof over the network. With no network
the sheet shows the proof as a code for the door to scan, and the phone will
also hand it over if held to the door.

Showing a ticket never gives it away. A ticket that was for sale comes off
sale when it is shown.

A door that lets somebody in greets them by their account's name.

## Buying

**Find NFTs** searches collections by name. A collection's page lists every
NFT it has, in the order of their numbers, and says what each one that is for
sale costs. Touching one opens its page, and **Buy for N sats** is there when
it is for sale. A link to a sale (`/market/…`) or a scan of its code comes to
the same sheet: the NFT, its price and who listed it, and **Pay N sats**.

Nothing is paid for what is no longer for sale: the site is asked first. A
sale's code may say which mint its seller is paid at. The app pays there only
if it is the mint the account is already paid at or one the site names;
otherwise it says so and nothing is paid. A sale that names no mint is paid at
the account's own.

If the wallet is short at that mint the sheet makes an invoice for the
difference, to pay from any Lightning wallet: **Open wallet** hands it to one
on the phone, or copies it when there is none. Test sats pay their own
invoice. The invoice is watched for three minutes. Then the price is locked
for the seller, and the NFT arrives when the seller hands it over: **It's
yours**. One that has not arrived while the sheet waits is looked for whenever
the account's NFTs are next read, and the sats come back if the seller never
sends it.

## Selling on

**Sell** on an NFT's page takes a price and lists it on the site's market,
where anyone can buy it, its maker included. The button then reads **For
sale · N sats**, and the same sheet has **Change price** and **Cancel
listing**. It is for sale only once the market lists it: one the market would
not take is not left looking as if it were.

An offer that meets the price is taken while Home is on the screen, and by
no other screen. Home looks after what is for sale when the market has news,
and every five minutes without any. When one sells Home says
**Sold. The sats are in your wallet.** They arrive at the mint the account is
paid at (see the wallet, below); the sheet warns when that is test money.

## Sending

**Send** makes a code and a link (`/claim/…`) after saying what they are:
whoever scans the code or opens the link first gets the NFT. Making the link
takes the NFT off sale. **Share link** sends it by any app, and the sheet says
**Sent. It's theirs now.** when it has been taken.

Whoever scans or opens one receives it at once, with nothing to confirm:
**An NFT for you**, then **It's yours**. The same happens when a collection's
maker hands one over in person from **Sell or send** on the collection's page
(see [selling.md](selling.md)): sent free, the guest scans and has it; sold,
the guest scans and pays, and the NFT goes to them as the sats arrive, both
or neither.

## The wallet

The Wallet tab is the account's sats, and each account has its own.

**The mints.** The wallet keeps money at the mints the site names, and the
app adds none of its own. The site names four: Testnut (test sats), Minibits,
Coinos and Macadamia. A site run for development also names its own test
mints. Each mint is a row with what is there to spend. A mint the site does
not name is listed, by its address, only if the wallet holds something there.

**The mint in view.** One mint is in view; touching a row puts that one in
view. The large amount is what can be spent there, with **N locked in a
sale** and **N on its way** under it when there is any. **Receive** and
**Send** at the foot act at the mint in view.

**No total.** Sums at different mints are never added. Testnut's sats are
test money: the site marks the mint as such, the app shows **test** on its
row and **test sats** and **Worth nothing** when it is in view, and a total
with them in it would be wrong. Until the site has answered, the app counts
`https://testnut.cashu.space` as the test mint by its address.

**Adding by invoice.** **Receive**, an amount, **Create invoice**. The sheet
shows the invoice as a code, with **Copy** and **Open wallet**, and asks
about it until it is paid or the sheet is closed. Then it says
**Received**. At a test mint there is no code to pay: the sats arrive by
themselves.

**Taking in ecash.** **Paste ecash** on the same sheet reads a Cashu token
from the clipboard, and a token that is scanned or opened is read the same
way. It is taken in at once. Only a token in sats from one of the mints the
site names is taken: a wallet that took any token would keep money at mints
nobody here has heard of. The sheet says how many sats arrived and at which
mint.

**Paying an invoice.** **Send**, **Pay invoice**, paste a Lightning invoice,
**Check**. A scanned invoice is checked at once, with nothing to paste. The
mint in view is asked what paying it would cost, and the sheet shows the
amount and **Fee up to N sats**, the most the mint may keep for it. Nothing
is paid until **Pay N sats** is pressed; **Cancel** leaves everything as it
was. Once begun the payment is seen through, and the sheet stays until it says
**Paid** or **Not paid**.

**Taking ecash out.** **Send**, **As ecash**, an amount or none for
everything at the mint in view, **Take out**. The sats leave the wallet as a
token, which is written to the phone before anything else happens, because
for a while it is the only copy of the money. The sheet shows it as a code,
with **Copy**. It is kept until its owner says **I have it** and holds
**Hold to delete this copy**. Until then **Send** opens that token and
nothing else.

**Recent.** Under the mints are the last twelve things that happened to the
money: added or paid by Lightning, received or sent as ecash, how much, at
which mint and when.

### Where the account is paid

What an account sells is paid at one mint, and a sale that names no mint is
paid from it. Until something sets it, that is Testnut. It becomes the mint
put in view, but only when nothing is for sale: an offer paid at the old
mint would wait for nobody. While something is for sale, choosing another
mint changes what the wallet shows and not where the account is paid. An
account that has never chosen is paid from then on at the mint where it
first buys.

### Earned by your collections

What a collection has been paid stays in the collection's own wallet. Each
collection the account made that holds something is a row under **Earned by
your collections**, with the amount, the mint it is at, and **Move to
wallet**. That takes the sats out of the collection as ecash and takes them
into the account's wallet. If they leave the collection and cannot be taken
in, the token is kept, the row says **Taken out, not yet in your wallet**,
and **Move to wallet** finishes it. The selling side is in
[selling.md](selling.md).

## What a code can be

| What is scanned or opened | What happens |
| --- | --- |
| A door's code | A sheet names the door and shows the ticket. **Show ticket** shows it. Nothing changes hands. |
| A code or link that sends an NFT | It is theirs at once: scanning it was the asking. |
| A code or link to a sale | A sheet shows the NFT and its price. **Pay** pays, and it arrives. |
| A link to a collection's page | What that collection has for sale. |
| An ecash token | It is taken into the wallet at once, if it is from one of the site's mints. |
| A Lightning invoice | A sheet says what paying it costs. **Pay N sats** pays it from the mint in view. |

A link to a sale or a gift on nonfungible.cash opens the app directly, so an
NFT sent in a message is one tap from being held.

**Scan** on Home and **Scan door code** on an NFT's page read the same
things, and so does a link opened with the app. Money is looked for first: an
ecash token, then an invoice, then the rest. What comes to the account is
taken without asking: an NFT that was sent, and ecash. What leaves it asks
first: a ticket shown to a scanned door, the price of a sale, an invoice.
Anything else gets "This code isn't a door code, an NFT link or a sale."

## The account on the site

What an account holds is under a profile of its own on the site. The profile
is made the first time it is needed, not for having looked: when an NFT is
received or bought, money is first added, or the cost of paying an invoice is
first asked. It carries the account's name.
Its only key is on the phone. **Copy backup key** in Settings copies it, and
**I have a backup key**, where a new account is named, brings its NFTs and
its wallet back into that account. Without the key they are lost with the
phone. Collections the account made have keys of their own.

## What it asks of the site

A venue's phones often share one address, and the site counts requests by
address. So the app shows what the phone already knows and asks only when
somebody else may have changed it: something is for sale, something paid for
has not arrived, or a link is out. Pulling the list down asks anyway. A ticket
shown by a tap, or as a code, costs no requests at all.

The Wallet tab asks what is at each mint when it is opened or pulled down,
and shows what it was last told until the answer comes. An account the site
has not heard of is shown the site's mints, empty, and no profile is made for
it.

## What is not there

- **Nobody has held one phone to another with this build.** See
  [door.md](door.md).
- **Real money is untested**, as for a collection's sales.
- **An NFT found by browsing does not say where its seller is paid.** The app
  offers at the mint the account is paid at, and a seller at another declines.
- **A mint cannot be added.** The wallet's mints are the ones the site names,
  and ecash from any other is refused.
- **Nothing moves sats from one mint to another** inside the wallet.
- **Sats are not sent to a Lightning address.** An invoice is paid, or ecash
  is taken out.
- **Only one ecash token taken out is kept at a time.** Another cannot be made
  until the first is said to be safe.
- **A link that sends an NFT cannot be taken back** from the app. The site can.
- **A key is put back only into a new account**, one that holds nothing yet.
- **Selling stops when Home is not on the screen**: with the app closed, or
  with another of its screens in front.
- **A purchase is not picked up again** if the app is killed while it waits.
  Sats already paid in stay in the wallet.
