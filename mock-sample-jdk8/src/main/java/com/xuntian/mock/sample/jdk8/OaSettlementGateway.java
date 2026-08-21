package com.xuntian.mock.sample.jdk8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.client.annotation.ThirdPartyMock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class OaSettlementGateway {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public OaSettlementGateway(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${oa.base-url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    @ThirdPartyMock(provider = "OA", api = "OA_SETTLE_CREATE")
    public String createReview(String businessNo) throws JsonProcessingException {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<String, Object>();
        form.add("docCreator", "{\"LoginName\":\"m0-user\"}");
        form.add("docSubject", "M0 settlement review");
        form.add("fdTemplateId", "M0-TEMPLATE");
        form.add("formValues", objectMapper.writeValueAsString(formValues(businessNo)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth("real-oa-secret");
        headers.add(HttpHeaders.COOKIE, "oa-session=real-only");
        headers.add("X-Signature", "real-oa-signature");
        headers.add("X-Business-Tag", "settlement");

        return restTemplate.postForObject(
                baseUrl + "/api/km-review/kmReviewRestService/addReviewNew",
                new HttpEntity<MultiValueMap<String, Object>>(form, headers),
                String.class);
    }

    @ThirdPartyMock(provider = "OA", api = "OA_SETTLE_CREATE")
    public String resetProbe(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("real-oa-secret");
        headers.add(HttpHeaders.COOKIE, "oa-session=real-only");
        headers.add("X-Signature", "real-oa-signature");
        return restTemplate.postForObject(
                baseUrl + "/__m0/reset",
                new HttpEntity<String>(body, headers),
                String.class);
    }

    private Map<String, Object> formValues(String businessNo) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("fd_quantity", "2");
        values.put("fd_all_amount", "1200.50");
        values.put("fd_task_no", businessNo);
        return values;
    }
}
