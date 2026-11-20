package dev.frontlineand2engineers.ocradditions.compute;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Zero-allocation-friendly cryptographic hashing and Proof-of-Work utilities
 * backed by Java's hardware-accelerated MessageDigest implementations.
 */
public final class CryptoKernels {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public enum Algorithm {
        SHA256("SHA-256", false),
        SHA256D("SHA-256", true),
        SHA512("SHA-512", false),
        SHA3_256("SHA3-256", false),
        MD5("MD5", false);

        public final String jcaName;
        public final boolean doublePass;

        private final ThreadLocal<MessageDigest> localDigest;

        Algorithm(String jcaName, boolean doublePass) {
            this.jcaName = jcaName;
            this.doublePass = doublePass;
            this.localDigest = ThreadLocal.withInitial(() -> {
                try {
                    return MessageDigest.getInstance(jcaName);
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException("Missing JCA digest: " + jcaName, e);
                }
            });
        }

        public MessageDigest digest() {
            MessageDigest md = localDigest.get();
            md.reset();
            return md;
        }

        public static Algorithm parse(String name) {
            if (name == null || name.isBlank()) {
                return SHA256;
            }
            return switch (name.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "sha256", "sha_256" -> SHA256;
                case "sha256d", "double_sha256", "sha_256d", "bitcoin" -> SHA256D;
                case "sha512", "sha_512" -> SHA512;
                case "sha3_256", "sha3" -> SHA3_256;
                case "md5" -> MD5;
                default -> throw new IllegalArgumentException("Unsupported hash algorithm: " + name);
            };
        }
    }

    private CryptoKernels() {
    }

    public static byte[] hashBytes(Algorithm algo, byte[] input) {
        MessageDigest md = algo.digest();
        byte[] first = md.digest(input);
        if (!algo.doublePass) {
            return first;
        }
        md.reset();
        return md.digest(first);
    }

    /**
     * Hashes {@code prefix || decimal(nonce) || suffix} into the caller-supplied digest without allocating Strings.
     */
    public static byte[] hashNonce(
            MessageDigest md,
            boolean doublePass,
            byte[] prefixBytes,
            long nonce,
            byte[] suffixBytes,
            byte[] nonceScratch
    ) {
        md.reset();
        if (prefixBytes.length > 0) {
            md.update(prefixBytes);
        }
        int start = writeAsciiLong(nonce, nonceScratch);
        md.update(nonceScratch, start, nonceScratch.length - start);
        if (suffixBytes.length > 0) {
            md.update(suffixBytes);
        }
        byte[] out = md.digest();
        if (!doublePass) {
            return out;
        }
        md.reset();
        return md.digest(out);
    }

    /**
     * Writes an ASCII signed base-10 long right-aligned into {@code buf} (at least 21 bytes)
     * and returns the starting offset.
     */
    public static int writeAsciiLong(long value, byte[] buf) {
        int pos = buf.length;
        if (value == 0L) {
            buf[--pos] = '0';
            return pos;
        }
        boolean negative = value < 0L;
        long q = negative ? value : -value;
        while (q <= -10L) {
            long next = q / 10L;
            int digit = (int) (next * 10L - q);
            buf[--pos] = (byte) ('0' + digit);
            q = next;
        }
        buf[--pos] = (byte) ('0' + (int) (-q));
        if (negative) {
            buf[--pos] = '-';
        }
        return pos;
    }

    public static boolean hasLeadingZeroBits(byte[] hash, int requiredBits) {
        if (requiredBits <= 0) return true;
        int fullBytes = requiredBits >>> 3;
        if (fullBytes > hash.length) return false;
        for (int i = 0; i < fullBytes; i++) {
            if (hash[i] != 0) return false;
        }
        int remBits = requiredBits & 7;
        if (remBits == 0) return true;
        if (fullBytes >= hash.length) return false;
        int mask = 0xFF << (8 - remBits);
        return (hash[fullBytes] & mask) == 0;
    }

    public static int leadingZeroBitsHex(String hex) {
        if (hex == null || hex.isEmpty()) return 0;
        int start = (hex.startsWith("0x") || hex.startsWith("0X")) ? 2 : 0;
        int bits = 0;
        for (int i = start; i < hex.length(); i++) {
            int digit = Character.digit(hex.charAt(i), 16);
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid hex digit at index " + i);
            }
            if (digit == 0) {
                bits += 4;
            } else {
                bits += Integer.numberOfLeadingZeros(digit) - 28;
                break;
            }
        }
        return bits;
    }

    public static int leadingZeroNibblesHex(String hex) {
        if (hex == null || hex.isEmpty()) return 0;
        int start = (hex.startsWith("0x") || hex.startsWith("0X")) ? 2 : 0;
        int zeros = 0;
        for (int i = start; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (c == '0') {
                zeros++;
            } else {
                break;
            }
        }
        return zeros;
    }

    /**
     * Parses a lowercase/uppercase hex prefix string into nibble values (0..15) for fast direct matching.
     */
    public static int[] parseHexNibbles(String hexPrefix) {
        String clean = hexPrefix == null ? "" : hexPrefix.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("0x")) {
            clean = clean.substring(2);
        }
        int[] nibbles = new int[clean.length()];
        for (int i = 0; i < clean.length(); i++) {
            int digit = Character.digit(clean.charAt(i), 16);
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid hex character in target prefix: " + clean.charAt(i));
            }
            nibbles[i] = digit;
        }
        return nibbles;
    }

    public static boolean matchesHexNibbles(byte[] hash, int[] nibbles) {
        if (nibbles.length > hash.length * 2) return false;
        for (int i = 0; i < nibbles.length; i++) {
            int b = hash[i >>> 1] & 0xFF;
            int actual = (i & 1) == 0 ? (b >>> 4) : (b & 0x0F);
            if (actual != nibbles[i]) {
                return false;
            }
        }
        return true;
    }

    public static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            chars[i * 2] = HEX[v >>> 4];
            chars[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(chars);
    }

    public static byte[] hexToBytes(String hex) {
        String clean = hex == null ? "" : hex.trim();
        if ((clean.length() & 1) != 0) {
            throw new IllegalArgumentException("Hex string must have even length");
        }
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex byte in input");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static byte[] utf8(String text) {
        return text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
    }
}
