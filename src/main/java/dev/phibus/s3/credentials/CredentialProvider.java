package dev.phibus.s3.credentials;

import dev.phibus.s3.test.TestRequest;

public interface CredentialProvider {
    S3Credentials resolve(TestRequest request);
}
