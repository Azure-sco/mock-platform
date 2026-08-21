package com.xuntian.mock.fakereal.capture;

public record CapturedRealRequest(
        String path,
        String authorization,
        String cookie,
        String signature,
        String domain) {
}
