package com.xuntian.mock.client.core.http;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class UriRewriterTest {

    @Test
    void replacesOnlySchemeHostAndPort() {
        URI original = URI.create("https://esign.example.com/v3/contracts/a%2Fb?detail=true&tag=x%20y");

        URI rewritten = UriRewriter.rewrite(original, URI.create("http://localhost:9080"));

        assertThat(rewritten.toASCIIString())
                .isEqualTo("http://localhost:9080/v3/contracts/a%2Fb?detail=true&tag=x%20y");
        assertThat(original.toASCIIString())
                .isEqualTo("https://esign.example.com/v3/contracts/a%2Fb?detail=true&tag=x%20y");
    }
}
