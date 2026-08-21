package com.xuntian.mock.sample.jdk8;

import com.xuntian.mock.client.annotation.ThirdPartyMock;
import org.springframework.stereotype.Service;

@Service
public class OaNumberGateway {

    private final OaFeignApi oaFeignApi;

    public OaNumberGateway(OaFeignApi oaFeignApi) {
        this.oaFeignApi = oaFeignApi;
    }

    @ThirdPartyMock(provider = "OA", api = "OA_NUMBER_QUERY")
    public String queryNumber(String flowIds) {
        return oaFeignApi.queryNumber(
                flowIds,
                "Bearer real-oa-secret",
                "oa-session=real-only",
                "real-oa-signature",
                "settlement");
    }

    @ThirdPartyMock(provider = "OA", api = "OA_NUMBER_QUERY")
    public String resetProbe(String body) {
        return oaFeignApi.resetProbe(
                "Bearer real-oa-secret",
                "oa-session=real-only",
                "real-oa-signature",
                body);
    }
}
