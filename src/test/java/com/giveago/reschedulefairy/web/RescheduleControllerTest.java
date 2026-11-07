package com.giveago.reschedulefairy.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RescheduleControllerTest {

    private static final String WINDOWS =
            "[{\"date\":\"2026-11-06\",\"start\":\"13:00\",\"end\":\"17:00\"},"
                    + "{\"date\":\"2026-11-09\",\"start\":\"13:00\",\"end\":\"17:00\"}]";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static MockMultipartFile sampleFile() throws Exception {
        try (InputStream in = RescheduleControllerTest.class.getResourceAsStream("/static/sample.ics")) {
            return new MockMultipartFile("file", "sample.ics", "text/calendar", in.readAllBytes());
        }
    }

    @Test
    void reschedulesSampleCalendarAndServesDownload() throws Exception {
        String body = mockMvc.perform(multipart("/api/reschedule")
                        .file(sampleFile())
                        .param("sourceDay", "2026-11-05")
                        .param("targetWindows", WINDOWS)
                        .param("bufferMinutes", "15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.before", hasSize(14)))
                .andExpect(jsonPath("$.after", hasSize(14)))
                .andExpect(jsonPath("$.moves", hasSize(2)))
                .andExpect(jsonPath("$.moves[0].title").value("1:1 with Sam"))
                .andExpect(jsonPath("$.moves[0].from").value("2026-11-05T12:00:00"))
                .andExpect(jsonPath("$.moves[0].to").value("2026-11-06T13:00:00"))
                .andExpect(jsonPath("$.moves[1].title").value("Design review"))
                .andExpect(jsonPath("$.moves[1].to").value("2026-11-06T15:15:00"))
                .andExpect(jsonPath("$.unplaceable", hasSize(1)))
                .andExpect(jsonPath("$.unplaceable[0].title").value("Roadmap deep-dive"))
                .andExpect(jsonPath("$.unplaceable[0].reason").isNotEmpty())
                .andExpect(jsonPath("$.after[?(@.title == 'Design review')].moved").value(true))
                .andExpect(jsonPath("$.after[?(@.title == 'Client kickoff - Northwind')].movable").value(false))
                .andExpect(jsonPath("$.icsDownloadId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        String ics = mockMvc.perform(get("/api/reschedule/{id}/download", json.get("icsDownloadId").asText()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("sample-rescheduled.ics")))
                .andExpect(content().contentTypeCompatibleWith("text/calendar"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(ics).contains("BEGIN:VCALENDAR").contains("DTSTART:20261106T151500");
    }

    @Test
    void returnsClearBadRequestErrors() throws Exception {
        mockMvc.perform(multipart("/api/reschedule")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()))
                        .param("sourceDay", "2026-11-05")
                        .param("targetWindows", WINDOWS))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("ics")));

        mockMvc.perform(multipart("/api/reschedule")
                        .file(sampleFile())
                        .param("sourceDay", "next thursday")
                        .param("targetWindows", WINDOWS))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("sourceDay")));

        mockMvc.perform(multipart("/api/reschedule")
                        .file(sampleFile())
                        .param("sourceDay", "2026-11-05")
                        .param("targetWindows", "not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("targetWindows")));

        mockMvc.perform(multipart("/api/reschedule")
                        .param("sourceDay", "2026-11-05")
                        .param("targetWindows", WINDOWS))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("file")));

        mockMvc.perform(get("/api/reschedule/{id}/download", "nope"))
                .andExpect(status().isNotFound());
    }
}
