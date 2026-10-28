package com.interlink.app.security;

import com.interlink.app.util.LogUtil;

import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Handles cryptographic operations for InterLink:
 * 1. Derives AES-256 session keys using PBKDF2WithHmacSHA256 from a 6-digit pairing PIN and random salt.
 * 2. Authenticates handshakes using HMAC-SHA256 challenge-response.
 * 3. Encrypts and decrypts real-time voice packets using AES-256-GCM (Authenticated Encryption).
 * 4. Provides replay attack protection via monotonic packet sequence validation.
 */
public class SessionSecurity {
    private static final String TAG = "SessionSecurity";

    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String AES_ALGORITHM = "AES";
    private static final String CIPHER_GCM = "AES/GCM/NoPadding";

    private static final int PBKDF2_ITERATIONS = 10000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    public static final int GCM_IV_LENGTH_BYTES = 12;
    public static final int SALT_LENGTH_BYTES = 16;
    public static final int CHALLENGE_LENGTH_BYTES = 16;
    private static final int REPLAY_WINDOW_SIZE = 128;

    private final SecureRandom secureRandom;
    private byte[] sessionKeyBytes;
    private SecretKey secretKey;

    // Replay protection tracking
    private long highestReceivedSeq = -1;
    private long replayWindowBitmap = 0; // 64-128 packet bitmask

    public SessionSecurity() {
        this.secureRandom = new SecureRandom();
    }

    /**
     * Generates a 6-digit numeric pairing PIN (e.g. 748201).
     */
    public static String generatePairingPin() {
        SecureRandom rng = new SecureRandom();
        int pin = rng.nextInt(900000) + 100000;
        return String.valueOf(pin);
    }

