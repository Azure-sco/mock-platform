package com.xuntian.mock.fakereal;

import com.xuntian.mock.fakereal.capture.FakeRealRequestCapture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = FakeRealApplication.class)
@AutoConfigureMockMvc
class FakeRealApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeRealRequestCapture capture;

    @Test
    void servesOaMultipartFixtureAsTheRealTarget() throws Exception {
        MockMultipartFile formValues = new MockMultipartFile(
                "formValues", "", MediaType.APPLICATION_JSON_VALUE,
                "{\"fd_task_no\":\"SETTLE-42\"}".getBytes());

        mockMvc.perform(multipart("/api/km-review/kmReviewRestService/addReviewNew")
                        .file(formValues)
                        .param("docSubject", "M0 settlement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FAKE_REAL"))
                .andExpect(jsonPath("$.data.flowNo").value("REAL-OA-M0"));

        assertThat(capture.last().path())
                .isEqualTo("/api/km-review/kmReviewRestService/addReviewNew");
    }

    @Test
    void servesCpsJsonFixtureAndCapturesRealHeaders() throws Exception {
        mockMvc.perform(post("/sign/create-and-start?channel=EQB")
                        .header("Authorization", "Bearer real-only")
                        .header("domain", "cps-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settleId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FAKE_REAL"))
                .andExpect(jsonPath("$.data.flowId").value("REAL-EQB-M0"));

        assertThat(capture.last().authorization()).isEqualTo("Bearer real-only");
        assertThat(capture.last().domain()).isEqualTo("cps-test");
    }
}
