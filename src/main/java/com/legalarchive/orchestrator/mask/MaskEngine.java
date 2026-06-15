package com.legalarchive.orchestrator.mask;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic, key-driven masking primitives (no external libraries — JDK only).
 *
 * For a given value: seed = HMAC-SHA256(secret, consistencyGroup + ":" + normalizedValue).
 * A counter-mode stream over the HMAC bytes provides a deterministic pseudo-random byte
 * source that drives every random choice for that value. Same input -> same output
 * (so joins on CID / repeated names survive across rows and feeds).
 *
 * Determinism is NOT reversibility: HMAC is one-way; there is no mapping table.
 *
 * Batch 1 strategies implemented here are format-preserving: numericFixed,
 * alphanumericFixed, cid. (Pool-based and free-text strategies arrive in later batches.)
 */
public final class MaskEngine {

    private final byte[] secret;

    public MaskEngine(String secret) {
        this.secret = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);
    }

    /** Deterministic byte stream for (group, value): counter-mode over HMAC-SHA256. */
    public final class Stream {
        private final byte[] base;     // HMAC(secret, group:value)
        private byte[] block;
        private int blockPos;
        private long counter;
        private final String groupValue;

        private Stream(String groupValue) {
            this.groupValue = groupValue;
            this.base = hmac(secret, groupValue.getBytes(StandardCharsets.UTF_8));
            this.block = base;
            this.blockPos = 0;
            this.counter = 0;
        }

        private void refill() {
            // next block = HMAC(secret, base || counter) — counter-mode expansion
            byte[] msg = new byte[base.length + 8];
            System.arraycopy(base, 0, msg, 0, base.length);
            long c = counter++;
            for (int i = 0; i < 8; i++) msg[base.length + i] = (byte) (c >>> (8 * (7 - i)));
            block = hmac(secret, msg);
            blockPos = 0;
        }

        /** next unsigned byte 0..255 */
        public int nextByte() {
            if (blockPos >= block.length) refill();
            return block[blockPos++] & 0xFF;
        }

        /** uniform integer in [0, bound), bound>0, rejection-sampled to avoid modulo bias */
        public int nextInt(int bound) {
            if (bound <= 0) return 0;
            // gather 4 bytes
            int limit = (int) ((0x100000000L / bound) * bound);   // largest multiple of bound <= 2^32
            while (true) {
                long v = ((long) nextByte() << 24) | (nextByte() << 16) | (nextByte() << 8) | nextByte();
                v &= 0xFFFFFFFFL;
                if (v < (limit & 0xFFFFFFFFL) || limit == 0) return (int) (v % bound);
            }
        }
    }

    public Stream stream(String consistencyGroup, String normalizedValue) {
        return new Stream(consistencyGroup + ":" + normalizedValue);
    }

    // ---- format-preserving strategies (Batch 1) ----

    private static final char[] DIGITS = "0123456789".toCharArray();

    /**
     * numericFixed: each digit -> a deterministic random digit. Non-digit characters
     * (separators like - / . , space) are preserved by default. Leading zeros preserved
     * implicitly because we replace digit-by-digit in place. Empty stays empty.
     */
    public String numericFixed(String value, String group, String normalized, boolean preserveSeparators) {
        if (value == null || value.isEmpty()) return value;
        Stream s = stream(group, normalized);
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') sb.append(DIGITS[s.nextInt(10)]);
            else if (preserveSeparators) sb.append(c);
            else sb.append(DIGITS[s.nextInt(10)]);   // treat everything as a digit slot
        }
        return sb.toString();
    }

    /**
     * alphanumericFixed / cid: replace each alphanumeric char with a deterministic random
     * char of the SAME class (digit->digit, A-Z->A-Z, a-z->a-z). Non-alphanumeric positions
     * (punctuation, spaces, separators) are preserved verbatim. Exact length preserved.
     * Empty stays empty.
     */
    public String alphanumericFixed(String value, String group, String normalized) {
        if (value == null || value.isEmpty()) return value;
        Stream s = stream(group, normalized);
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') sb.append((char) ('0' + s.nextInt(10)));
            else if (c >= 'A' && c <= 'Z') sb.append((char) ('A' + s.nextInt(26)));
            else if (c >= 'a' && c <= 'z') sb.append((char) ('a' + s.nextInt(26)));
            else sb.append(c);   // preserve layout (separators, punctuation)
        }
        return sb.toString();
    }

    private static byte[] hmac(byte[] key, byte[] msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // an empty key is not allowed by some providers; pad to one zero byte
            byte[] k = key.length == 0 ? new byte[]{0} : key;
            mac.init(new SecretKeySpec(k, "HmacSHA256"));
            return mac.doFinal(msg);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable: " + e.getMessage(), e);
        }
    }
}
