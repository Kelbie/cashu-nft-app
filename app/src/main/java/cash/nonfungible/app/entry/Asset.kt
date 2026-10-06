package cash.nonfungible.app.entry

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest

// The order of the BLS12-381 scalar field.
private val ORDER =
    BigInteger("52435875175126190479447740508185965837690552500527637822603658699938581184513")

/** An asset's identity, the scalar its mint signs: NUT-XX "Asset hash". */
fun assetHash(asset: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("Cashu_PS_Asset_v1".toByteArray())
    digest.update(ByteBuffer.allocate(4).putInt(asset.size).array())
    return BigInteger(1, digest.digest(asset)).mod(ORDER).toString(16).padStart(64, '0')
}
