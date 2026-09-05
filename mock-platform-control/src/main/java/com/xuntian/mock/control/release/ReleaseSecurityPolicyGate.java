package com.xuntian.mock.control.release;

public interface ReleaseSecurityPolicyGate {

    void requirePublishedAndBound(String environment, String app);
}
