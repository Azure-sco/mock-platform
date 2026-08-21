package com.xuntian.mock.fakereal;

import com.xuntian.mock.fakereal.capture.FakeRealRequestCapture;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public final class FakeRealController {

    private final FakeRealRequestCapture capture;

    public FakeRealController(FakeRealRequestCapture capture) {
        this.capture = capture;
    }

    @PostMapping(
            path = "/api/km-review/kmReviewRestService/addReviewNew",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> createOaReview(HttpServletRequest request) {
        capture.record(request);
        return response("200", mapOf("flowNo", "REAL-OA-M0", "status", "SUBMITTED"));
    }

    @GetMapping("/api/tcl-cpms/cpmsAuditRestService/getAuditInfosNew")
    public Map<String, Object> queryOaNumber(HttpServletRequest request) {
        capture.record(request);
        return response("200", mapOf("flowNo", "REAL-OA-M0", "oaNumber", "OA-REAL-0001"));
    }

    @PostMapping("/sign/create-and-start")
    public Map<String, Object> createAndStartSign(HttpServletRequest request) {
        capture.record(request);
        return response("0", mapOf("flowId", "REAL-EQB-M0", "status", "SIGNING"));
    }

    @PostMapping("/flow/get-contract-files")
    public Map<String, Object> flowFiles(HttpServletRequest request) {
        capture.record(request);
        return response("0", mapOf(
                "flowId", "REAL-EQB-M0",
                "status", "SIGNED",
                "files", List.of(mapOf("fileId", "REAL-FILE-1", "fileName", "real-signed.pdf"))));
    }

    @PostMapping("/__m0/reset")
    public Map<String, Object> resetFallbackSentinel(HttpServletRequest request) {
        capture.record(request);
        return response("0", mapOf("fallback", "REAL_TARGET_REACHED"));
    }

    private Map<String, Object> response(String code, Object data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("message", "success");
        result.put("source", "FAKE_REAL");
        result.put("data", data);
        return result;
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
