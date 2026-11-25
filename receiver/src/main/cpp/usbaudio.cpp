// Direct USB Audio output: streams PCM to a USB DAC's isochronous OUT endpoint through the Linux
// usbfs interface on the file descriptor Android's UsbDeviceConnection hands us. No Android mixer,
// no resampling, no volume scaling: the bytes written here are the bytes the DAC receives.
//
// The Kotlin side (UsbDac.kt) claims the interfaces, picks the alternate setting, sets the DAC's
// clock rate and volume with control transfers, then starts this engine.
//
// Timing: the endpoints this targets are synchronous or adaptive (the DAC follows the USB bus
// clock), so every packet carries exactly rate / packetsPerSecond frames on average, spread with a
// fractional accumulator (44.1 kHz at 8000 packets/s is 5, 5, 6, 5, 6, ... frames).

#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sched.h>
#include <cstdio>
#include <unistd.h>
#include <pthread.h>
#include <atomic>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <algorithm>
#include <ctime>
#include <android/log.h>

#define TAG "UsbAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Urb {
    usbdevfs_urb* urb = nullptr;
    uint8_t* buffer = nullptr;
    int frames = 0;  // frames of real audio (not silence) in this transfer
    uint32_t gen = 0;  // flush generation the audio belongs to
};

struct Engine {
    int fd = -1;
    int endpoint = 0;
    int frameBytes = 0;
    int rate = 0;
    int maxPacket = 0;
    int packetsPerSecond = 0;
    int packetsPerUrb = 0;
    double framesPerPacket = 0;
    double carry = 0;

    // Single producer (Java writer) / single consumer (streaming thread) ring buffer of whole frames.
    std::vector<uint8_t> ring;
    uint64_t capacity = 0;
    std::atomic<uint64_t> writePos{0};
    std::atomic<uint64_t> readPos{0};

    std::vector<Urb> urbs;
    std::atomic<bool> running{false};
    std::atomic<bool> paused{false};
    std::atomic<bool> flushRequested{false};
    std::atomic<uint64_t> flushPos{0};   // ring position where the audio after a flush starts
    std::atomic<uint32_t> gen{0};        // bumped on flush; older transfers no longer count as played
    bool flushing = false;
    // Short fades (5 ms) on pause, resume and seek, so stopping mid-sound never clicks. Steady playback is untouched.
    int fadeLen = 0;
    int level = 0;                       // 0 = silent, fadeLen = full sound
    int sampleBytes = 4;
    bool lastFull = false;               // the previous packet was full of audio
    std::atomic<int> inFlight{0};
    std::atomic<uint64_t> framesPlayed{0};
    std::atomic<uint64_t> underruns{0};
    std::atomic<int> lastError{0};
    std::atomic<uint64_t> latePackets{0};  // packets the controller could not send on time
    std::atomic<int> minInFlight{1 << 30};
    std::atomic<int64_t> maxGapUs{0};   // longest wait between collecting one transfer and the next
    std::atomic<int> priorityResult{0};
    std::atomic<int> cpuMask{0};
    int urbCount = 8;
    int cpuMode = 0;   // 0 = fastest tier only, 1 = any core, 2 = every tier except the fastest
    pthread_t thread{};
    bool threadStarted = false;
};

// Scales frames [0, n) of 32-bit samples along the fade, moving e->level one step per frame.
void ramp(Engine* e, uint8_t* data, int n, bool up) {
    int channels = e->frameBytes / 4;
    auto* s = reinterpret_cast<int32_t*>(data);
    for (int f = 0; f < n; f++) {
        if (up) { if (e->level < e->fadeLen) e->level++; } else { if (e->level > 0) e->level--; }
        if (e->level == e->fadeLen) continue;
        for (int c = 0; c < channels; c++) {
            int32_t& v = s[f * channels + c];
            v = static_cast<int32_t>(static_cast<int64_t>(v) * e->level / e->fadeLen);
        }
    }
}

