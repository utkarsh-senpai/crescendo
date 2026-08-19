package io.crescendo.game.config;

import io.crescendo.game.domain.Artist;
import io.crescendo.game.domain.ArtistFeatureSnapshot;
import io.crescendo.game.domain.League;
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
 * Seeds a deterministic demo world across three draftable {@link League}s — POP, EDM, BOLLYWOOD.
 * Each artist is a REAL Official Artist / label channel (see {@code ml/seeds/genre_artists.txt}),
 * anchored on its real current subscriber count captured 2026-08-19. The momentum feature values
 * (7d/30d growth, acceleration, etc.) are SYNTHETIC demo values until the daily cron accumulates
 * enough real history to compute them — the game is clearly a labelled demo in the meantime.
 *
 * <p>All three leagues use the SAME /predict model — for these top-artist pools it reads "who's
 * growing fastest right now" rather than literal breakout. Every seeded real artist is organic
 * (inorganic_score well under the detector threshold): we never fabricate a "bought growth"
 * accusation against a real person. Values match the ML canonical FEATURES names.
 */
@Configuration
@ConditionalOnProperty(name = "crescendo.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    /** One seed row: id, name, salary, draft-day features, and realised growth_30d at score date. */
    private record Seed(long id, String name, String genre, League league, int salary,
                        double subs, double g7, double g30, double accel, double consistency,
                        double vg7, double upload, double inorganic,
                        double realisedGrowth30d) {
    }

    @Bean
    ApplicationRunner seed(ArtistRepository artists, ArtistFeatureSnapshotRepository snapshots,
                           GameRules rules) {
        return args -> {
            if (artists.count() > 0) {
                return;
            }
            LocalDate draft = rules.getDraftAsOfDate();
            LocalDate score = rules.getScoreAsOfDate();

            Seed[] seeds = allSeeds();
            for (Seed s : seeds) {
                artists.save(new Artist(s.id(), s.name(), s.genre(), s.league(), s.salary()));
                snapshots.save(new ArtistFeatureSnapshot(
                        s.id(), draft, s.subs(), s.g7(), s.g30(), s.accel(),
                        s.consistency(), s.vg7(), s.upload(), s.inorganic()));
                // Score-date snapshot: growth_30d becomes the realised relative growth for scoring.
                snapshots.save(new ArtistFeatureSnapshot(
                        s.id(), score, s.subs() * (1 + s.g30()), s.realisedGrowth30d(),
                        s.realisedGrowth30d(), s.accel() * 0.5, s.consistency(),
                        s.vg7() * 0.8, s.upload(), s.inorganic()));
            }
            log.info("Seeded {} demo artists across {} leagues",
                    seeds.length, League.values().length);
        };
    }

    private static Seed[] allSeeds() {
        return new Seed[] {
            // ---- POP — real Official Artist/label channels; momentum synthetic (labelled demo) ----
            new Seed(501, "BTS", "pop", League.POP, 24, 82400000, 0.08, 0.28, 0.06, 0.85, 0.2, 4.0, 0.04, 0.39),
            new Seed(502, "Taylor Swift", "pop", League.POP, 23, 63300000, 0.075, 0.26, 0.055, 0.823, 0.185, 3.7, 0.045, 0.363),
            new Seed(503, "Blackpink", "pop", League.POP, 21, 101000000, 0.069, 0.24, 0.051, 0.795, 0.171, 3.5, 0.051, 0.335),
            new Seed(504, "Justin Bieber", "pop", League.POP, 20, 79200000, 0.064, 0.22, 0.046, 0.768, 0.156, 3.2, 0.056, 0.308),
            new Seed(505, "Ed Sheeran", "pop", League.POP, 19, 59200000, 0.058, 0.2, 0.042, 0.741, 0.142, 2.9, 0.062, 0.281),
            new Seed(506, "The Weeknd", "pop", League.POP, 18, 39900000, 0.053, 0.18, 0.037, 0.714, 0.127, 2.6, 0.067, 0.254),
            new Seed(507, "Ariana Grande", "pop", League.POP, 16, 58000000, 0.047, 0.16, 0.033, 0.686, 0.113, 2.4, 0.073, 0.226),
            new Seed(508, "Katy Perry", "pop", League.POP, 15, 47200000, 0.042, 0.14, 0.028, 0.659, 0.098, 2.1, 0.078, 0.199),
            new Seed(509, "Bruno Mars", "pop", League.POP, 14, 44200000, 0.036, 0.12, 0.024, 0.632, 0.084, 1.8, 0.084, 0.172),
            new Seed(510, "Billie Eilish", "pop", League.POP, 13, 58500000, 0.031, 0.1, 0.019, 0.605, 0.069, 1.5, 0.089, 0.145),
            new Seed(511, "Dua Lipa", "pop", League.POP, 11, 25300000, 0.025, 0.08, 0.015, 0.577, 0.055, 1.3, 0.095, 0.117),
            new Seed(512, "Shawn Mendes", "pop", League.POP, 10, 30800000, 0.02, 0.06, 0.01, 0.55, 0.04, 1.0, 0.1, 0.09),
            // ---- EDM — real Official Artist/label channels; momentum synthetic (labelled demo) ----
            new Seed(601, "David Guetta", "electronic", League.EDM, 24, 27900000, 0.08, 0.28, 0.06, 0.85, 0.2, 4.0, 0.04, 0.39),
            new Seed(602, "Calvin Harris", "electronic", League.EDM, 22, 19600000, 0.071, 0.249, 0.053, 0.807, 0.177, 3.6, 0.049, 0.347),
            new Seed(603, "Marshmello", "electronic", League.EDM, 20, 58600000, 0.063, 0.217, 0.046, 0.764, 0.154, 3.1, 0.057, 0.304),
            new Seed(604, "The Chainsmokers", "electronic", League.EDM, 18, 23000000, 0.054, 0.186, 0.039, 0.721, 0.131, 2.7, 0.066, 0.261),
            new Seed(605, "Alan Walker", "electronic", League.EDM, 16, 47700000, 0.046, 0.154, 0.031, 0.679, 0.109, 2.3, 0.074, 0.219),
            new Seed(606, "Avicii", "electronic", League.EDM, 14, 21600000, 0.037, 0.123, 0.024, 0.636, 0.086, 1.9, 0.083, 0.176),
            new Seed(607, "Skrillex", "electronic", League.EDM, 12, 20500000, 0.029, 0.091, 0.017, 0.593, 0.063, 1.4, 0.091, 0.133),
            new Seed(608, "Martin Garrix", "electronic", League.EDM, 10, 15200000, 0.02, 0.06, 0.01, 0.55, 0.04, 1.0, 0.1, 0.09),
            // ---- BOLLYWOOD — real Official Artist/label channels; momentum synthetic (labelled demo) ----
            new Seed(701, "T-Series", "bollywood", League.BOLLYWOOD, 24, 314000000, 0.08, 0.28, 0.06, 0.85, 0.2, 4.0, 0.04, 0.39),
            new Seed(702, "Zee Music", "bollywood", League.BOLLYWOOD, 22, 123000000, 0.072, 0.253, 0.054, 0.812, 0.18, 3.6, 0.048, 0.353),
            new Seed(703, "Sony Music India", "bollywood", League.BOLLYWOOD, 20, 72500000, 0.065, 0.225, 0.048, 0.775, 0.16, 3.2, 0.055, 0.315),
            new Seed(704, "YRF", "bollywood", League.BOLLYWOOD, 19, 72600000, 0.057, 0.198, 0.041, 0.738, 0.14, 2.9, 0.062, 0.277),
            new Seed(705, "Tips Official", "bollywood", League.BOLLYWOOD, 17, 82200000, 0.05, 0.17, 0.035, 0.7, 0.12, 2.5, 0.07, 0.24),
            new Seed(706, "Speed Records", "bollywood", League.BOLLYWOOD, 15, 48700000, 0.042, 0.143, 0.029, 0.663, 0.1, 2.1, 0.077, 0.202),
            new Seed(707, "Arijit Singh", "bollywood", League.BOLLYWOOD, 14, 6380000, 0.035, 0.115, 0.022, 0.625, 0.08, 1.8, 0.085, 0.165),
            new Seed(708, "Neha Kakkar", "bollywood", League.BOLLYWOOD, 12, 15400000, 0.028, 0.087, 0.016, 0.588, 0.06, 1.4, 0.092, 0.128),
            new Seed(709, "Shreya Ghoshal", "bollywood", League.BOLLYWOOD, 10, 2710000, 0.02, 0.06, 0.01, 0.55, 0.04, 1.0, 0.1, 0.09),
        };
    }
}
