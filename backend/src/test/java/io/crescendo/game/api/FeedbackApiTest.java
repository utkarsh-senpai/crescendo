package io.crescendo.game.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.crescendo.game.predict.PredictClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract test for the feedback endpoints: submit + validation + admin read auth. */
@SpringBootTest(properties = "crescendo.admin.token=test-secret")
@AutoConfigureMockMvc
class FeedbackApiTest {

    @Autowired
    MockMvc mvc;

    // Not exercised here, but the context wires PredictClient; mock it so no live seam call happens.
    @MockBean
    PredictClient predictClient;

    @Test
    void submitValidFeedbackPersistsAndCountIncrements() throws Exception {
        long before = readCount();

        mvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"message\":\"Loved the transparent AI!\",\"name\":\"Ada\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.createdAt").exists());

        long after = readCount();
        org.junit.jupiter.api.Assertions.assertEquals(before + 1, after);
    }

    @Test
    void ratingIsOptional() throws Exception {
        mvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"No stars, just a note.\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void blankMessageRejected() throws Exception {
        mvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4,\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void ratingOutOfRangeRejected() throws Exception {
        mvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":9,\"message\":\"nine stars\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminReadRequiresMatchingToken() throws Exception {
        // No token → 403.
        mvc.perform(get("/api/feedback"))
                .andExpect(status().isForbidden());
        // Wrong token → 403.
        mvc.perform(get("/api/feedback").header("X-Admin-Token", "nope"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminReadWithTokenReturnsSubmittedFeedback() throws Exception {
        mvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4,\"message\":\"admin-read probe\",\"name\":\"Zed\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/feedback").header("X-Admin-Token", "test-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message").exists())
                .andExpect(jsonPath("$[0].createdAt").exists());
    }

    private long readCount() throws Exception {
        String body = mvc.perform(get("/api/feedback/count"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.count", Long.class);
    }
}
