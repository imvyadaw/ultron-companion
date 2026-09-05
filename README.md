# ULTRON Android Companion

The Android companion is the phone-side client for ULTRON's `cross_device` runtime.

## What is wired

- Explicit 6-digit pairing against `cross_device.companion_api` on TCP `8766`.
- Authenticated WebSocket session against `cross_device.websocket_server` on TCP `8765`.
- ULTRON chat routed through `cross_device.chat_router` into the same assistant loop.
- Full remote-tool catalog from `ai.tool_runtime` with server-side destructive-action safeguards.
- Typed JSON command arguments (numbers, booleans, arrays and objects are preserved).
- Automatic reconnect with exponential backoff.
- Command-catalog refresh and PC host-status diagnostics.
- Android speech recognition for one-shot voice questions and TTS playback for replies.
- Optional Eyes & Ears service: camera JPEG frames + PCM16 microphone chunks.
- Optional location service: fused GPS updates and named-place detection on the PC.
- Encrypted local storage for the PC address, paired device ID and token.

## Pairing

1. On the PC, ask ULTRON to start pairing (the `pair_new_device` tool) and obtain the 6-digit code.
2. Put the PC's LAN IP or Tailscale IP in the app.
3. Enter the six-digit code and pair.
4. The app stores the issued device token in Android encrypted preferences.
5. The foreground connection service maintains the authenticated WebSocket.

The PC must be reachable from the phone. The ULTRON runtime defaults the HTTP and WebSocket listeners to `0.0.0.0`; use a firewall/VPN such as Tailscale rather than exposing ports directly to the public internet.

## Live phone sensing

These are opt-in foreground services. They do not start automatically.

- **Eyes & Ears:** requires CAMERA and RECORD_AUDIO. Camera frames are sent as actual JPEG data; audio is PCM16 mono at 16 kHz in short chunks.
- **Location:** requires fine or coarse location permission and sends a fused location fix about every 30 seconds.

The ULTRON side consumes these through `cross_device.live_sense` and `cross_device.location_tracker`. The AI tools `phone_camera_see`, `phone_mic_transcript`, and `phone_location_get` read that state.

## Build

Open the project in Android Studio or run:

```text
./gradlew assembleDebug
```

The supplied environment cannot perform an Android Gradle build without downloading the Gradle 8.9 distribution, so the source was statically checked here and the ULTRON `cross_device` Python package passes `py_compile`. Run the Gradle build on a machine with Android/Gradle dependencies available before shipping the APK.
