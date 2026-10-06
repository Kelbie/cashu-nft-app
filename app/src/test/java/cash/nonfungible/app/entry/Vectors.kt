package cash.nonfungible.app.entry

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Vectors made by the reference implementation, cashu-nft's scripts/tickets/entry.py. */
object Vectors {
    private val all: JsonObject = checkNotNull(javaClass.getResourceAsStream("/door-vectors.json"))
        .reader().use { JsonParser.parseReader(it).asJsonObject }
    private val request: JsonObject = all.getAsJsonObject("request")

    val collection = checkNotNull(Collection.parse(request.get("collection").asString))
    val door: String = request.get("door").asString
    val nprofile: String = request.get("nprofile").asString
    val id: String = request.get("id").asString
    val encoded: String = request.get("encoded").asString
    val fields: JsonObject = request.getAsJsonObject("fields")

    val contextHex: String = all.get("context_hex").asString
    val bindingHex: String = all.get("binding_hex").asString
    val presentationHex: String = all.get("presentation_hex").asString
    val nullifierHex: String = all.get("nullifier_hex").asString
    val answer: String = all.get("answer").toString()
    val proof: String = all.getAsJsonObject("answer").getAsJsonArray("proofs").get(0).asString

    val assetHash: String = all.get("asset_hash").asString

    /** An answer that shows two NFTs: the ticket, and a plain picture. */
    val group: String = all.get("answer_group").toString()
    val plainHash: String = all.get("plain_hash").asString

    // A collection that holds the ticket: its key, and the card its page claims the ticket with.
    private val holding: JsonObject = all.getAsJsonObject("holder")
    val holder: String = holding.get("pubkey").asString
    val holderCard: JsonObject get() = holding.getAsJsonObject("card").deepCopy()
    val signedByAnother: String = holding.get("signed_by_another").asString
    val claimOfAnotherCredential: String = holding.get("claim_of_another_credential").asString

    val asset: ByteArray
        get() = java.util.Base64.getDecoder().decode(all.get("asset_jpg_base64").asString)

    /** A picture that carries no metadata. */
    val plain: ByteArray
        get() = java.util.Base64.getDecoder().decode(all.get("plain_jpg_base64").asString)

    fun asked(collection: Collection = this.collection): Asked =
        Entry.request(collection, door, nprofile, id)
}
