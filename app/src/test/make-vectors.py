"""Makes resources/door-vectors.json again with the reference door, when the protocol changes.

Run it in a cashu-nft checkout, with that project's Python environment:

    python ../numo/app/src/test/make-vectors.py ../numo/app/src/test/resources/door-vectors.json

It keeps the file's plain picture and its request's fixed parts, and makes everything that
depends on the protocol or on the metadata format afresh: the ticket's picture, which says what
it is in the current format, the request, a holder's answers for one NFT and for two, and the
page of a collection that holds the first. The mint and the holder's keys are throwaways.
"""

import base64
import io
import json
import sys

import cbor2

sys.path[:0] = ["tests", "scripts/tickets", "."]
import entry  # noqa: E402
import metadata  # noqa: E402
from coincurve import PrivateKey  # noqa: E402
from PIL import Image  # noqa: E402
from test_nft_tickets import Mint, hashed  # noqa: E402

from cashu.nft.portfolio import claim_digest, make_showing  # noqa: E402

path = sys.argv[1]
with open(path) as kept:
    old = json.load(kept)
plain = base64.b64decode(old["plain_jpg_base64"])
request, mint = old["request"], Mint()
# The ticket: the picture that was there, saying what a ticket of the request's collection says.
said = metadata.parse_metadata(
    json.dumps(
        {
            "schema": metadata.SCHEMA,
            "name": "An Evening",
            "kind": "General",
            "edition": {"number": 2, "of": 5},
            "collection": {
                "id": request["collection"][-64:],
                "url": request["collection"],
            },
            "ext": {"ticket": {}},
            "style": {
                "template": "ticket-sleeve/1",
                "palette": {
                    "ground": "#111111",
                    "panel": "#222222",
                    "ink": "#eeeeee",
                    "accent": "#ff5500",
                },
            },
        }
    ).encode()
)
if said is None:
    raise SystemExit("The ticket's document does not read as one.")
asset = metadata.metadata_jpg(
    Image.open(io.BytesIO(base64.b64decode(old["asset_jpg_base64"]))), said
)
entry.secrets.token_hex = lambda size: request["id"]
asked = entry.request(request["collection"], request["door"], request["nprofile"])
(one, held), (two, _) = mint.transfer_file(asset), mint.transfer_file(plain)
answer, group = entry.prove(one, asked), entry.prove(one, asked, two)
context = entry.challenge(asked)
presentation = bytes.fromhex(answer["proofs"][0][6:])[2 + len(context) :]

# A holder who says who they are: their collection's page claims the credential they show,
# with a showing that names their key, signed with it.
key, other = PrivateKey(b"\x07" * 32), PrivateKey(b"\x09" * 32)
pubkey = key.public_key_xonly.format().hex()
showing = make_showing(pubkey, held)
card = {
    "id": "f" * 32,
    "pubkey": pubkey,
    "h": hashed(asset),
    "title": "Mine",
    "showing": showing,
    "status": "owned",
    "signature": key.sign_schnorr(claim_digest(showing)).hex(),
}
padded = asked[5:] + "=" * (-len(asked[5:]) % 4)
made = {
    **old,
    "asset_jpg_base64": base64.b64encode(asset).decode(),
    "asset_hash": hashed(asset),
    "metadata": metadata.normal(said),
    "request": {
        **request,
        "encoded": asked,
        "fields": cbor2.loads(base64.urlsafe_b64decode(padded)),
    },
    "context_hex": context.hex(),
    "binding_hex": (
        b"Cashu_PS_Showing_v1" + len(context).to_bytes(2, "big") + context
    ).hex(),
    "answer": answer,
    "answer_group": group,
    "plain_hash": hashed(plain),
    "presentation_hex": presentation.hex(),
    "nullifier_hex": presentation[209:257].hex(),
    "mint_public_key_hex": mint.key.public_key.to_bytes().hex(),
    "mint_keyset_id": mint.key.public_key.keyset_id,
    "holder": {
        "pubkey": pubkey,
        "card": card,
        "signed_by_another": other.sign_schnorr(claim_digest(showing)).hex(),
        "claim_of_another_credential": make_showing(
            pubkey, mint.transfer_file(asset)[1]
        ),
    },
}
with open(path, "w") as kept:
    json.dump(made, kept, indent=1)
