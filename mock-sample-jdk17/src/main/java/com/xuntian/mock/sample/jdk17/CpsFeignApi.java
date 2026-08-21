package com.xuntian.mock.sample.jdk17;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(name = "cps-eqb-m0", url = "${cps.base-url}")
public interface CpsFeignApi {

    @PostMapping(
            path = "/sign/create-and-start",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    String createAndStart(
            @RequestParam("channel") String channel,
            @RequestHeader("domain") String domain,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Cookie") String cookie,
            @RequestHeader("X-Signature") String signature,
            @RequestBody Map<String, Object> body);

    @PostMapping(
            path = "/__m0/reset",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    String resetProbe(
            @RequestHeader("domain") String domain,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Cookie") String cookie,
            @RequestHeader("X-Signature") String signature,
            @RequestBody String body);
}
