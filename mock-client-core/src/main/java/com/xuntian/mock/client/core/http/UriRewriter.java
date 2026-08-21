package com.xuntian.mock.client.core.http;

import java.net.URI;

public final class UriRewriter {

    private UriRewriter() {
    }

    public static URI rewrite(URI original, URI runtimeBaseUri) {
        StringBuilder rewritten = new StringBuilder()
                .append(runtimeBaseUri.getScheme())
                .append("://")
                .append(runtimeBaseUri.getRawAuthority())
                .append(original.getRawPath());
        if (original.getRawQuery() != null) {
            rewritten.append('?').append(original.getRawQuery());
        }
        return URI.create(rewritten.toString());
    }
}
