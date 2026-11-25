# Direct (bit-perfect) USB DAC output on Android: build guide and findings

This is how Hassasin's Stream 1.6 plays audio straight to a USB DAC, bypassing Android's mixer, and everything learned building it. It is written so the same feature can be rebuilt in another app.

Hardware used throughout:

- **DAC:** Audiocular D07 (USB `3302:3395`, "TTGK Technology Co.,Ltd").
- **Earphones:** KZ Castor IEMs.
- **vivo X300 Ultra** (V2562, Android 17): the main test phone.
- **Samsung S21 FE** (SM-G990B2, Android 16): the second test phone.

Code lives in `receiver/src/main/`:

| File | Role |
|---|---|
| `cpp/usbaudio.cpp`, `cpp/CMakeLists.txt` | Native isochronous streaming engine (raw usbfs, no libusb) |
| `kotlin/.../UsbDac.kt` | Descriptor parsing (`DacLayout`), the DAC session (`UsbDacSession`), JNI bindings, the tone test activity |
| `kotlin/.../UsbDacProbe.kt` | Read-only descriptor report and probe activity |
| `kotlin/.../UsbAudioSink.kt` | Media3 `AudioSink` that feeds the engine; `UsbLive` shared state |
| `kotlin/.../StereoDownmix.kt`, `ChannelOrder.kt` | Downmix to stereo in any PCM format; channel reordering |
| `kotlin/.../UsbKeepAliveService.kt` | Foreground service and wake lock while direct output plays |
| `kotlin/.../UsbAttachActivity.kt`, `res/xml/usb_audio_devices.xml` | Takes Android's "open with" offer on plug-in, which grants USB access |
| `kotlin/.../UsbSinkTest.kt` | Test player: real player stack with direct output, scripted pause, seek and clock check |
| `PlayerScreen.kt` (Output panel, auto switching), `AudioFx.kt` (`AppRenderersFactory`), `LibraryStore.kt` (settings) | Integration |

---

## 1. Why the app's own USB driver was needed

- Android 14+ has an official bit-perfect path: `AudioManager.setPreferredMixerAttributes` with `MIXER_BEHAVIOR_BIT_PERFECT`. But it is **optional for phone makers**, and it only works when the audio HAL declares it.
- `dumpsys media.audio_policy` on both phones showed `mixerBehaviors: 0` on every USB-capable mix port. Neither the S21 FE nor the vivo offers bit-perfect.
- The S21 FE's USB output is the Qualcomm `hifi_playback` port: 32-bit, rates 32, 44.1, 48, 96, 192 and 384 kHz, "dynamic rates". Matching the file's rate through Android is possible there, but Android's mixer and volume still touch the samples.
- So the app drives the DAC itself over USB, like USB Audio Player Pro does.
- **Java's USB API can't do isochronous transfers**, which audio needs. `UsbRequest` covers only bulk and interrupt. So a small native module does them through usbfs ioctls on the file descriptor from `UsbDeviceConnection.getFileDescriptor()`. libusb was considered and dropped: raw usbfs is about 300 lines, needs no extra library, and avoids LGPL packaging.
- **Build tools:** NDK r29 (`29.0.14206865`) and CMake 3.31.6, installed with the command-line `sdkmanager`. In `build.gradle.kts`: `ndkVersion`, plus `externalNativeBuild { cmake { path = "src/main/cpp/CMakeLists.txt"; version = "3.31.6" } }`, with ABIs `arm64-v8a` and `armeabi-v7a`.

## 2. Reading the DAC (descriptors only, nothing sent)

