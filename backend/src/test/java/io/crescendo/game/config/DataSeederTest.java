package io.crescendo.game.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.crescendo.game.domain.Artist;
import io.crescendo.game.domain.League;
import io.crescendo.game.predict.PredictClient;
import io.crescendo.game.repo.ArtistRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Verifies the self-healing seed: a stale pre-v1.4 artist set (wrong count / null channelId) is
 * detected and rebuilt to the full current roster with channelIds populated. This is the fix for
 * the prod Neon DB that was seeded at v1.3 and never picked up v1.4's data.
 */
@SpringBootTest
class DataSeederTest {

    @Autowired
    ArtistRepository artists;

    @Autowired
    ApplicationRunner seedRunner;

    @MockBean
    PredictClient predictClient;

    @Test
    void reSeedsWhenChannelIdMissing() throws Exception {
        // Boot already seeded the full current roster; every artist must have a channelId.
        assertThat(artists.count()).isGreaterThan(0);
        assertThat(artists.countByChannelIdIsNull()).isZero();
        long full = artists.count();

        // Simulate a stale pre-v1.4 row: an artist with a NULL channelId.
        artists.save(new Artist(9999L, "Legacy Artist", null, "pop", League.POP, 12));
        assertThat(artists.countByChannelIdIsNull()).isEqualTo(1);

        // Re-run the seeder: it should detect the stale row and rebuild the full, complete roster.
        seedRunner.run(null);

        assertThat(artists.count()).isEqualTo(full);
        assertThat(artists.countByChannelIdIsNull()).isZero();
    }
}
