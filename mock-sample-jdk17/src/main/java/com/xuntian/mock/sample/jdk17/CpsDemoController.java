package com.xuntian.mock.sample.jdk17;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CpsDemoController {

    private final CpsSigningGateway gateway;

    public CpsDemoController(CpsSigningGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/demo/cps/signatures")
    public String createAndStart(@RequestParam long settleId) {
        return gateway.createAndStart(settleId);
    }
}
