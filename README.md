# InterLink V1 — Offline Motorcycle Intercom for Android

**InterLink** is a native, offline, full-duplex motorcycle intercom application engineered in Java for Android smartphones. It allows exactly two riders (or rider and passenger) to establish hands-free, continuous two-way voice communication over local Wi-Fi or a mobile hotspot without cellular data, internet access, cloud servers, or user accounts.

---

## 1. System Architecture

InterLink follows a clean modular architecture separating concerns across dedicated subsystems:

```
                      ┌──────────────────────────────────────┐
                      │    UI Layer (XML + ViewModels)       │
                      │   MainActivity  •  SessionActivity   │
                      └──────────────────┬───────────────────┘
                                         │ Observers / Actions
                      ┌──────────────────┴───────────────────┐
                      │       IntercomService                │
                      │  (Foreground Service: Microphone)    │
                      └──────────────────┬───────────────────┘
                                         │
                      ┌──────────────────┴───────────────────┐
                      │          SessionManager              │
                      │   (Lifecycle & Audio-Network Bridge) │
                      └─────────┬───────────────────┬────────┘
                                │                   │
       ┌────────────────────────┴───────┐   ┌───────┴───────────────────────┐
       │         Audio Subsystem        │   │       Network Subsystem       │
       │  MicrophoneCapture (AudioRecord│   │  ControlChannel (TCP Handshake│
       │  VoicePlayback     (AudioTrack)│   │  VoiceTransport (UDP Datagrams│
       │  JitterBuffer      (Playout)   │   │  HostDiscovery  (UDP Beacons) │
       │  BluetoothAudioManager (SCO/BLE│   │  PacketProtocol (Wire Format) │
       └────────────────┬───────────────┘   └───────┬───────────────────────┘
                        │                           │
                        └───────────────┬───────────┘
                                        │
                      ┌─────────────────┴──────────────────┐
                      │          SessionSecurity           │
                      │   PBKDF2-HMAC-SHA256 Key Derivation│
                      │   AES-256-GCM AEAD Voice Encryption│
                      │   Sequence-based Replay Protection │
                      └────────────────────────────────────┘
```

---

## 2. Key Technical Specifications

| Parameter | Specification | Details |
|---|---|---|
| **Platform** | Native Android (Java only) | Min SDK: API 31 (Android 12), Target SDK: API 34 |
| **Session Control** | TCP Sockets | Handshake, Mutual Auth, Heartbeats, State Sync |
| **Voice Transport** | UDP Sockets | Low-latency Datagrams, Monotonic Sequence Numbers |
| **Audio Format** | 16-bit Mono PCM @ 16 kHz | 20 ms frame duration (640 bytes raw PCM per packet) |
| **Hardware Preprocessing** | AcousticEchoCanceler & NoiseSuppressor | Automatically enabled via `AudioSource.VOICE_COMMUNICATION` |
| **Audio Routing** | `AudioManager.setCommunicationDevice` | Modern API 31+ routing for Bluetooth SCO & BLE Headsets |
| **Playout Buffer** | Adaptive Jitter Buffer | Target depth: 40–60 ms; Packet Loss Concealment (PLC) |
| **Security & Cipher** | AES-256-GCM (Authenticated Encryption) | PBKDF2-derived master key from 6-digit PIN + random salt |
| **Replay Protection** | 128-packet sliding sequence window | Rejects duplicate or expired nonces |
| **Background Mode** | Foreground Service (`microphone` type) | High-contrast ongoing notification, Partial WakeLock |

---

## 3. Network & Cryptographic Wire Protocol

### 3.1 UDP Voice Datagram Framing (24-byte Header + Encrypted Payload)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                  Magic: 'I' 'N' 'T' 'L' (0x494E544C)          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Version (1)  |   Type (0x01) |       Reserved Flags (0x00)   |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                    Sequence Number (64-bit int)               +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                      Timestamp (64-bit ms)                    +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                 12-byte GCM Nonce (Initialization Vector)     |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|     Encrypted PCM Audio Payload (640 bytes) + 16-byte Tag     |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

Total datagram size is approximately **692 bytes**, well under standard Ethernet/Wi-Fi MTU (1500 bytes), guaranteeing zero IP fragmentation.

### 3.2 TCP Control Protocol Flow

```
Guest Phone                                              Host Phone
    │                                                        │
    ├───────────── HANDSHAKE_INIT (Client Model) ───────────►│
    │                                                        │
    │◄──────────── AUTH_CHALLENGE (Salt + HostChallenge) ────┤
    │                                                        │
[Derive Key via PBKDF2]                     [Derive Key via PBKDF2]
[Compute HMAC(HostChallenge)]                                │
    │                                                        │
    ├───────────── AUTH_RESPONSE (HMAC + GuestChallenge) ───►│
    │                                                        │
    │                                               [Verify Guest HMAC]
    │                                               [Compute HMAC(GuestChallenge)]
    │                                                        │
    │◄──────────── AUTH_SUCCESS (HostHMAC + VoicePort) ──────┤
    │                                                        │
[Verify Host HMAC]                                           │
    │                                                        │
    │◄══════════════ Full-Duplex Encrypted Audio ═══════════►│
    │                                                        │
    ├───────────── HEARTBEAT (Timestamp) ───────────────────►│
    │◄──────────── HEARTBEAT_ACK (Echo Timestamp) ───────────┤
```

---

## 4. Rider Setup & Pairing Guide

