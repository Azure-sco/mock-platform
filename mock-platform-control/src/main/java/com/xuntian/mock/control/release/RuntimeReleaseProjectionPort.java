package com.xuntian.mock.control.release;

public interface RuntimeReleaseProjectionPort {

    void putImmutableSnapshot(String releaseId, byte[] envelopeBytes);

    byte[] readImmutableSnapshot(String releaseId);

    void writeActivePointer(String environment, String app, long activationVersion, byte[] pointerBytes);
}