- **Getting the descriptors:** `UsbManager.openDevice(device).rawDescriptors` returns the whole configuration. Parse it yourself. The app needs USB permission first.
- **Parsing a USB Audio Class 2 (UAC2) device** (see `DacLayout.parse`):
  - **Audio control interface:** class 1, subclass 1. Interface protocol `0x20` means UAC2.
  - **Input terminal** (CS_INTERFACE `0x24`, subtype `0x02`): terminal type `0x0101` is "USB streaming", meaning playback from the host. Its `bCSourceID` (byte 7) is the clock that drives playback.
  - **Feature unit** (subtype `0x06`) whose `bSourceID` (byte 4) is that terminal: `bmaControls` are 4 bytes per channel. Bits 0–1 are mute and bits 2–3 are volume; `0b11` means the host can set it.
  - **Clock source** (subtype `0x0A`): `bmControls` bits 0–1 equal to 3 means the rate can be set.
  - **Audio streaming interface** (class 1, subclass 2): its AS_GENERAL (subtype `0x01`) `bTerminalLink` (byte 3) matches the input terminal. `bNrChannels` is byte 10. FORMAT_TYPE (subtype `0x02`) has `bSubslotSize` (byte 4) and `bBitResolution` (byte 5).
  - **Endpoint:** an isochronous OUT data endpoint. Sync type is `(bmAttributes >> 2) & 3`: 1 = async, 2 = adaptive, 3 = sync. Max packet is `wMaxPacketSize & 0x7FF`, times `((wMaxPacketSize >> 11) & 3) + 1`.
- **What the D07 reported:**
  - UAC2 at high speed. Playback clock 9; clock 10 is for the mic.
  - Feature unit 2: settable mute, and settable volume on channels 1 and 2 (no master volume control). Its range, from GET_RANGE: **−74 to 0 dB in 0.5 dB steps**.
  - Playback alternate settings 1/2/3 = 16/24/32-bit stereo with 2/3/4-byte slots. Endpoint `0x01` OUT, **synchronous** (no feedback endpoint), max packet 192/288/384 bytes, bInterval 1. High speed means 8000 packets a second, so 384 kHz × 8 bytes ÷ 8000 = 384 bytes per packet.
  - It also has a mono mic input (interface 2, endpoint `0x81`, 8 kHz and up). That mic matters: see sections 9 and 10.

## 3. Taking the DAC from Android and controlling it (`UsbDacSession`)

- **Claim:** `claimInterface(intf, force = true)` on **every** audio-class interface. That detaches the kernel `snd-usb-audio` driver, so Android loses the card while the app owns it.
- **Class control requests** go through `UsbDeviceConnection.controlTransfer`. They only work while the interface is claimed. Before that, usbfs refuses because the kernel driver owns it.
- **Setting the rate:** with alt 0 selected, send SET_CUR CS_SAM_FREQ_CONTROL to the clock: request type `0x21`, request `0x01`, value `0x0100`, index `(clockId << 8) | acInterface`, 4 bytes little-endian Hz. Read it back with GET_CUR (`0xA1`, `0x01`). The D07 accepted 44.1, 48, 96 and 192 kHz exactly.
- **Volume:**
  - Read the range with GET_RANGE (`0xA1`, request `0x02`, value `(0x02 << 8) | channel`, index `(featureUnit << 8) | ac`). The reply is subrange count (2 bytes), then min, max and resolution as int16 in 1/256 dB.
  - Set it per channel with SET_CUR (`0x21`, `0x01`, the same value and index, int16 1/256 dB).
- **Mute:** SET_CUR with value `0x0100` and 1 byte. The D07 obeys it (checked by reading it back), but see section 7: don't use it for switching.
- **Starting a stream:** `setInterface(alt)` for the deepest format (always 32-bit here: 16-bit and 24-bit samples are shifted left, which stays bit-perfect), then start the native engine.
- **Handing back:**
  1. Stop the engine.
  2. Select alt 0.
  3. Restore the rate, volume and mute Android had.
  4. `releaseInterface` each interface, then the `USBDEVFS_IOCTL` + `USBDEVFS_CONNECT` ioctl per interface to reattach the kernel driver. Interface 0 returns `-16` (EBUSY) because the driver re-binds the whole card from another interface. That is harmless.