### Phase 1: Local Network Connection (Offline)
1. **Host Rider**: Turn on Android **Personal Hotspot** in Android Settings. No SIM card or mobile data is necessary.
2. **Guest Rider**: In Android Wi-Fi settings, connect to the Host's Hotspot network. (If Android displays "Wi-Fi has no internet access", tap **Stay Connected**).

### Phase 2: InterLink Pairing
1. **Host Rider**: Launch InterLink and tap **CREATE SESSION (HOST)**.
   - The screen displays the Host's Local IP (e.g., `192.168.43.1`) and a 6-digit PIN (e.g., `8 4 9 2 0 1`).
2. **Guest Rider**: Launch InterLink and tap **JOIN SESSION (GUEST)**.
   - If nearby discovery is active, tap the detected host. Otherwise, verify the pre-populated Gateway IP matches the Host IP.
   - Enter the 6-digit PIN and tap **CONNECT TO RIDER**.
3. **Session Active**: Within milliseconds, both devices mutually authenticate, exchange UDP ports, and switch to the **Active Intercom** screen. Full-duplex hands-free voice is now active.

---

## 5. Bluetooth Helmet Headset Integration

InterLink uses Android 12+ `AudioManager.setCommunicationDevice()` to route bidirectional voice through helmet communication systems:

- **Supported Systems**: Cardo (Packtalk / Freecom), Sena (50S, 50R, 30K, SMH5), Lexin, FreedConn, and standard Bluetooth HFP / BLE Audio headsets.
- **Microphone Route**: Helmet boom or chin microphone via Bluetooth SCO / HFP.
- **Speaker Route**: Helmet in-ear or cheek-pad speakers.
- **Route Selection**: Tap the **Change Route** button on the Active Intercom screen to switch between Bluetooth Helmet, Phone Speaker, or Earpiece.
- **Disconnection Handling**: If a Bluetooth headset battery dies or disconnects, InterLink dynamically falls back to internal phone audio without dropping the intercom session.

---

## 6. Background & Screen-Off Operation

- **Microphone Foreground Service**: InterLink runs `IntercomService` declared with `android:foregroundServiceType="microphone"` in compliance with Android while-in-use security policies.
- **Screen Locking**: When phones are placed in motorcycle tank bags or pockets and screens are turned off, audio capture and playback continue uninterrupted.
- **Partial WakeLock**: Held exclusively during active sessions to ensure the CPU is not suspended by Android doze mode.
- **Notification Controls**: The persistent lock screen notification provides instant **Mute/Unmute** and **End Session** buttons without having to unlock the device.

---

## 7. Spotify Coexistence

- InterLink requests transient communication audio focus (`AUDIOFOCUS_GAIN_TRANSIENT`) with speech content type.
- When Spotify is playing music in the background:
  - Depending on helmet headset profile capabilities, music may duck (lower volume) or pause while speech is active.
  - On single-channel Bluetooth HFP devices, music may pause because the headset switches from A2DP stereo to 16 kHz SCO communication mode.
- InterLink does not intercept or transmit third-party music to preserve intercom latency and battery efficiency.

---

## 8. Android Studio Build & Installation Instructions

### Prerequisites
- Android Studio Iguana, Jellyfish, or newer.
- JDK 17 or JDK 21.
- Android SDK Platform 34 and Build-Tools 34.0.0.

### Building via Android Studio
1. Open Android Studio.
2. Select **File > Open...** and navigate to `c:\xampp\htdocs\App\InterLink`.
3. Allow Gradle to sync dependencies.
4. Connect an Android phone via USB (with USB Debugging enabled) or choose an emulator.
5. Click **Run > Run 'app'** (`Shift + F10`).

### Building APK via Command Line (Windows)
Open PowerShell or Command Prompt in the project folder:

```powershell
cd C:\xampp\htdocs\App\InterLink

# Build Debug APK
.\gradlew.bat assembleDebug

# Output APK path:
# app\build\outputs\apk\debug\app-debug.apk
```

Install on connected phones using ADB:
```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 9. Verification & Testing Summary

Unit tests have been executed on the cryptographic and audio framing engine:

```text
JUnit version 4.13.2
.[D/SessionSecurity] AES-256 session key derived successfully
.[D/SessionSecurity] AES-256 session key derived successfully
........
Time: 1.75s

OK (15 tests)
```

Verified Test Scenarios:
1. **Pairing PIN Generation**: 6-digit numeric randomness verified.
2. **Mutual HMAC Authentication**: Valid PIN passes; incorrect PIN immediately rejected.
3. **AES-256-GCM Round Trip**: Encrypted 640-byte audio payloads match original PCM.
4. **AEAD Tamper Detection**: Modified ciphertext or sequence numbers rejected with tag error.
5. **Replay Attack Resistance**: Duplicate packet sequences rejected by sliding window bitmap.
6. **JitterBuffer In-Order Playout**: Continuous 20 ms playout without jitter.
7. **JitterBuffer Out-of-Order Reassembly**: Out-of-sequence UDP arrivals reordered correctly.
8. **Packet Loss Concealment (PLC)**: Missing frames filled with comfort silence to prevent audio pops.
9. **Duplicate Discarding**: Network echo packets dropped before buffer queueing.
10. **Packet Protocol Serialization**: Wire framing matches big-endian specification.

*Note on Hardware Testing: While cryptographic and jitter buffer logic has passed automated unit test validation, physical Bluetooth helmet communication (Cardo/Sena) and battery consumption under 70+ mph wind conditions must be validated on physical motorcycle hardware.*

