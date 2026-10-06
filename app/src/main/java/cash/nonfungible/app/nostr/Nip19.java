package cash.nonfungible.app.nostr;

import static cash.nonfungible.app.nostr.Bech32.convertBits;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Minimal NIP-19 encoder for nsec / npub / nprofile.
 */
public final class Nip19 {

    private Nip19() {}

    private static final String HRP_NPROFILE = "nprofile";

    /**
     * Encode an nprofile with:
     * - TLV type 0: 32-byte pubkey
     * - TLV type 1: one or more relay URLs
     */
    public static String encodeNprofile(byte[] pub32, List<String> relays) {
        if (pub32 == null || pub32.length != 32) {
            throw new IllegalArgumentException("pubkey must be 32 bytes");
        }
        ByteArrayOutputStream tlv = new ByteArrayOutputStream();
        // type=0, length=32, pubkey
        tlv.write(0x00);
        tlv.write(32);
        tlv.write(pub32, 0, 32);

        if (relays != null) {
            for (String r : relays) {
                if (r == null || r.isEmpty()) continue;
                byte[] relBytes = r.getBytes(StandardCharsets.UTF_8);
                if (relBytes.length == 0 || relBytes.length > 255) continue;
                tlv.write(0x01);
                tlv.write(relBytes.length);
                tlv.write(relBytes, 0, relBytes.length);
            }
        }

        byte[] data5 = convertBits(tlv.toByteArray(), 8, 5, true);
        return Bech32.encode(HRP_NPROFILE, data5);
    }
}
