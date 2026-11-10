package dev.phibus.s3.test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.DataFormatException;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramIterationValue;

/**
 * Wire representation for mergeable latency histograms reported by load agents.
 */
public final class LatencyHistogramCodec {
    private static final int MAX_ENCODED_LENGTH = 2_000_000;

    private LatencyHistogramCodec() { }

    public static String encode(Histogram histogram) {
        if (histogram == null || histogram.getTotalCount() == 0) return null;
        ByteBuffer buffer = ByteBuffer.allocate(histogram.getNeededByteBufferCapacity());
        int length = histogram.encodeIntoCompressedByteBuffer(buffer);
        return Base64.getEncoder().encodeToString(Arrays.copyOf(buffer.array(), length));
    }

    public static String encodeScaled(Histogram histogram, long divisor, long highestTrackableValue) {
        if (histogram == null || histogram.getTotalCount() == 0) return null;
        if (divisor < 1) throw new IllegalArgumentException("Histogram divisor must be positive");
        Histogram scaled = new Histogram(1, highestTrackableValue, histogram.getNumberOfSignificantValueDigits());
        for (HistogramIterationValue value : histogram.recordedValues()) {
            long normalized = Math.max(1, Math.min(highestTrackableValue, value.getValueIteratedTo() / divisor));
            scaled.recordValueWithCount(normalized, value.getCountAtValueIteratedTo());
        }
        return encode(scaled);
    }

    public static Histogram decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        if (encoded.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("Latency histogram payload is too large");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            return Histogram.decodeFromCompressedByteBuffer(ByteBuffer.wrap(bytes), 1);
        } catch (IllegalArgumentException | DataFormatException error) {
            throw new IllegalArgumentException("Invalid latency histogram payload", error);
        }
    }
}