// Fills one transfer's packets from the ring (or silence).
void fill(Engine* e, Urb& u) {
    if (e->flushRequested.exchange(false)) e->flushing = true;
    usbdevfs_urb* urb = u.urb;
    uint8_t* out = u.buffer;
    int total = 0;
    int real = 0;
    u.gen = e->gen.load();
    bool paused = e->paused.load();
    bool canFade = e->sampleBytes == 4 && e->fadeLen > 0;
    for (int p = 0; p < e->packetsPerUrb; p++) {
        e->carry += e->framesPerPacket;
        int frames = static_cast<int>(e->carry);
        e->carry -= frames;
        int bytes = frames * e->frameBytes;
        if (bytes > e->maxPacket) { frames = e->maxPacket / e->frameBytes; bytes = frames * e->frameBytes; }
        urb->iso_frame_desc[p].length = bytes;
        urb->iso_frame_desc[p].actual_length = 0;
        urb->iso_frame_desc[p].status = 0;
        uint8_t* dst = out + total;
        int copied = 0;
        bool wantSound = !paused && !e->flushing;
        // While fading out, only as many frames as the fade has left; without fades, stop at once.
        int allowed = wantSound ? frames : (canFade ? std::min(frames, e->level) : 0);
        if (allowed > 0) {
            uint64_t r = e->readPos.load(std::memory_order_relaxed);
            uint64_t w = e->flushing ? e->flushPos.load() : e->writePos.load(std::memory_order_acquire);
            uint64_t avail = w > r ? w - r : 0;
            uint64_t want = static_cast<uint64_t>(allowed) * e->frameBytes;
            uint64_t take = avail < want ? avail : want;
            take -= take % e->frameBytes;
            uint64_t off = r % e->capacity;
            uint64_t first = take < e->capacity - off ? take : e->capacity - off;
            memcpy(dst, e->ring.data() + off, first);
            if (take > first) memcpy(dst + first, e->ring.data(), take - first);
            e->readPos.store(r + take, std::memory_order_release);
            copied = static_cast<int>(take);
            if (canFade) ramp(e, dst, copied / e->frameBytes, wantSound);
            // A real gap: sound was flowing and ran out (planned silences after a seek or at the end do not count).
            if (wantSound && copied < bytes && e->lastFull) e->underruns.fetch_add(1);
        }
        if (!wantSound && (!canFade || e->level == 0 || copied == 0)) {
            e->level = 0;
            if (e->flushing) {
                // Old audio has faded away: skip to where the new audio starts.
                e->readPos.store(e->flushPos.load(), std::memory_order_release);
                e->flushing = false;
            }
        }
        e->lastFull = wantSound && copied == bytes;
        if (copied < bytes) memset(dst + copied, 0, bytes - copied);
        real += copied / e->frameBytes;
        total += bytes;
    }
    urb->buffer_length = total;
    u.frames = real;
}

bool submit(Engine* e, Urb& u) {
    fill(e, u);
    u.urb->status = 0;
    u.urb->actual_length = 0;
    if (ioctl(e->fd, USBDEVFS_SUBMITURB, u.urb) < 0) {
        e->lastError.store(errno);
        LOGE("submit failed: %s", strerror(errno));
        return false;
    }
    e->inFlight.fetch_add(1);
    return true;
}

