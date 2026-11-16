package com.elevate.analysis.stats;

import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

/**
 * One place for how latencies are measured: HdrHistograms of response times in
 * <b>microseconds</b>, tracking up to 10 minutes with 3 significant digits.
 *
 * <p>3 significant digits means every recorded value is kept to within 0.1%, so a p95 of
 * 150 ms is known to ±0.15 ms. Unlike Locust's rounded buckets, two histograms can be
 * {@linkplain Histogram#add(org.HdrHistogram.AbstractHistogram) added} without any loss: the
 * percentiles of the sum are exactly the percentiles of all the recorded values together.
 */
public final class Histograms {

    /** Largest latency kept: 10 minutes (longer values are clamped to it). */
    public static final long HIGHEST_TRACKABLE_MICROS = 600_000_000L;
    /** Value precision: 3 significant digits (±0.1%). */
    public static final int SIGNIFICANT_DIGITS = 3;

    private Histograms() {
    }

    /** A new, empty histogram with the standard settings. */
    public static Histogram newHistogram() {
        return new Histogram(HIGHEST_TRACKABLE_MICROS, SIGNIFICANT_DIGITS);
    }

    /** A new recorder with the standard settings (see {@code engine.CaseRecorder}). */
    public static Recorder newRecorder() {
        return new Recorder(HIGHEST_TRACKABLE_MICROS, SIGNIFICANT_DIGITS);
    }

    /** Clamps a latency into the trackable range. */
    public static long clamp(long micros) {
        return Math.max(0, Math.min(micros, HIGHEST_TRACKABLE_MICROS));
    }

    /** A copy of {@code source} with the standard settings. */
    public static Histogram copyOf(Histogram source) {
        Histogram copy = newHistogram();
        copy.add(source);
        return copy;
    }

    /**
     * A percentile in milliseconds.
     *
     * @param histogram  the histogram
     * @param percentile e.g. {@code 95.0}
     * @return the value in ms, or {@code null} when the histogram is empty
     */
    public static Double percentileMs(Histogram histogram, double percentile) {
        if (histogram.getTotalCount() == 0) {
            return null;
        }
        return histogram.getValueAtPercentile(percentile) / 1000.0;
    }

    /** Compressed binary form, for the database. */
    public static byte[] encode(Histogram histogram) {
        ByteBuffer buffer = ByteBuffer.allocate(histogram.getNeededByteBufferCapacity());
        int length = histogram.encodeIntoCompressedByteBuffer(buffer);
        byte[] bytes = new byte[length];
        buffer.flip();
        buffer.get(bytes);
        return bytes;
    }

    /**
     * Reverses {@link #encode(Histogram)}.
     *
     * @param bytes the compressed form, or {@code null}
     * @return the histogram (empty for {@code null})
     */
    public static Histogram decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return newHistogram();
        }
        try {
            return copyOf(Histogram.decodeFromCompressedByteBuffer(ByteBuffer.wrap(bytes), HIGHEST_TRACKABLE_MICROS));
        } catch (DataFormatException e) {
            throw new IllegalStateException("stored histogram is corrupt", e);
        }
    }
}
