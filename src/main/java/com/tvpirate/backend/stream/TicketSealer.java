package com.tvpirate.backend.stream;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Child proxy tickets that carry their own target: the root ticket plus the
 * child URL, AES-GCM encrypted under a per-process key, so a playlist rewrite
 * stores nothing and the browser still never sees a CDN URL. GCM's tag makes
 * any tampered ticket fail to open. vault:stream-proxy-deep-dive#sealed-tickets
 */
class TicketSealer {

    static final String PREFIX = "s.";

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    /** Root tickets are 32-hex UUIDs without dashes. */
    private static final int ROOT_LENGTH = 32;

    /** What a sealed ticket opens to. */
    record Unsealed(String rootToken, String childUrl) {
    }

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    /** A fresh key per process: roots die on restart anyway, so there's nothing to persist. */
    TicketSealer() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            this.key = generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-256 unavailable", e);
        }
    }

    static boolean isSealed(String ticket) {
        return ticket != null && ticket.startsWith(PREFIX);
    }

    String seal(String rootToken, String childUrl) {
        if (rootToken.length() != ROOT_LENGTH) {
            throw new IllegalArgumentException("root token must be " + ROOT_LENGTH + " chars");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] sealed = cipher(Cipher.ENCRYPT_MODE, nonce, (rootToken + childUrl).getBytes(StandardCharsets.UTF_8));
        byte[] ticket = ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(ticket);
    }

    /** Null for anything that isn't a ticket this process sealed, unmodified. */
    Unsealed unseal(String ticket) {
        if (!isSealed(ticket)) {
            return null;
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(ticket.substring(PREFIX.length()));
            if (raw.length <= NONCE_BYTES) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(raw);
            byte[] nonce = new byte[NONCE_BYTES];
            buffer.get(nonce);
            byte[] sealed = new byte[buffer.remaining()];
            buffer.get(sealed);
            String plain = new String(cipher(Cipher.DECRYPT_MODE, nonce, sealed), StandardCharsets.UTF_8);
            return plain.length() <= ROOT_LENGTH
                    ? null
                    : new Unsealed(plain.substring(0, ROOT_LENGTH), plain.substring(ROOT_LENGTH));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null; // bad base64, or a tag that doesn't verify
        }
    }

    /** A new Cipher per call — Cipher instances aren't thread-safe. */
    private byte[] cipher(int mode, byte[] nonce, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
