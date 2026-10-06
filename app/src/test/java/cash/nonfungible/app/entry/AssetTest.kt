package cash.nonfungible.app.entry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AssetTest {

    @Test
    fun `asset hash is the reference's`() {
        assertEquals(Vectors.assetHash, assetHash(Vectors.asset))
    }

    @Test
    fun `asset hash covers every byte of the asset`() {
        val asset = Vectors.asset
        asset[asset.size - 1] = (asset[asset.size - 1] + 1).toByte()
        assertNotEquals(Vectors.assetHash, assetHash(asset))
        assertNotEquals(Vectors.assetHash, assetHash(Vectors.asset + 0))
    }
}