- **Restore Android's state on hand-back.** Android's driver caches the rate it set and does not resend it. If the DAC is left at another rate, Android's audio plays at the wrong speed (see section 10).
  - Read the volume and mute at claim time and put them back.
  - Give the **clock back at Android's mixer rate** (`AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE`, normally 48000), **not** the clock value read after claiming. Right after the claim, the D07 had already dropped to an idle 8 kHz, and restoring that made Android's output crawl at 8,000 ÷ 44,100 ≈ 0.18× speed.

## 4. The native engine (`usbaudio.cpp`)

- **The URB:** one `usbdevfs_urb` with `type = USBDEVFS_URB_TYPE_ISO`, `flags = USBDEVFS_URB_ISO_ASAP`, a buffer of `packets × maxPacket` bytes, and `number_of_packets` entries in `iso_frame_desc[]`. Submit with `USBDEVFS_SUBMITURB`, collect with blocking `USBDEVFS_REAPURB`, cancel with `USBDEVFS_DISCARDURB`.
- **Synchronous endpoint timing:** each packet carries `rate / 8000` frames on average, spread with a fractional accumulator. At 44.1 kHz that's 5.5125 frames per packet, which comes out as 5, 5, 6, 5, 6, …. No feedback endpoint is needed. Measured: 132,300 frames in 3.011 s, and the clock stays within ±5 ms over minutes.
- **Ring buffer:** single producer (the Java writer) and single consumer (the streaming thread), 500 ms, whole frames only. `write()` copies what fits and returns the byte count.
- **Played-frames counter:** counts frames from completed transfers. That's the audio clock the player uses for A/V sync.
- **Flush generations:** a flush bumps a generation number and zeroes the count. Transfers filled before the flush don't count when they complete. Without this, the position runs up to 30 ms ahead after a seek.
- **5 ms fades,** only at pause, resume and flush:
  - The ramp scales int32 samples while a pause or flush is pending.
  - A flush fades the old audio out, then skips the read position to where the new audio starts (`flushPos`).
  - Steady playback is never touched.
- **Gap counter:** counts a "gap" only when audio was flowing and ran out (`lastFull`). The planned silence after a seek or at the end of a file doesn't count.
- **Thread priority:**
  - `setpriority(PRIO_PROCESS, gettid(), -19)` (urgent audio) is allowed for apps.
  - `SCHED_FIFO` is refused.
  - Use `sched_setaffinity`, reading `/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq`. See section 6 for which cores.

## 5. Queue depth: the single most important number

The streaming thread must refill transfers before the controller runs out. Every millisecond the phone freezes the thread longer than the queued audio is an audible gap.

| Queue | Result on the vivo |
|---|---|
| 8 × 1 ms transfers (8 ms) | Glitches at 48 and 192 kHz. The thread was frozen 7–26 ms. |
| 16 ms | Better, but a 23 ms pause still broke through. |
| 24 × 1 ms (24 ms) | Clean with the screen on. |
| 28 and 30 ms | Work. |
| 32 × 1 ms, or 8 × 4 ms (32 ms either way) | **Stall immediately**: the DAC stops taking audio after about 40 ms. The vivo's USB controller can't hold about 256 queued packets, so the limit is about 30 ms. |
| **30 × 1 ms (chosen)** | Clean, including the screen-off moment in 10 of 10 runs (worst pause 27.6 ms). |

- Keep transfers at 1 ms (8 packets). The bigger 4 ms transfers stalled the vivo at the same total depth.
- **"Late packets" and "lowest queue" counters are blind to this.** When the queue runs dry, the next transfers simply start later, ISO_ASAP style, with no error status. Measure **the longest time between reaps** instead; that's the number that predicts gaps.

## 6. Thread placement and the screen-off hiccup

- **Pinning to the fastest cores:** the vivo has six 3.6 GHz cores and two 4.6 GHz cores (6 and 7). With the screen on, pinning to the fastest tier cut the worst pause from 11–26 ms to under 7 ms.
- **But pinning can silently fail.** If the app starts in Android's `background` cpuset (cores 0–5 on the vivo), `sched_setaffinity` to 6–7 is refused. **Final choice: any core** (`cpuMode = 1`), and rely on the 30 ms queue.
- **Screen off:** at the moment the screen turns off, the vivo froze the streaming thread for **125 ms**, which you hear as a crackle.

