package io.crescendo.game.config;

import io.crescendo.game.domain.Artist;
import io.crescendo.game.domain.ArtistFeatureSnapshot;
import io.crescendo.game.repo.ArtistFeatureSnapshotRepository;
import io.crescendo.game.repo.ArtistRepository;
import io.crescendo.game.service.GameRules;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Seeds a deterministic demo world: 10 emerging electronic artists with a salary-cap price and two
 * feature snapshots each — one at the draft date (what the seam scores on) and one at the score
 * date (the realised 30-day growth). The cohort is deliberately mixed so the transparent-AI story
 * shows: some artists have strong ORGANIC momentum (low inorganic_score) and go on to grow, while
 * one has high raw growth but a high inorganic_score — the seam should discount it, and it should
 * underperform at score time. Values match the ML canonical FEATURES names.
 */
@Configuration
@ConditionalOnProperty(name = "crescendo.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    @Bean
    ApplicationRunner seed(ArtistRepository artists, ArtistFeatureSnapshotRepository snapshots,
                           GameRules rules) {
        return args -> {
            if (artists.count() > 0) {
                return;
            }
            LocalDate draft = rules.getDraftAsOfDate();
            LocalDate score = rules.getScoreAsOfDate();

            // artistId, name, salary, draft-day features, realised growth_30d at score date.
            record Seed(long id, String name, int salary,
                        double subs, double g7, double g30, double accel, double consistency,
                        double vg7, double upload, double inorganic,
                        double realisedGrowth30d) {
            }

            Seed[] seeds = {
                new Seed(101, "Neon Pulse", 22, 42000, 0.09, 0.31, 0.05, 0.82, 0.14, 4.0, 0.05, 0.40),
                new Seed(102, "Velvet Static", 20, 31000, 0.08, 0.27, 0.04, 0.79, 0.12, 3.5, 0.07, 0.35),
                new Seed(103, "Glass Harbor", 18, 27500, 0.07, 0.24, 0.03, 0.75, 0.10, 3.0, 0.06, 0.30),
                new Seed(104, "Midnight Cartography", 16, 19000, 0.06, 0.20, 0.02, 0.71, 0.09, 2.5, 0.08, 0.26),
                new Seed(105, "Aurora Circuit", 15, 15500, 0.05, 0.18, 0.02, 0.68, 0.08, 2.5, 0.09, 0.22),
                new Seed(106, "Paper Satellites", 13, 11000, 0.04, 0.14, 0.01, 0.63, 0.06, 2.0, 0.10, 0.17),
                new Seed(107, "Low Tide Theory", 11, 8200, 0.03, 0.11, 0.01, 0.58, 0.05, 1.5, 0.11, 0.13),
                new Seed(108, "Cassette Ghosts", 9, 5400, 0.02, 0.08, 0.00, 0.52, 0.03, 1.0, 0.12, 0.09),
                // High RAW growth but high inorganic_score — the seam should discount it and it
                // under-delivers on realised organic growth.
                new Seed(109, "Bot Bloom", 24, 68000, 0.22, 0.61, 0.11, 0.30, 0.28, 1.0, 0.93, 0.06),
                new Seed(110, "Phantom Streams", 21, 52000, 0.18, 0.49, 0.09, 0.34, 0.24, 1.0, 0.88, 0.08),
            };

            for (Seed s : seeds) {
                artists.save(new Artist(s.id(), s.name(), "electronic", s.salary()));
                snapshots.save(new ArtistFeatureSnapshot(
                        s.id(), draft, s.subs(), s.g7(), s.g30(), s.accel(),
                        s.consistency(), s.vg7(), s.upload(), s.inorganic()));
                // Score-date snapshot: growth_30d becomes the realised relative growth for scoring.
                snapshots.save(new ArtistFeatureSnapshot(
                        s.id(), score, s.subs() * (1 + s.g30()), s.realisedGrowth30d(),
                        s.realisedGrowth30d(), s.accel() * 0.5, s.consistency(),
                        s.vg7() * 0.8, s.upload(), s.inorganic()));
            }
            log.info("Seeded {} demo artists with draft + score feature snapshots", seeds.length);
        };
    }
}
