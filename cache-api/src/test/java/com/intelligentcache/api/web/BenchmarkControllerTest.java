package com.intelligentcache.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentcache.api.web.dto.BenchmarkRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BenchmarkController.class)
class BenchmarkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void runsZipfianTraceAgainstLruAndLfuAndReturnsBothResults() throws Exception {
        BenchmarkRequest request = new BenchmarkRequest(
                BenchmarkRequest.TraceType.ZIPFIAN,
                20_000, 500, 1.2, null, 42L, 50,
                List.of("LRU", "LFU")
        );

        mockMvc.perform(post("/benchmark/run")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.LRU.hitRate").exists())
                .andExpect(jsonPath("$.LFU.hitRate").exists())
                .andExpect(jsonPath("$.LFU.policyName").value("LFU"));
    }

   @Test
    void rejectsUnknownPolicyName() throws Exception {
        BenchmarkRequest request = new BenchmarkRequest(
                BenchmarkRequest.TraceType.SEQUENTIAL,
                1000, 100, null, null, 1L, 20,
                List.of("FIFO") // genuinely unsupported -- LRU/LFU/ARC/TinyLFU are all real now
        );

        mockMvc.perform(post("/benchmark/run")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists()); 
    }

}