| Fix | Screen-off pause |
|---|---|
| None | 125 ms |
| ExoPlayer wake lock (`setWakeMode(WAKE_MODE_LOCAL)`) | 61 ms |
| Foreground service (type `mediaPlayback`) + partial wake lock, started whenever direct output plays | 15–38 ms (with the 24 ms queue) |
| Same, with the 30 ms queue | under 30 ms in 10 of 10 runs |

- About 1 s after screen-off, the vivo caps the big cores at 1.38 GHz. The cores stay online, and that speed is fine.
- **Comparing core choices at screen-off:**

| Core choice | Pauses over 3 runs |
|---|---|
| Fastest only | 15.6, 25.1, 33.6 ms |
| Base cores (0–5) only | 15.0, 11.9, 27.7 ms |
| Any core | 13.6, 22.1, 9.1 ms |

  The screen-off hiccup hits the whole process. Core choice doesn't fix it.
- **Foreground service crash:** if the service is stopped before it has called `startForeground`, Android kills the app with `ForegroundServiceDidNotStartInTimeException`. Hold an early stop in a flag and act on it after `startForeground`.
- **Not tried yet** (deliberately left for another app): run the refill from an AAudio low-latency data callback, which Android schedules at real-time priority, to remove the remaining screen-off risk.

## 7. Rate switching without pops

These were judged by ear on the Castor, and the order matters:

1. Cutting the stream mid-tone and changing the rate → click.
2. The DAC's hardware mute around the switch → a metallic "**ting**" at the unmute, on every switch after the first.
3. Gliding the DAC volume down and up → distortion.
4. **Silence only (chosen):** let the audio end (or fade it out), keep streaming silence about 100 ms, stop, change the clock and alt setting, stream silence about 100 ms, then play. Clean in every run.

On top of that, **pre-fill before playing:** after a start or a flush, hold the engine paused until 250 ms of audio is buffered. A 4K Dolby Vision film starting up starved the buffer (17 ms) and caused 5 restarts, heard as "playing and pausing repeatedly". With pre-fill: 0 gaps at the start and after seeks.

## 8. Feeding it from Media3 (`UsbAudioSink`)

- A `ForwardingAudioSink` around Android's `DefaultAudioSink`. Every call goes to Android's sink unless direct output is active, so falling back is automatic.
- **Format support:**
  - **Pure mode:** report PCM 16/24/32-bit and float as `SINK_FORMAT_SUPPORTED_DIRECTLY`. Decoders then hand over real 24-bit (logged as encoding 21), so FLAC stays bit-perfect.
  - **Keep-sound mode:** support 16-bit only, because the app's `SoundProcessor` handles only 16-bit.
  - Report compressed formats (AC3 and so on) as unsupported, so they get decoded.
- **`configure`:**
  - Build an `AudioProcessingPipeline`: `ChannelOrder(outputChannels)` if given, then the sound engine (keep mode only), then `StereoDownmix` if there are more or fewer than 2 channels.
  - Open the session.
  - If the rate changes, wait for the ring to drain (up to 1.5 s), then `start(rate)`.
  - If the DAC refuses the rate, use Android's sink.
- **Honour `outputChannels`.** The player passes a channel order. Ignoring it is one way dialogue ends up on the left.
- **Media3's `ChannelMixingAudioProcessor` only takes 16-bit.** With FFmpeg decoding Atmos/E-AC3 to **float**, it threw `UnhandledAudioFormatException`, and every film silently fell back to Android's output. `StereoDownmix` is the replacement:
  - It takes any PCM format, in the standard order L, R, C, LFE, BL, BR, SL, SR.
  - Centre and surrounds go into both sides at −3 dB, and LFE is dropped.
  - It normalises so it can't clip.
  - Proof the order is standard: measured channel levels in a dialogue scene were 32.1, 32.7, **64.4**, −90 (LFE), 22.8 and 23.1 dB. The loudest channel is the 3rd, the centre.