    /**
     * Generates a random session salt.
     */
    public byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        secureRandom.nextBytes(salt);
        return salt;
    }

    /**
     * Generates a random authentication challenge.
     */
    public byte[] generateChallenge() {
        byte[] challenge = new byte[CHALLENGE_LENGTH_BYTES];
        secureRandom.nextBytes(challenge);
        return challenge;
    }

    /**
     * Derives an AES-256 key from a 6-digit PIN and 16-byte salt using PBKDF2.
     */
    public synchronized void initializeSessionKey(String pairingPin, byte[] salt) throws Exception {
        if (pairingPin == null || pairingPin.trim().isEmpty()) {
            throw new IllegalArgumentException("Pairing PIN cannot be empty");
        }
        if (salt == null || salt.length < SALT_LENGTH_BYTES) {
            throw new IllegalArgumentException("Invalid salt length");
        }

        char[] pinChars = pairingPin.trim().toCharArray();
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
            KeySpec spec = new PBEKeySpec(pinChars, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
            byte[] rawKey = factory.generateSecret(spec).getEncoded();
            this.sessionKeyBytes = rawKey;
            this.secretKey = new SecretKeySpec(rawKey, AES_ALGORITHM);
            resetReplayProtection();
            LogUtil.d(TAG, "AES-256 session key derived successfully");
        } finally {
            Arrays.fill(pinChars, '\0');
        }
    }

    /**
     * Computes HMAC-SHA256 of challenge bytes using the derived session key.
     */
    public synchronized byte[] computeHmac(byte[] challenge) throws Exception {
        if (sessionKeyBytes == null) {
            throw new IllegalStateException("Session key not initialized");
        }
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(sessionKeyBytes, HMAC_ALGORITHM));
        return mac.doFinal(challenge);
    }

    /**
     * Verifies an incoming HMAC against the challenge using constant-time comparison.
     */
    public synchronized boolean verifyHmac(byte[] challenge, byte[] expectedHmac) {
        try {
            byte[] actualHmac = computeHmac(challenge);
            return MessageDigestUtils.constantTimeEquals(actualHmac, expectedHmac);
        } catch (Exception e) {
            LogUtil.e(TAG, "HMAC verification error", e);
            return false;
        }
    }

    /**
     * Encrypts an audio payload using AES/GCM/NoPadding.
     * Returns: 12-byte IV + Ciphertext + 16-byte GCM Tag.
     */
    public synchronized byte[] encryptVoiceFrame(long sequenceNumber, byte[] pcmData, int offset, int length) throws Exception {
        if (secretKey == null) {
            throw new IllegalStateException("Security key not ready for encryption");
        }

        // Generate a fresh 12-byte IV for every packet
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(CIPHER_GCM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

        // Include sequence number as Additional Authenticated Data (AAD)
        ByteBuffer aadBuffer = ByteBuffer.allocate(8);
        aadBuffer.putLong(sequenceNumber);
        cipher.updateAAD(aadBuffer.array());

        byte[] encryptedPayload = cipher.doFinal(pcmData, offset, length);

        // Output buffer: IV (12 bytes) + Encrypted Payload (includes GCM Tag)
        ByteBuffer outBuffer = ByteBuffer.allocate(GCM_IV_LENGTH_BYTES + encryptedPayload.length);
        outBuffer.put(iv);
        outBuffer.put(encryptedPayload);
        return outBuffer.array();
    }

    /**
     * Decrypts an audio packet payload using AES/GCM/NoPadding.
     * Validates GCM Authentication Tag and AAD sequence number.
     */
    public synchronized byte[] decryptVoiceFrame(long sequenceNumber, byte[] encryptedDataWithIv, int offset, int length) throws Exception {
        if (secretKey == null) {
            throw new IllegalStateException("Security key not ready for decryption");
        }
        if (length < GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)) {
            throw new IllegalArgumentException("Packet payload too short for AES-GCM");
        }

        // Check replay protection first
        if (!validateAndAdvanceSequence(sequenceNumber)) {
            throw new SecurityException("Replay attack or obsolete packet detected: seq=" + sequenceNumber);
        }

        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        System.arraycopy(encryptedDataWithIv, offset, iv, 0, GCM_IV_LENGTH_BYTES);

        int cipherOffset = offset + GCM_IV_LENGTH_BYTES;
        int cipherLength = length - GCM_IV_LENGTH_BYTES;

        Cipher cipher = Cipher.getInstance(CIPHER_GCM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

        ByteBuffer aadBuffer = ByteBuffer.allocate(8);
        aadBuffer.putLong(sequenceNumber);
        cipher.updateAAD(aadBuffer.array());

        return cipher.doFinal(encryptedDataWithIv, cipherOffset, cipherLength);
    }

    /**
     * Sliding window replay protection check.
     */
    public synchronized boolean validateAndAdvanceSequence(long sequenceNumber) {
        if (highestReceivedSeq == -1) {
            highestReceivedSeq = sequenceNumber;
            replayWindowBitmap = 1L;
            return true;
        }

        long diff = sequenceNumber - highestReceivedSeq;

        if (diff > 0) {
            // Newer packet
            if (diff >= REPLAY_WINDOW_SIZE) {
                replayWindowBitmap = 1L;
            } else {
                replayWindowBitmap = (replayWindowBitmap << diff) | 1L;
            }
            highestReceivedSeq = sequenceNumber;
            return true;
        }

        // Older or duplicate packet
        long negativeDiff = -diff;
        if (negativeDiff >= 64) {
            // Far too old
            return false;
        }

        long mask = 1L << negativeDiff;
        if ((replayWindowBitmap & mask) != 0) {
            // Already received (duplicate/replay)
            return false;
        }

        // Valid out-of-order packet within window
        replayWindowBitmap |= mask;
        return true;
    }

    public synchronized void resetReplayProtection() {
        highestReceivedSeq = -1;
        replayWindowBitmap = 0;
    }

    /**
     * Clears all session keys from memory securely.
     */
    public synchronized void destroy() {
        if (sessionKeyBytes != null) {
            Arrays.fill(sessionKeyBytes, (byte) 0);
            sessionKeyBytes = null;
        }
        secretKey = null;
        resetReplayProtection();
    }

    /**
     * Helper for constant-time comparison to prevent timing side-channel attacks.
     */
    private static class MessageDigestUtils {
        public static boolean constantTimeEquals(byte[] a, byte[] b) {
            if (a == null || b == null) {
                return a == b;
            }
            if (a.length != b.length) {
                return false;
            }
            int result = 0;
            for (int i = 0; i < a.length; i++) {
                result |= a[i] ^ b[i];
            }
            return result == 0;
        }
    }
}
