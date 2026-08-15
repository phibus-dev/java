package dev.phibus.s3.test;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class S3LoadTestEngine implements LoadTestEngine {
    private final UploadTestEngine delegate;

    public S3LoadTestEngine(UploadTestEngine delegate) {
        this.delegate = delegate;
    }

    @Override
    public TestType type() {
        return TestType.S3;
    }

    @Override
    public void execute(TestRun run) {
        delegate.execute(run);
    }

    public List<String> listBuckets(TestRequest request) {
        return delegate.listBuckets(request);
    }
}