- **Writing:**
  - Convert the pipeline output to int32 LE, shifting 16/24-bit left and scaling float by 2³¹.
  - Hold what doesn't fit in a pending buffer.
  - Return `false` from `handleBuffer` while there's still pending data, so the player offers the same buffer again.
- **Position:** `startMediaUs` is set from the first buffer after configure or flush, plus `framesPlayed × 1e6 / rate`. When the engine restarts at a new rate, its count restarts at 0, so reset the sink's own frame counts too. (That bug froze the position at the boundary between files.)
- **Speed is fixed at 1×** in direct mode, since changing speed would mean resampling. Player volume is ignored, because the DAC's own volume is the master.
- **Unplug while playing:** a failed submit ends the stream. Don't throw from the sink. Set `UsbLive.lost`, return `false`, and let the player rebuild on Android's output.

## 9. Permission and plug-in workflow

- Requesting access with `UsbManager.requestPermission` shows a dialog each time. On the vivo it has no "Always" option. The permission is per plug-in and is revoked on unplug.
- **Better:** declare an activity with a `USB_DEVICE_ATTACHED` intent filter, plus meta-data pointing at `<usb-device class="1"/>`. Android's filter matches interface classes too. Android then offers the app when the DAC is plugged in, and launching that activity grants access. Here it's `UsbAttachActivity`: translucent, `noHistory`, finishes at once, and brings the player back to the front if a film was playing.
- **"Always" is greyed out for the D07** because it has a microphone. Android only lets apps holding `RECORD_AUDIO` be the default handler for audio-capture USB devices. So expect the chooser on every plug-in, unless the app asks for microphone permission (not done here).
- **USB reset** (`USBDEVFS_RESET`) on hand-back works as a "virtual replug", **but Android then forgets the app's access.** Not used.
- **The chooser and permission dialog cover the player.** That fires `onUserLeaveHint`, which entered picture-in-picture, and `ON_STOP`, which paused the film. Skip both while a DAC is attached without permission.
- **Final behaviour (Output = "Auto"):**
  - No DAC: normal Android sound.
  - DAC plugged in with access: the player moves to direct output at the same spot (a rebuild), even when paused, and stays paused.
  - Unplugged: the player moves back to Android's output at the same spot, without an error.
  - When the player starts with the DAC already allowed, the sink goes direct on its own, with no rebuild.
- **Rebuild pitfalls:**
  - Only rebuild once the player is READY. Rebuilding while it's still starting (before the resume seek) made the player think the file couldn't seek, and it went to the compatibility engine.
  - Count direct output as "mine" only if it started after this player was built, because a film switch overlaps with the previous player still holding the DAC. Have the new sink wait up to 2.5 s for the old session to close.
  - At most 2 tries per film, so a DAC that refuses a rate can't cause a rebuild loop.
  - Compose `pointerInput(Unit)` keeps the first player in its closures. Key gesture handlers by `player`, or taps and seeks act on a released player after a rebuild.

## 10. Android's own USB path after the app touches the DAC (open issue)

- **Symptom:** after the app has had the DAC (or even just been granted access), Android's normal USB output plays slow and choppy until a physical replug.
- **Measured:**
  - Android's USB mixer thread consumed about 8,560–8,760 frames a second instead of 48,000 (`dumpsys media.audio_flinger`, Timestamp stats `rate=0.18143`, which is exactly 8000/44100).
  - In another run, a mic pitch test gave 0.89× (an 880 Hz chord recorded at 786 Hz).
- **Cause:** Android's kernel driver keeps its cached sample rate and doesn't resend it, while the D07 sits at a different rate, including its idle 8 kHz.
- **Mitigations in 1.6:**
  - Restore the DAC to Android's mixer rate on hand-back.
  - With Auto output, a plugged-in DAC is always driven directly by the player, so Android's USB path isn't used while watching.
