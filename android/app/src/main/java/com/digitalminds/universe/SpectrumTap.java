package com.digitalminds.universe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * UNIVERSE :: SpectrumTap
 *
 * Turns the PCM audio the player is about to play into a spectrum that looks exactly like
 * Web Audio's getByteFrequencyData (Blackman window, FFT, 0.35 smoothing, -90..-10 dB mapped
 * to 0..255). The light show was tuned on that scale, so it keeps working unchanged.
 *
 * Pure Java on purpose: no Android classes, so it is unit tested on a plain JVM.
 * Threads: the audio thread calls configure() and write(); the analysis thread calls compute().
 */
final class SpectrumTap {

    static final int N = 1024;          // FFT size
    static final int BINS = N / 2;      // bytes per spectrum

    private static final int RING = 8192;   // mono samples kept (after decimation)
    private static final float SMOOTH = 0.35f;
    private static final float MIN_DB = -90f;
    private static final float MAX_DB = -10f;

    // PCM encodings as defined by androidx.media3.common.C (kept as numbers to stay Android free).
    private static final int PCM_8 = 3;
    private static final int PCM_16 = 2;
    private static final int PCM_16_BE = 0x10000000;
    private static final int PCM_24 = 21;
    private static final int PCM_32 = 22;
    private static final int PCM_FLOAT = 4;

    private final Object lock = new Object();
    private final float[] ring = new float[RING];
    private int head;
    private long written;
    private int channels = 2;
    private int encoding = PCM_16;
    private int decim = 1;
    private int rate = 44100;
    private float acc;
    private int accN;
    private volatile boolean clearSmooth;

    // Analysis scratch space (analysis thread only).
    private final float[] window = new float[N];
    private final float[] re = new float[N];
    private final float[] im = new float[N];
    private final float[] cosT = new float[N / 2];
    private final float[] sinT = new float[N / 2];
    private final float[] smooth = new float[BINS];
    private final int[] rev = new int[N];
    private long computedAt = -1;

    SpectrumTap() {
        for (int i = 0; i < N; i++) {
            // Blackman window, alpha 0.16, exactly as Web Audio defines it.
            double x = 2.0 * Math.PI * i / N;
            window[i] = (float) (0.42 - 0.5 * Math.cos(x) + 0.08 * Math.cos(2.0 * x));
        }
        for (int i = 0; i < N / 2; i++) {
            cosT[i] = (float) Math.cos(2.0 * Math.PI * i / N);
            sinT[i] = (float) Math.sin(2.0 * Math.PI * i / N);
        }
        int bits = Integer.numberOfTrailingZeros(N);
        for (int i = 0; i < N; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
    }

    /** Called whenever the audio stream (re)starts or the user seeks. Forgets old audio. */
    void configure(int sampleRate, int channelCount, int pcmEncoding) {
        synchronized (lock) {
            channels = Math.max(1, channelCount);
            encoding = pcmEncoding;
            // High resolution files (88.2 / 96 / 192 kHz) are reduced to about 48 kHz so bass keeps its resolution.
            decim = sampleRate > 52000 ? Math.max(1, Math.round(sampleRate / 48000f)) : 1;
            rate = Math.max(1, sampleRate / decim);
            head = 0;
            written = 0;
            acc = 0;
            accN = 0;
            computedAt = -1;
            clearSmooth = true;
        }
    }

    /** Effective sample rate of the spectrum (what bin 0..BINS-1 spans: 0 to rate / 2). */
    int rate() {
        synchronized (lock) {
            return rate;
        }
    }

    /** Audio thread: copy the samples into the ring. Never touches the player's buffer position. */
    void write(ByteBuffer pcm) {
        ByteBuffer b = pcm.duplicate();
        synchronized (lock) {
            int bytes;
            switch (encoding) {
                case PCM_8: bytes = 1; break;
                case PCM_16: case PCM_16_BE: bytes = 2; break;
                case PCM_24: bytes = 3; break;
                case PCM_32: case PCM_FLOAT: bytes = 4; break;
                default: return;                       // unknown format: stay silent instead of guessing
            }
            b.order(encoding == PCM_16_BE ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
            int frame = bytes * channels;
            while (b.remaining() >= frame) {
                float sum = 0f;
                for (int c = 0; c < channels; c++) sum += sample(b);
                acc += sum / channels;
                if (++accN >= decim) {
                    ring[head] = acc / accN;
                    head = (head + 1) % RING;
                    written++;
                    acc = 0f;
                    accN = 0;
                }
            }
        }
    }

    private float sample(ByteBuffer b) {
        switch (encoding) {
            case PCM_8: return ((b.get() & 0xFF) - 128) / 128f;
            case PCM_16: case PCM_16_BE: return b.getShort() / 32768f;
            case PCM_24: {
                int v = (b.get() & 0xFF) | ((b.get() & 0xFF) << 8) | (b.get() << 16);   // last byte carries the sign
                return v / 8388608f;
            }
            case PCM_32: return b.getInt() / 2147483648f;
            default: return b.getFloat();
        }
    }

    /**
     * Analysis thread: writes BINS bytes into out. Returns false when there is nothing new
     * to say (not enough audio yet, or playback is paused), so the caller can stay quiet.
     */
    boolean compute(byte[] out) {
        synchronized (lock) {
            if (written < N || written == computedAt) return false;
            int start = (head - N + RING) % RING;
            for (int i = 0; i < N; i++) re[i] = ring[(start + i) % RING] * window[i];
            computedAt = written;
        }
        if (clearSmooth) {
            Arrays.fill(smooth, 0f);
            clearSmooth = false;
        }
        Arrays.fill(im, 0f);
        fft();
        for (int k = 0; k < BINS; k++) {
            float mag = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) / N;
            smooth[k] = SMOOTH * smooth[k] + (1f - SMOOTH) * mag;
            float db = 20f * (float) Math.log10(smooth[k] + 1e-12f);
            float v = (db - MIN_DB) / (MAX_DB - MIN_DB) * 255f;
            out[k] = (byte) (v <= 0f ? 0 : (v >= 255f ? 255 : (int) v));
        }
        return true;
    }

    /** In place radix-2 forward FFT of re / im. */
    private void fft() {
        for (int i = 0; i < N; i++) {
            int j = rev[i];
            if (j > i) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= N; len <<= 1) {
            int half = len >> 1;
            int step = N / len;
            for (int i = 0; i < N; i += len) {
                for (int k = 0; k < half; k++) {
                    float wr = cosT[k * step];
                    float wi = -sinT[k * step];
                    int a = i + k;
                    int c = a + half;
                    float xr = re[c] * wr - im[c] * wi;
                    float xi = re[c] * wi + im[c] * wr;
                    re[c] = re[a] - xr;
                    im[c] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }
}