void* streamLoop(void* arg) {
    auto* e = static_cast<Engine*>(arg);
    // Android lets apps use the urgent-audio nice level (-19) for their audio threads.
    e->priorityResult.store(setpriority(PRIO_PROCESS, gettid(), -19) == 0 ? 0 : errno);
    // Keep this thread on the fastest cores: the small power-saving cores nap long enough to empty the queue.
    {
        long maxFreq[16] = {0};
        long best = 0, second = 0;
        for (int c = 0; c < 16; c++) {
            char path[96];
            snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
            FILE* f = fopen(path, "r");
            if (!f) continue;
            if (fscanf(f, "%ld", &maxFreq[c]) != 1) maxFreq[c] = 0;
            fclose(f);
            if (maxFreq[c] > best) { second = best; best = maxFreq[c]; }
            else if (maxFreq[c] < best && maxFreq[c] > second) second = maxFreq[c];
        }
        cpu_set_t set;
        CPU_ZERO(&set);
        int chosen = 0;
        // Leave out the slowest tier when the chip has more than one (efficiency or base cores).
        long lowest = best;
        for (int c = 0; c < 16; c++) if (maxFreq[c] > 0 && maxFreq[c] < lowest) lowest = maxFreq[c];
        long floorFreq = lowest < best ? lowest + 1 : best;
        for (int c = 0; c < 16; c++) {
            if (maxFreq[c] <= 0) continue;
            bool pick = e->cpuMode == 1 || (e->cpuMode == 2 ? (maxFreq[c] < best || lowest == best) : maxFreq[c] >= floorFreq);
            if (pick) { CPU_SET(c, &set); chosen |= 1 << c; }
        }
        if (chosen && sched_setaffinity(0, sizeof set, &set) == 0) e->cpuMask.store(chosen);
    }
    timespec last{};
    clock_gettime(CLOCK_MONOTONIC, &last);
    for (auto& u : e->urbs) {
        if (!submit(e, u)) { e->running.store(false); break; }
    }
    while (e->inFlight.load() > 0) {
        usbdevfs_urb* done = nullptr;
        if (ioctl(e->fd, USBDEVFS_REAPURB, &done) < 0) {
            if (errno == EINTR) continue;
            e->lastError.store(errno);
            LOGE("reap failed: %s", strerror(errno));
            e->running.store(false);
            break;  // device gone: URBs are dead with it
        }
        timespec now{};
        clock_gettime(CLOCK_MONOTONIC, &now);
        int64_t gap = (now.tv_sec - last.tv_sec) * 1000000LL + (now.tv_nsec - last.tv_nsec) / 1000;
        last = now;
        if (e->running.load() && e->framesPlayed.load() > 0 && gap > e->maxGapUs.load()) e->maxGapUs.store(gap);
        int left = e->inFlight.fetch_sub(1) - 1;
        if (e->running.load() && left < e->minInFlight.load()) e->minInFlight.store(left);
        Urb* u = static_cast<Urb*>(done->usercontext);
        for (unsigned p = 0; p < done->number_of_packets; p++) {
            if (e->running.load() && done->iso_frame_desc[p].status != 0) e->latePackets.fetch_add(1);
        }
        if ((done->status == 0 || done->status == -EXDEV) && u->gen == e->gen.load()) e->framesPlayed.fetch_add(u->frames);
        if (e->running.load()) {
            if (!submit(e, *u)) e->running.store(false);
        }
    }
    LOGI("stream stopped (%d Hz: %llu gaps, %llu late packets, longest pause %.1f ms, queue %zu ms)",
         e->rate, static_cast<unsigned long long>(e->underruns.load()),
         static_cast<unsigned long long>(e->latePackets.load()), e->maxGapUs.load() / 1000.0, e->urbs.size());
    return nullptr;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_wifistream_receiver_UsbAudioNative_start(JNIEnv*, jclass, jint fd, jint endpoint, jint frameBytes, jint rate,
                                                 jint maxPacket, jint packetsPerSecond, jint bufferMs, jint urbCount, jint cpuMode) {
    auto* e = new Engine();
    e->cpuMode = cpuMode;
    e->fd = fd;
    e->endpoint = endpoint;
    e->frameBytes = frameBytes;
    e->rate = rate;
    e->maxPacket = maxPacket;
    e->packetsPerSecond = packetsPerSecond;
    e->framesPerPacket = static_cast<double>(rate) / packetsPerSecond;
    e->fadeLen = rate / 200;  // 5 ms
    e->sampleBytes = (frameBytes % 4 == 0 && frameBytes / 4 <= 8) ? 4 : 0;
    // 1 ms of packets per transfer (larger transfers stall the vivo's controller); urbCount in flight.
    e->packetsPerUrb = packetsPerSecond >= 8000 ? 8 : 1;
    e->urbCount = urbCount;
    uint64_t frames = static_cast<uint64_t>(rate) * bufferMs / 1000;
    e->capacity = frames * frameBytes;
    e->ring.assign(e->capacity, 0);
    for (int i = 0; i < urbCount; i++) {
        Urb u;
        size_t size = sizeof(usbdevfs_urb) + e->packetsPerUrb * sizeof(usbdevfs_iso_packet_desc);
        u.urb = static_cast<usbdevfs_urb*>(calloc(1, size));
        u.buffer = static_cast<uint8_t*>(calloc(1, static_cast<size_t>(e->packetsPerUrb) * maxPacket));
        u.urb->type = USBDEVFS_URB_TYPE_ISO;
        u.urb->endpoint = static_cast<unsigned char>(endpoint);
        u.urb->flags = USBDEVFS_URB_ISO_ASAP;
        u.urb->buffer = u.buffer;
        u.urb->number_of_packets = e->packetsPerUrb;
        e->urbs.push_back(u);
    }
    for (auto& u : e->urbs) u.urb->usercontext = &u;
    e->running.store(true);
    if (pthread_create(&e->thread, nullptr, streamLoop, e) != 0) {
        e->running.store(false);
        LOGE("thread start failed");
    } else {
        e->threadStarted = true;
        // Audio must not wait behind other work.
        sched_param sp{};
        sp.sched_priority = 2;
        pthread_setschedparam(e->thread, SCHED_FIFO, &sp);
    }
    LOGI("start: ep 0x%x, %d Hz, %d bytes/frame, max packet %d, %d packets/s", endpoint, rate, frameBytes, maxPacket, packetsPerSecond);
    return reinterpret_cast<jlong>(e);
}

// Copies as many whole frames as fit; returns the number of bytes taken.
JNIEXPORT jint JNICALL
Java_com_wifistream_receiver_UsbAudioNative_write(JNIEnv* env, jclass, jlong handle, jbyteArray data, jint offset, jint length) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e || !e->running.load()) return -1;
    uint64_t w = e->writePos.load(std::memory_order_relaxed);
    uint64_t r = e->readPos.load(std::memory_order_acquire);
    uint64_t space = e->capacity - (w - r);
    uint64_t take = static_cast<uint64_t>(length) < space ? static_cast<uint64_t>(length) : space;
    take -= take % e->frameBytes;
    if (take == 0) return 0;
    uint64_t off = w % e->capacity;
    uint64_t first = take < e->capacity - off ? take : e->capacity - off;
    jbyte* src = env->GetByteArrayElements(data, nullptr);
    memcpy(e->ring.data() + off, src + offset, first);
    if (take > first) memcpy(e->ring.data(), src + offset + first, take - first);
    env->ReleaseByteArrayElements(data, src, JNI_ABORT);
    e->writePos.store(w + take, std::memory_order_release);
    return static_cast<jint>(take);
}

