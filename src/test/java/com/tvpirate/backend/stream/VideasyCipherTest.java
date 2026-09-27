package com.tvpirate.backend.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * The videasy cipher's port fidelity. Their JS derives every sbox index with
 * {@code %} on an UNSIGNED value, so a Java {@code floorMod} shifts each one
 * by 2^32 mod 61 == 57 and yields a keystream that still base64-decodes and
 * still "works" — it just never produces the magic. The golden arrays below
 * were generated from their own player bundle, so that mistake cannot come
 * back unnoticed. vault:streaming-providers-deep-dive#videasy-wire
 */
class VideasyCipherTest {

    private static final String SEED = "e3f1a9c07b2d4e6f";

    /** Fight Club — the title the vault's live E2E run used. */
    private static final long MEDIA_ID = 550L;

    /** First 32 keystream bytes for (SEED, MEDIA_ID), taken from their bundle's
     *  generator. The floorMod variant diverges from byte 0 (it starts -41). */
    private static final byte[] GOLDEN_KEYSTREAM = {
            110, -8, 83, 13, -90, 75, -99, -63, -53, -118, -70, -71, -113, -121, 32, -40,
            109, 91, 22, -20, 4, 1, 110, -81, -95, -116, -82, -113, -68, 49, 102, 91 };

    private static final String SOURCES_JSON =
            "{\"sources\":[{\"quality\":\"1080p\",\"url\":\"https://moon.peakstorm.top/f/1080/index.m3u8\"}]}";

    /** "mvm1" + SOURCES_JSON, XORed with the keystream above and base64'd by
     *  their bundle — an end-to-end golden neither side of our port produced. */
    private static final String GOLDEN_CIPHERTEXT =
            "A44+PN1p7q6++Nnc/KUagxZ5Z5llbQfb2K6UrY0BXmsR0y7sTdVKHjrNAwT75ne0l9P7sPQ2OYDrDBImSnL8KlMdSgF6fkS8uHmEY8BmM5XPQpZYfxLk9k4I";

    // --- the keystream ---

    @Test
    void theDerivedKeystreamMatchesTheirPlayerBundle() {
        assertThat(VideasyProvider.keystream(SEED, MEDIA_ID, GOLDEN_KEYSTREAM.length))
                .containsExactly(GOLDEN_KEYSTREAM);
    }

    @Test
    void aLongerRunKeepsTheSameLeadingBytes() {
        byte[] longRun = VideasyProvider.keystream(SEED, MEDIA_ID, 256);

        assertThat(Arrays.copyOf(longRun, GOLDEN_KEYSTREAM.length)).containsExactly(GOLDEN_KEYSTREAM);
    }

    @Test
    void aLengthThatStopsMidWordKeepsOnlyThatWordsLowBytes() {
        assertThat(VideasyProvider.keystream(SEED, MEDIA_ID, 3))
                .containsExactly(Arrays.copyOf(GOLDEN_KEYSTREAM, 3));
    }

    @Test
    void theSeedFeedsTheKeystream() {
        assertThat(VideasyProvider.keystream("f3f1a9c07b2d4e6f", MEDIA_ID, GOLDEN_KEYSTREAM.length))
                .isNotEqualTo(GOLDEN_KEYSTREAM);
    }

    @Test
    void theMediaIdFeedsTheKeystreamToo() {
        assertThat(VideasyProvider.keystream(SEED, MEDIA_ID + 1, GOLDEN_KEYSTREAM.length))
                .isNotEqualTo(GOLDEN_KEYSTREAM);
    }

    // --- decrypt ---

    @Test
    void theirGoldenCiphertextDecryptsToItsJson() {
        assertThat(VideasyProvider.decrypt(GOLDEN_CIPHERTEXT, SEED, MEDIA_ID)).isEqualTo(SOURCES_JSON);
    }

