package dev.phibus.s3.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

class LatencyHistogramCodecTest {
    @Test
    void roundTripsHistogramWithoutLosingCountsOrPercentiles() {
        Histogram source = new Histogram(1, 60_000, 3);
        source.recordValueWithCount(10, 90);
        source.recordValueWithCount(100, 10);

        Histogram decoded = LatencyHistogramCodec.decode(LatencyHistogramCodec.encode(source));

        assertThat(decoded.getTotalCount()).isEqualTo(100);
        assertThat(decoded.getValueAtPercentile(50)).isEqualTo(10);
        assertThat(decoded.getValueAtPercentile(99)).isEqualTo(100);
    }

    @Test
    void rejectsMalformedPayload() {
        assertThatThrownBy(() -> LatencyHistogramCodec.decode("not-base64"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid latency histogram");
    }
}
