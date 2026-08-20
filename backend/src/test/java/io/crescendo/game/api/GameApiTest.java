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
                        s.getArtistId(), 0.5, rank++, List.of("test reason"), null, null, null, null));
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
                .andExpect(jsonPath("$.artists.length()").value(20)) // v1.5: POP expanded to 20
                .andExpect(jsonPath("$.artists[0].breakoutScore").value(0.5));

        mvc.perform(post("/api/games/{id}/draft", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artistIds\":[511,512,513,514,515]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salarySpent").value(80)) // v1.5: 511+512+513+514+515 = 18+17+16+15+14 = 80
                .andExpect(jsonPath("$.roster.length()").value(5))
                // The transparent AI opponent drafts off the same board and shows its reasoning.
                .andExpect(jsonPath("$.opponent.name").value("Crescendo AI"))
                .andExpect(jsonPath("$.opponent.roster.length()").value(5))
                .andExpect(jsonPath("$.opponent.roster[0].rationale").isNotEmpty());

        mvc.perform(post("/api/games/{id}/score", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scoreAsOfDate\":\"2026-08-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCORED"))
                .andExpect(jsonPath("$.playerScore").isNumber())
                .andExpect(jsonPath("$.opponent.score").isNumber())
                .andExpect(jsonPath("$.outcome").isNotEmpty());

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
                        .content("{\"artistIds\":[501,502,503,504,505]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("exceeds cap")));
    }

    @Test
    void unknownGameReturns404() throws Exception {
        mvc.perform(get("/api/games/{id}", 999999))
                .andExpect(status().isNotFound());
    }

    @Test
    void replayDateIsPresentInBoardResponseWhenPassed() throws Exception {
        // v1.7: when replayDate is provided, the board response carries it back
        String createBody = mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerName\":\"ReplayScout\",\"replayDate\":\"2026-06-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayDate").value("2026-06-01"))
                .andExpect(jsonPath("$.isReplayMode").value(true))
                .andReturn().getResponse().getContentAsString();
        long gameId = json.readTree(createBody).get("gameId").asLong();

        mvc.perform(get("/api/games/{id}/board", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayDate").value("2026-06-01"))
                .andExpect(jsonPath("$.isReplayMode").value(true));
    }
}
