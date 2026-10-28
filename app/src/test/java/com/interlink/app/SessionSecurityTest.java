package com.interlink.app;

import com.interlink.app.security.SessionSecurity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SessionSecurityTest {

    private SessionSecurity hostSecurity;
    private SessionSecurity guestSecurity;
    private String testPin;
    private byte[] testSalt;

    @Before
    public void setUp() throws Exception {
        hostSecurity = new SessionSecurity();
        guestSecurity = new SessionSecurity();
        testPin = "592814";
        testSalt = hostSecurity.generateSalt();

        hostSecurity.initializeSessionKey(testPin, testSalt);
        guestSecurity.initializeSessionKey(testPin, testSalt);
    }

    @After
    public void tearDown() {
        hostSecurity.destroy();
        guestSecurity.destroy();
    }

    @Test
    public void testPinGeneration() {
        String pin = SessionSecurity.generatePairingPin();
        assertNotNull(pin);
        assertEquals(6, pin.length());
        int pinVal = Integer.parseInt(pin);
        assertTrue(pinVal >= 100000 && pinVal <= 999999);
    }

    @Test
    public void testMutualHmacAuthenticationSuccess() throws Exception {
        byte[] challenge = hostSecurity.generateChallenge();
        byte[] guestHmac = guestSecurity.computeHmac(challenge);

        assertTrue("Host should successfully verify Guest HMAC with identical PIN",
                hostSecurity.verifyHmac(challenge, guestHmac));
    }

    @Test
    public void testMutualHmacAuthenticationFailureWrongPin() throws Exception {
        SessionSecurity attackerSecurity = new SessionSecurity();
        attackerSecurity.initializeSessionKey("000000", testSalt);

        byte[] challenge = hostSecurity.generateChallenge();
        byte[] attackerHmac = attackerSecurity.computeHmac(challenge);

        assertFalse("Host must reject HMAC computed with wrong PIN",
                hostSecurity.verifyHmac(challenge, attackerHmac));

        attackerSecurity.destroy();
    }

    @Test
    public void testVoiceFrameEncryptionDecryptionRoundTrip() throws Exception {
        byte[] originalPcm = new byte[640];
        for (int i = 0; i < originalPcm.length; i++) {
            originalPcm[i] = (byte) (i % 128);
        }

        long seq = 1001;
        byte[] encrypted = hostSecurity.encryptVoiceFrame(seq, originalPcm, 0, originalPcm.length);
        assertNotNull(encrypted);
        assertTrue(encrypted.length > originalPcm.length); // Includes IV + Tag

        byte[] decrypted = guestSecurity.decryptVoiceFrame(seq, encrypted, 0, encrypted.length);
        assertArrayEquals("Decrypted PCM must match original", originalPcm, decrypted);
    }

    @Test
    public void testTamperedCiphertextRejected() throws Exception {
        byte[] originalPcm = new byte[640];
        long seq = 1002;
        byte[] encrypted = hostSecurity.encryptVoiceFrame(seq, originalPcm, 0, originalPcm.length);

        // Tamper with one byte in the ciphertext
        encrypted[encrypted.length - 1] ^= 0xFF;

        try {
            guestSecurity.decryptVoiceFrame(seq, encrypted, 0, encrypted.length);
            fail("Decryption must throw exception when payload is tampered");
        } catch (Exception expected) {
            // Success
        }
    }

    @Test
    public void testReplayAttackProtection() throws Exception {
        byte[] originalPcm = new byte[640];
        long seq = 50;

        byte[] encrypted = hostSecurity.encryptVoiceFrame(seq, originalPcm, 0, originalPcm.length);

        // First decryption succeeds
        guestSecurity.decryptVoiceFrame(seq, encrypted, 0, encrypted.length);

        // Replaying the exact same packet sequence must fail
        try {
            guestSecurity.decryptVoiceFrame(seq, encrypted, 0, encrypted.length);
            fail("Replayed packet with duplicate sequence must be rejected");
        } catch (SecurityException expected) {
            // Success
        }
    }

    @Test
    public void testSequenceWindowProgression() {
        SessionSecurity security = new SessionSecurity();
        assertTrue(security.validateAndAdvanceSequence(10));
        assertTrue(security.validateAndAdvanceSequence(11));
        assertTrue(security.validateAndAdvanceSequence(15)); // Jump ahead
        assertTrue(security.validateAndAdvanceSequence(12)); // Reordered packet within window
        assertFalse(security.validateAndAdvanceSequence(10)); // Duplicate
        assertFalse(security.validateAndAdvanceSequence(12)); // Duplicate
    }
}

