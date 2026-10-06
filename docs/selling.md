# Prices and selling

This page is about a collection made on this phone: setting its prices and
selling from it. What an account does with the NFTs it holds, and its wallet,
is in [guest.md](guest.md). Where each screen is reached from is in
[app.md](app.md).

A collection and the account that made it keep separate things. A collection
has a key and a wallet of its own; what the account holds is in a collection
of the account's own. A shop is kept for every collection made on this phone,
whichever account is in use, while the app is open.

## What a sale is

The app does not invent a way to sell. It uses the site's market, through the
site's own code. The buyer's ecash is locked for the seller and released only
against the NFT, and the NFT goes straight into the buyer's collection. Paying
and receiving happen together or not at all.

The site's market has two rules worth knowing:

- An offer must meet the asking price. There are no offers below it.
- A seller accepts an offer; nothing sells while nobody is there to accept.

The second is why selling needs the app open. While the app is running it
accepts, by itself, every offer that is for something it has
on sale, pays what is asked for it now in the money you chose, and passes the
site's own check of the payment. An offer made before a price went up is
declined, not taken at the old price. The site
holds the question "any news?" open, so an offer is taken within a second or
two while the phone asks only twice a minute. Close the app and offers wait.

## Prices

**Prices** is under **More** on a collection's page; a collection that is not
tickets has it as a button at the foot of its page instead. It is one screen:

- **Today**: a price for each ticket type. A type with no price is not for sale.
- **Add price change**: a day, and the prices from that day. Add as many as the
  event has stages. This is how conferences price: one pass whose price steps
  up on fixed dates, with a cheaper type beside it for students or locals.
- **Sell automatically**: on the day, the later price becomes today's and
  what is for sale is repriced, with nobody touching anything. Switched off, a
  later price waits until you tap **Apply now**, and payments wait for you on
  the site.
- **sats, USD or EUR**: what the prices are written in. An event that thinks
  in dollars writes dollars; each time something is put on sale the amount is
  turned into sats at the rate mempool.space gives, rounded to three figures,
  and repriced only when the rate has moved by a hundredth. With no rate less
  than an hour old, nothing priced in a currency is on sale.
- **Receive payments at**: the mint whose ecash you accept. The site suggests a few;
  **Testnut** is test money, worth nothing, for trying all of this.
- **Sell on the site**: switch it on and the app keeps five of each priced
  type listed on the site's market, lowest numbers first, and lists another
  as each sells. What it has listed is exactly what the plan wants listed: an
  NFT that was admitted at the door, or whose type lost its price, comes off
  sale on the next round. Switching it off takes everything off sale, and the
  screen says so if something could not be.

The line at the bottom says how the shop stands: how many are out, how many
sold and for how much, and when prices next change.

What a collection has been paid stays in the collection's own wallet until
**Move to wallet**. That is on the Wallet tab, under **Earned by your
collections**, where each collection that holds something has a row, and on
this screen as **Move N sats to your wallet**. It takes everything the
collection holds at the mint it receives payments at out of the collection as
ecash, and the wallet of the account in use takes that ecash in, at the same
mint.

For a moment the ecash is all there is of the money, so the phone keeps it
until the wallet has it:

- If the sats cannot be taken out of the collection, nothing has moved, and
  the app says so.
- If they left the collection and the wallet could not take them in, the ecash
  is kept. The row says **Taken out, not yet in your wallet**, and **Move to
  wallet** finishes with that same ecash before anything else is taken out.

What the wallet does with sats once it has them is in [guest.md](guest.md).

## Listed and sold

A collection minted on this phone holds its NFTs until they are sent or sold,
and the app counts in those words wherever it counts: under the collection's
name on the door ("North door · 12 of 40 sold"), on the Collections tab
("40 NFTs · 12 sold or sent"), and as **Listed** and **Sold** on a
collection's page, where each count is also a filter. "Listed" is what the
collection has on the market as of its last round of selling. An NFT the
collection still holds says **Yours** in the list, or **Listed** when it is on
the market.

## Selling or sending one at the door

**Sell or send**, on a collection's page and on its door, is one flow:

1. **Which ticket type?** Each says how many are left and what it costs. The
   app takes the lowest-numbered one of that type that the collection still
   holds and has not admitted. A type with none left opens **Mint more**. A
   collection of one type skips the question.
2. **Sell for N sats** or **Send free.** A type with no price is only sent.
3. **A code.** The guest scans it with the app on their own phone. For a sale
   they pay, and the NFT is theirs as the sats arrive, both or neither. Closing
   the sheet before they pay takes it off sale again, after one last look for a
   payment that was on its way.
4. **A tick**, when it is theirs, and **Mark admitted** to let them in with it
   there and then. They can also simply hold their phone to the door.

An NFT that was listed for sale comes off sale before it is sent free. An NFT
sent by a code is promised from that moment: it is not also sold.

The same sheet opens from any NFT in the list that the collection still holds.

## When a holder cannot come

A holder sells their ticket on the site's market like any NFT: open it,
**Sell**, and name a price. Anyone can buy it at that price. They are paid at
the mint their wallet has in view, which stays as it is while they have
something for sale: see [guest.md](guest.md).

**For sale by guests**, at the bottom of Prices, lists every NFT of the
collection that somebody else has up for sale, cheapest first. **Buy back**
offers the holder the price shown, and no more if they have raised it since,
out of what the collection has been paid. The NFT returns when they accept.

A used ticket looks like an unused one to a buyer: only the door's record
knows it was let in. Buying a ticket second-hand on the day carries that risk.

## What is not there

- **No bids below the asking price**, and no timed auctions: the site's market
  has neither.
- **A buyer who loses a race waits.** Two guests can offer on the same NFT; the
  one not chosen has their money back only when their offer runs out, a day by
  default. The app keeps several of a type listed so that this is rare.
- **Nothing sells while the app is closed.** The schedule and the accepting
  both run in the app.
- **No tap to pay.** A phone held to the door shows a ticket; a sale at the
  door is still a code the guest scans.
- **Numo's own wallet was not brought back.** The collection's money is in the
  site's ecash wallet, driven by the site's code, because that is the wallet
  the site's market pays into.
- **Real money is untested.** Everything here was run with Testnut's test sats
  against a local copy of the site. The site's market code calls itself
  experimental and asks for review before real use.