- **Not fully solved for other apps** after hand-back. Next ideas: a USB reset combined with the plug-in auto-grant, then re-measure.

## 11. Volume and controls

- In direct mode, Android's volume does nothing, so the player maps controls to the DAC's volume in dB (saved in settings):
  - Phone volume keys (`onPreviewKeyEvent`): 1 dB per press.
  - Right-side vertical swipe: 1 dB per 40 px.
  - Output panel slider: 0.5 dB steps.
- **Max volume cap** (default −10 dB) protects sensitive IEMs. The KZ Castor was comfortable around −30 to −40 dB.
- The player's left-side brightness swipe is now remembered across films.

## 12. How it was tested

- **Probe** (`UsbDacProbeActivity`): reads descriptors only.
- **Tone test** (`UsbDacTestActivity`, extras `--ei queue N --ei vol dB --ei switch 0/1/2`):
  - Plays 3 s at 1 kHz at 44.1, 96, 48 and 192 kHz.
  - Logs frames, time, gaps, longest pause and cores to `files/usb-dac-test.txt`.
  - Mode 1 (silence only) is the default. Mode 0 is the DAC mute (ting); mode 2 is the volume glide (distortion).
- **Player test** (`UsbSinkTestActivity`, extras `--es only a --ei cpu --ei queue --ei vol --ez direct`):
  - Plays FLAC files from `Android/data/<pkg>/files/usbtest/`.
  - On each file: pause 1 s at 2 s, jump back to 1 s at 4 s, then a clock check (drift was 0–7 ms).
  - Engine stats per stream go into `files/usb-sink-test.txt`.
- **Logs on the vivo:** its logcat is flooded and rotates in seconds. Stream filtered tags live to a file with `adb logcat -v time Tag:I '*:S' > file`, or write results to an app file.
- **Ears:** the user's ears on the Castor were the deciding test for clicks, the ting, distortion and channel balance.
- **Recording through a mic:** Voicemeeter Potato's Out B1 must be opened at **44.1 kHz** (48 kHz gives silence). The Bash and PowerShell sandboxes capture silence, so record outside them. An IEM against a headset mic is about −78 dB, too faint for click analysis, but fine for pitch.
- **Speed checks:** read the player's on-screen clock twice, 16 s apart, or record a chord and measure its pitch with an FFT.

## 13. Checklist for another app

1. NDK + CMake. Copy `usbaudio.cpp` (engine, fades, flush generations, gap and pause stats).
2. `DacLayout.parse` + `UsbDacSession` (claim all audio interfaces; set the clock at alt 0; volume per channel; hand back with the rate and volume restored, then reattach).
3. Defaults: **30 × 1 ms queue**, any core, nice −19, silence-only rate switching, 250 ms pre-fill.
4. The sink: honour `outputChannels`, use your own any-format downmix, int32 output, return `false` while the ring is full, and never throw on unplug.
5. Foreground service (`mediaPlayback`) + partial wake lock while streaming, with an early-stop guard.
6. `USB_DEVICE_ATTACHED` activity with an audio-class device filter, for access on plug-in. Expect the chooser every time for DACs with mics unless you hold `RECORD_AUDIO`.
7. Auto output: switch on attach (once the player is READY) and back on unplug, at the same position. Key gesture handlers by the player.
8. Test with tone sweeps at 44.1, 48, 96 and 192 kHz, screen-off runs, unplug and replug cycles, film switches, and a 4K start-up. Judge clicks by ear.
9. Next steps not done here:
   - An AAudio real-time callback to drive refills.
   - Asking for `RECORD_AUDIO` so "Always" works.
   - Fixing Android's own USB path after hand-back.
   - Async-feedback endpoints, which many other DACs use. The D07 is synchronous; an async DAC needs its feedback endpoint read and the frames per packet adjusted to match.
