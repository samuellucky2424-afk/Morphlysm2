# Morphly streaming and virtual-camera architecture

## What is already in the repository

`android_virtual_cam` is an Xposed module. Its camera hooks substitute frames
decoded from a local `virtual.mp4`; it is not a live virtual camera device and
does not currently receive Decart frames. BlackBox already supplies the other
important half: it can install and launch apps in independent virtual users.

That means the app should be built as a controlled bridge inside the BlackBox
environment, not as a second independent Android app that happens to share a
file.

## Target flow

```text
Sign in → upload image → create protected source asset → reserve credits
        → create Decart realtime session → receive Decart output frames
        → publish frames to the selected clone slot → launch clone app
        → cloned app reads that slot as its camera source
```

The running-status mini window belongs to the host app. It shows stream status,
the assigned clone slot, an elapsed timer, a **Launch clone app** action, and a
**Stop** action. It is opened as soon as the stream reaches `running`, and it
is minimized when the clone app comes to the foreground.

## Frame bridge

The static MP4 decoder needs to become a `FrameSource` abstraction:

```text
Decart remote WebRTC VideoTrack
  → host-side VideoSink (I420/NV21 frame)
  → per-session shared-memory ring buffer
  → host ContentProvider returns a read-only descriptor to the selected clone
  → injected camera hook reads the newest frame at the requested camera size
  → Camera1/Camera2 callbacks receive a correctly sized NV21 frame
```

Each active stream has a random session token, clone slot (0–99), target width,
height, and a monotonically increasing frame counter. The ring buffer avoids
re-reading an MP4 or writing a file for every frame. It also makes a stopped
stream explicit: the hook should return a neutral placeholder or the real
camera, according to the user’s chosen setting, rather than blocking in a
`while (data_buffer == null)` loop.

The provider must validate both the virtual user/clone slot and the unguessable
session token. It should expose only a read-only file descriptor; cloned apps
must never be able to publish frames or access another user’s stream.

## Important Decart constraint

The supplied Decart Android quick start transforms a **local WebRTC camera
track** and returns a remote video track. A single uploaded image is not, by
itself, a camera track. The Android implementation therefore needs one of these
two approved source paths:

1. A custom WebRTC capturer that continually emits the selected image as video
   frames (with the required crop/rotation), then sends it to Decart.
2. The Decart image-input endpoint/model specified in the current project
   documentation, if the account has one.

We will choose the documented path once the Decart token and image-input API
details are confirmed. A permanent Decart key stays in the backend; the APK
receives a short-lived token only.

## Clone slots

BlackBox users map naturally to the requested 100 virtual-camera accounts:

```text
BlackBox user 0..99 ↔ Morphly clone slot 0..99 ↔ one active camera stream
```

The database enforces one active session per signed-in user and clone slot.
The Android interface should display only the slots that contain a cloned app,
while retaining the ability to add up to 100 slots.

## Lifecycle and credits

1. The user starts a stream from an uploaded image.
2. The backend atomically reserves the configured starting credits and creates
   the `starting` stream session.
3. Android connects Decart and starts the frame bridge.
4. Android marks the session `running`, shows the mini window, and may launch
   the clone immediately.
5. If connection/startup fails before `running`, the reservation is refunded.
6. Stop/failure closes the camera bridge before the clone is launched again.

This is intentionally a simple start-credit policy. Duration-based charging can
be layered on later with server-side metering; it must not be calculated solely
on the device.
