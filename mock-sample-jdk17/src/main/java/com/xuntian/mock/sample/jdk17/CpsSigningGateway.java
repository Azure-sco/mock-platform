package com.xuntian.mock.sample.jdk17;

import com.xuntian.mock.client.annotation.ThirdPartyMock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CpsSigningGateway {

    private static final String REAL_AUTHORIZATION = "Bearer real-cps-secret";
    private static final String REAL_COOKIE = "cps-session=real-only";
    private static final String REAL_SIGNATURE = "real-cps-signature";

    private final CpsFeignApi cpsFeignApi;
    private final String domain;

    public CpsSigningGateway(CpsFeignApi cpsFeignApi, @Value("${cps.domain}") String domain) {
        this.cpsFeignApi = cpsFeignApi;
        this.domain = domain;
    }

    @ThirdPartyMock(provider = "CPS_EQB", api = "CPS_SIGN_CREATE_START")
    public String createAndStart(long settleId) {
        return createAndStart(settleId, "EQB");
    }

    @ThirdPartyMock(provider = "CPS_EQB", api = "CPS_SIGN_CREATE_START")
    public String createAndStartWithChannel(long settleId, String channel) {
        return createAndStart(settleId, channel);
    }

    private String createAndStart(long settleId, String channel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateType", "file");
        body.put("fileName", "settlement.pdf");
        body.put("settleId", settleId);
        body.put("autoFinish", true);
        return cpsFeignApi.createAndStart(
                channel, domain, REAL_AUTHORIZATION, REAL_COOKIE, REAL_SIGNATURE, body);
    }

    @ThirdPartyMock(provider = "CPS_EQB", api = "CPS_SIGN_CREATE_START")
    public String resetProbe(String body) {
        return cpsFeignApi.resetProbe(
                domain, REAL_AUTHORIZATION, REAL_COOKIE, REAL_SIGNATURE, body);
    }
}
