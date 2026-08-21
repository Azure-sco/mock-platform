package com.xuntian.mock.sample.jdk8;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OaDemoController {

    private final OaSettlementGateway gateway;

    public OaDemoController(OaSettlementGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/demo/oa/reviews")
    public String createReview(@RequestParam String businessNo) throws JsonProcessingException {
        return gateway.createReview(businessNo);
    }
}