    @Test
    void xoringTheMagicAndJsonWithTheKeystreamReproducesTheirCiphertext() {
        // The cipher is a plain stream XOR, so encryption is the same operation.
        assertThat(encrypt(SOURCES_JSON, SEED, MEDIA_ID)).isEqualTo(GOLDEN_CIPHERTEXT);
    }

    @Test
    void aLocallyEncryptedPayloadRoundTrips() {
        String json = "{\"sources\":[{\"quality\":\"480p\",\"url\":\"https://moon.peakstorm.top/f/480/index.m3u8\"}]}";

        assertThat(VideasyProvider.decrypt(encrypt(json, SEED, MEDIA_ID), SEED, MEDIA_ID)).isEqualTo(json);
    }

    @Test
    void theUrlSafeAlphabetIsNormalisedBeforeDecoding() {
        assertThat(GOLDEN_CIPHERTEXT).contains("+").contains("/"); // both need swapping
        String urlSafe = GOLDEN_CIPHERTEXT.replace('+', '-').replace('/', '_');

        assertThat(VideasyProvider.decrypt(urlSafe, SEED, MEDIA_ID)).isEqualTo(SOURCES_JSON);
    }

    @Test
    void strippedBase64PaddingIsRestored() {
        // One char shorter than SOURCES_JSON, so its base64 really does need a '='.
        String json = "{\"sources\":[{\"quality\":\"1080p\",\"url\":\"https://moon.peakstorm.top/f/108/index.m3u8\"}]}";
        String padded = encrypt(json, SEED, MEDIA_ID);
        assertThat(padded).endsWith("=");

        assertThat(VideasyProvider.decrypt(padded.replace("=", ""), SEED, MEDIA_ID)).isEqualTo(json);
    }

    // --- the magic check is the only integrity signal ---

    @Test
    void aWrongSeedFailsInsteadOfReturningGarbage() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> VideasyProvider.decrypt(GOLDEN_CIPHERTEXT, "0000000000000000", MEDIA_ID))
                .withMessage("bad seed or tampered payload");
    }

    @Test
    void aWrongMediaIdFailsToo() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> VideasyProvider.decrypt(GOLDEN_CIPHERTEXT, SEED, MEDIA_ID + 1))
                .withMessage("bad seed or tampered payload");
    }

    @Test
    void aCorruptedPayloadIsRejected() {
        byte[] cipher = Base64.getDecoder().decode(GOLDEN_CIPHERTEXT);
        cipher[2] ^= 0x40;

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> VideasyProvider.decrypt(
                        Base64.getEncoder().encodeToString(cipher), SEED, MEDIA_ID))
                .withMessage("bad seed or tampered payload");
    }

    @Test
    void aPayloadShorterThanTheMagicIsRejected() {
        String twoBytes = Base64.getEncoder().encodeToString(new byte[] { 1, 2 });

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> VideasyProvider.decrypt(twoBytes, SEED, MEDIA_ID))
                .withMessage("bad seed or tampered payload");
    }

    @Test
    void anEmptyPayloadIsRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> VideasyProvider.decrypt("", SEED, MEDIA_ID))
                .withMessage("bad seed or tampered payload");
    }

    @Test
    void aNonBase64PayloadIsRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> VideasyProvider.decrypt("not base64 at all!!", SEED, MEDIA_ID));
    }

    // --- helpers ---

    /** The encrypt side of the same stream cipher: "mvm1" + json, XORed, base64. */
    private static String encrypt(String json, String seed, long mediaId) {
        byte[] clear = ("mvm1" + json).getBytes(StandardCharsets.UTF_8);
        byte[] key = VideasyProvider.keystream(seed, mediaId, clear.length);
        byte[] cipher = new byte[clear.length];
        for (int i = 0; i < clear.length; i++) {
            cipher[i] = (byte) (clear[i] ^ key[i]);
        }
        return Base64.getEncoder().encodeToString(cipher);
    }
}
