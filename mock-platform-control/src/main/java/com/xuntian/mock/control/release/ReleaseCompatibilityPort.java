package com.xuntian.mock.control.release;

public interface ReleaseCompatibilityPort {

    void requireCompatible(String environment, String app, String releaseId);
}
