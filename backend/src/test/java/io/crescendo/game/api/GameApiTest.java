package io.crescendo.game.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.crescendo.game.predict.PredictClient;
import io.crescendo.game.predict.PredictDtos.RankedArtist;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** End-to-end HTTP contract test: create → board → draft → score → leaderboard, seam mocked. */
@SpringBootTest
@AutoConfigureMockMvc
class GameApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @MockBean
    PredictClient predictClient;

    @BeforeEach
    void stubSeam() {
        when(predictClient.rank(any(LocalDate.class), anyList())).thenAnswer(inv -> {
            Map<Long, RankedArtist> out = new java.util.LinkedHashMap<>();
            List<?> snaps = inv.getArgument(1);
            int rank = 1;
            for (Object o : snaps) {
                var s = (io.crescendo.game.domain.ArtistFeatureSnapshot) o;
                out.put(s.getArtistId(), new RankedArtist(
                        s.getArtistId(), 0.5, rank++, List.of("test reason")));
            }
            return out;
        });
    }

    @Test
    void fullGameFlowOverHttp() throws Exception {
        String createBody = mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerName\":\"Ada\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFTING"))
                .andExpect(jsonPath("$.salaryCap").value(100))
                .andReturn().getResponse().getContentAsString();
        long gameId = json.readTree(createBody).get("gameId").asLong();

        mvc.perform(get("/api/games/{id}/board", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artists.length()").value(10))
                .andExpect(jsonPath("$.artists[0].breakoutScore").value(0.5));

        mvc.perform(post("/api/games/{id}/draft", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artistIds\":[105,104,106,107,108]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salarySpent").value(64))
                .andExpect(jsonPath("$.roster.length()").value(5));

        mvc.perform(post("/api/games/{id}/score", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scoreAsOfDate\":\"2026-08-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCORED"))
                .andExpect(jsonPath("$.playerScore").isNumber());

        mvc.perform(get("/api/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].playerName").value("Ada"));
    }

    @Test
    void overCapDraftReturns400() throws Exception {
        String createBody = mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerName\":\"Grace\"}"))
                .andReturn().getResponse().getContentAsString();
        long gameId = json.readTree(createBody).get("gameId").asLong();

        mvc.perform(post("/api/games/{id}/draft", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artistIds\":[109,101,110,102,103]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("exceeds cap")));
    }

    @Test
    void unknownGameReturns404() throws Exception {
        mvc.perform(get("/api/games/{id}", 999999))
                .andExpect(status().isNotFound());
    }
}