// Frames written but not yet sent to the DAC (in the ring) plus those queued in the controller.
JNIEXPORT jlong JNICALL
Java_com_wifistream_receiver_UsbAudioNative_queuedFrames(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e) return 0;
    uint64_t inRing = (e->writePos.load() - e->readPos.load()) / e->frameBytes;
    uint64_t inUsb = static_cast<uint64_t>(e->inFlight.load()) * e->packetsPerUrb * static_cast<uint64_t>(e->framesPerPacket + 0.5);
    return static_cast<jlong>(inRing + inUsb);
}

JNIEXPORT jlong JNICALL
Java_com_wifistream_receiver_UsbAudioNative_framesPlayed(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    return e ? static_cast<jlong>(e->framesPlayed.load()) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_wifistream_receiver_UsbAudioNative_underruns(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    return e ? static_cast<jlong>(e->underruns.load()) : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_wifistream_receiver_UsbAudioNative_isRunning(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    return e && e->running.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_wifistream_receiver_UsbAudioNative_latePackets(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    return e ? static_cast<jlong>(e->latePackets.load()) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_wifistream_receiver_UsbAudioNative_maxGapUs(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    return e ? static_cast<jlong>(e->maxGapUs.load()) : 0;
}

JNIEXPORT jint JNICALL
Java_com_wifistream_receiver_UsbAudioNative_priorityResult(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    return e ? e->priorityResult.load() : -1;
}

JNIEXPORT jint JNICALL
Java_com_wifistream_receiver_UsbAudioNative_cpuMask(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    return e ? e->cpuMask.load() : 0;
}

JNIEXPORT jint JNICALL
Java_com_wifistream_receiver_UsbAudioNative_minInFlight(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    return e ? e->minInFlight.load() : 0;
}

JNIEXPORT jint JNICALL
Java_com_wifistream_receiver_UsbAudioNative_lastError(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    return e ? e->lastError.load() : 0;
}

JNIEXPORT void JNICALL
Java_com_wifistream_receiver_UsbAudioNative_setPaused(JNIEnv*, jclass, jlong handle, jboolean paused) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (e) e->paused.store(paused == JNI_TRUE);
}

// Drops everything written so far (after a short fade) and restarts the played-frames count.
JNIEXPORT void JNICALL
Java_com_wifistream_receiver_UsbAudioNative_flush(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e) return;
    e->flushPos.store(e->writePos.load());
    e->gen.fetch_add(1);
    e->framesPlayed.store(0);
    e->flushRequested.store(true);
}

// Frames written but not yet taken by the streaming thread.
JNIEXPORT jlong JNICALL
Java_com_wifistream_receiver_UsbAudioNative_ringFrames(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    return e ? static_cast<jlong>((e->writePos.load() - e->readPos.load()) / e->frameBytes) : 0;
}

JNIEXPORT void JNICALL
Java_com_wifistream_receiver_UsbAudioNative_stop(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e) return;
    e->running.store(false);
    for (auto& u : e->urbs) ioctl(e->fd, USBDEVFS_DISCARDURB, u.urb);  // EINVAL for ones already done: fine
    if (e->threadStarted) pthread_join(e->thread, nullptr);
    for (auto& u : e->urbs) { free(u.buffer); free(u.urb); }
    delete e;
}

// Resets the DAC (like unplugging and replugging it, but it stays the same device for Android), so it
// comes back to Android in its power-on state and Android's audio driver sets it up from scratch.
JNIEXPORT jint JNICALL
Java_com_wifistream_receiver_UsbAudioNative_resetDevice(JNIEnv*, jclass, jint fd) {
    int r = ioctl(fd, USBDEVFS_RESET, 0);
    return r < 0 ? -errno : 0;
}

// Hands an interface back to the kernel's audio driver so Android can use the DAC again.
JNIEXPORT jint JNICALL
Java_com_wifistream_receiver_UsbAudioNative_reattachKernelDriver(JNIEnv*, jclass, jint fd, jint interfaceNumber) {
    usbdevfs_ioctl command{};
    command.ifno = interfaceNumber;
    command.ioctl_code = USBDEVFS_CONNECT;
    command.data = nullptr;
    int r = ioctl(fd, USBDEVFS_IOCTL, &command);
    return r < 0 ? -errno : 0;
}

}  // extern "C"
