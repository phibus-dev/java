package dev.phibus.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class S3MultipartUploaderTest {
    @Test
    void calculatesPartCount() {
        assertEquals(1, S3MultipartUploader.partCount(5, 5));
        assertEquals(2, S3MultipartUploader.partCount(6, 5));
    }

    @Test
    void rejectsInvalidSizes() {
        assertThrows(IllegalArgumentException.class, () -> S3MultipartUploader.partCount(0, 5));
        assertThrows(IllegalArgumentException.class, () -> S3MultipartUploader.partCount(5, 0));
    }

    @Test
    void raisesPartSizeToRespectTenThousandPartLimit() {
        long total = 100_000L;
        assertEquals(10L, S3MultipartUploader.normalizePartSize(1, total));
    }

    @Test
    void randomInputStreamStopsAtConfiguredSize() throws IOException {
        var stream = new S3MultipartUploader.RandomInputStream(10);
        byte[] buffer = new byte[16];
        assertEquals(10, stream.read(buffer));
        assertEquals(-1, stream.read(buffer));
    }
}
