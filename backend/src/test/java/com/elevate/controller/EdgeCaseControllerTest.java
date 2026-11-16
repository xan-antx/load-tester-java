package com.elevate.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.elevate.analysis.edgecase.CustomCaseClassifier;
import com.elevate.analysis.edgecase.EdgeCaseSuite;
import com.elevate.service.EdgeCaseService;

/** Uses the real edge-case service (it is pure logic) behind the controller. */
@WebMvcTest(EdgeCaseController.class)
@Import(EdgeCaseControllerTest.Real.class)
class EdgeCaseControllerTest {

    private static final String SAMPLE = "{\"username\":\"demo\",\"password\":\"demo123\",\"expiresInMins\":30}";

    @TestConfiguration
    static class Real {
        @Bean
        EdgeCaseService edgeCaseService() {
            return new EdgeCaseService(EdgeCaseSuite.standard(), new CustomCaseClassifier(EdgeCaseSuite.standard()));
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void generate() throws Exception {
        mvc.perform(post("/api/generate-edge-cases").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sample_input\":" + SAMPLE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_cases").value(27))
                .andExpect(jsonPath("$.cases[0].label").value("valid_sample"))
                .andExpect(jsonPath("$.cases[0].expect").value("accept"))
                .andExpect(jsonPath("$.cases[1].payload.password").value("demo123"));
        mvc.perform(post("/api/generate-edge-cases").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("sample_input (object) is required"));
    }

    @Test
    void validateCustomCaseIsAlways200() throws Exception {
        mvc.perform(post("/api/validate-custom-case").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sample_input\":" + SAMPLE + ",\"label\":\"no_user\",\"payload\":{\"password\":\"x\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.changes[0].kind").value("removed"))
                .andExpect(jsonPath("$.changes[0].from").doesNotExist());
        mvc.perform(post("/api/validate-custom-case").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sample_input\":" + SAMPLE + ",\"label\":\"x\",\"payload\":" + SAMPLE + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("Not an edge case — identical to the sample"));
    }

    @Test
    void confirmSelection() throws Exception {
        mvc.perform(post("/api/confirm-selection").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sample_input\":" + SAMPLE + ",\"selected_labels\":[\"valid_sample\"],"
                                + "\"custom_cases\":[{\"label\":\"mine\",\"payload\":{\"username\":\"x\"},\"expect\":\"reject\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed_count").value(2))
                .andExpect(jsonPath("$.confirmed_cases[1].classified_as").value("custom"))
                .andExpect(jsonPath("$.confirmed_cases[0].classified_as").doesNotExist());
        mvc.perform(post("/api/confirm-selection").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sample_input\":" + SAMPLE + ",\"selected_labels\":[\"nope\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Unknown labels: ['nope']"));
    }
}
