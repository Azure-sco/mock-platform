package com.xuntian.mock.sample.jdk17;

import com.xuntian.mock.client.annotation.ThirdPartyMock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CpsFilesRestGateway {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String domain;

    public CpsFilesRestGateway(
            RestTemplate restTemplate,
            @Value("${cps.base-url}") String baseUrl,
            @Value("${cps.domain}") String domain) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.domain = domain;
    }

    @ThirdPartyMock(provider = "CPS_EQB", api = "CPS_FLOW_FILES")
    public String querySignedFiles(String flowId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seqNo", flowId);
        body.put("returnDownloadUrl", true);
        body.put("flowState", "SIGNED");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("domain", domain);
        headers.setBearerAuth("real-cps-secret");
        headers.set(HttpHeaders.COOKIE, "cps-session=real-only");
        headers.set("X-Signature", "real-cps-signature");

        return restTemplate.postForObject(
                baseUrl + "/flow/get-contract-files",
                new HttpEntity<>(body, headers),
                String.class);
    }

    @ThirdPartyMock(provider = "CPS_EQB", api = "CPS_FLOW_FILES")
    public String resetProbe(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("domain", domain);
        headers.setBearerAuth("real-cps-secret");
        headers.set(HttpHeaders.COOKIE, "cps-session=real-only");
        headers.set("X-Signature", "real-cps-signature");

        return restTemplate.postForObject(
                baseUrl + "/__m0/reset",
                new HttpEntity<>(body, headers),
                String.class);
    }
}
