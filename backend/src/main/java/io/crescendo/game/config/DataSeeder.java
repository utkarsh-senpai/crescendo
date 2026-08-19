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
    private record Seed(long id, String name, String channelId, String genre, League league,
                        int salary, double subs, double g7, double g30, double accel,
                        double consistency, double vg7, double upload, double inorganic,
                        double realisedGrowth30d) {
    }

    @Bean
    ApplicationRunner seed(ArtistRepository artists, ArtistFeatureSnapshotRepository snapshots,
                           GameRules rules) {
        return args -> {
            Seed[] seeds = allSeeds();
            // Self-healing seed: (re)seed when the artist table is empty, OR when it holds a stale
            // pre-v1.4 set — a different row count than expected, or any artist missing a channelId
            // (the column didn't exist before v1.4, so a v1.3-seeded prod DB has nulls and the
            // live-stats lookup finds nothing). Reference data only; safe to rebuild. Idempotent:
            // a correct, complete seed is left untouched.
            long count = artists.count();
            boolean stale = count != seeds.length || artists.countByChannelIdIsNull() > 0;
            if (count > 0 && !stale) {
                return;
            }
            if (count > 0) {
                log.info("Re-seeding stale artist data (had {}, expected {}, channelId-null={})",
                        count, seeds.length, artists.countByChannelIdIsNull());
                snapshots.deleteAllInBatch();
                artists.deleteAllInBatch();
            }
            LocalDate draft = rules.getDraftAsOfDate();
            LocalDate score = rules.getScoreAsOfDate();

            for (Seed s : seeds) {
                artists.save(new Artist(
                        s.id(), s.name(), s.channelId(), s.genre(), s.league(), s.salary()));
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
            // ---- POP (15) — real channels; momentum synthetic (labelled demo) ----
            new Seed(501, "BTS", "UC3IZKseVpdzPSBaWxBxundA", "pop", League.POP, 24, 82400000, 0.08, 0.28, 0.06, 0.85, 0.2, 4.0, 0.04, 0.39),
            new Seed(502, "Taylor Swift", "UCqECaJ8Gagnn7YCbPEzWH6g", "pop", League.POP, 23, 63300000, 0.076, 0.264, 0.056, 0.829, 0.189, 3.8, 0.044, 0.369),
            new Seed(503, "Blackpink", "UCOmHUn--16B90oW2L6FRR3A", "pop", League.POP, 22, 101000000, 0.071, 0.249, 0.053, 0.807, 0.177, 3.6, 0.049, 0.347),
            new Seed(504, "Justin Bieber", "UCIwFjwMjI0y7PDBVEO9-bkQ", "pop", League.POP, 21, 79200000, 0.067, 0.233, 0.049, 0.786, 0.166, 3.4, 0.053, 0.326),
            new Seed(505, "Ed Sheeran", "UC0C-w0YjGpqDXGB8IHb662A", "pop", League.POP, 20, 59200000, 0.063, 0.217, 0.046, 0.764, 0.154, 3.1, 0.057, 0.304),
            new Seed(506, "The Weeknd", "UC0WP5P-ufpRfjbNrmOWwLBQ", "pop", League.POP, 19, 39900000, 0.059, 0.201, 0.042, 0.743, 0.143, 2.9, 0.061, 0.283),
            new Seed(507, "Ariana Grande", "UC9CoOnJkIBMdeijd9qYoT_g", "pop", League.POP, 18, 58000000, 0.054, 0.186, 0.039, 0.721, 0.131, 2.7, 0.066, 0.261),
            new Seed(508, "Katy Perry", "UCYvmuw-JtVrTZQ-7Y4kd63Q", "pop", League.POP, 17, 47200000, 0.05, 0.17, 0.035, 0.7, 0.12, 2.5, 0.07, 0.24),
            new Seed(509, "Maroon 5", "UCBVjMGOIkavEAhyqpxJ73Dw", "pop", League.POP, 16, 38100000, 0.046, 0.154, 0.031, 0.679, 0.109, 2.3, 0.074, 0.219),
            new Seed(510, "Bruno Mars", "UCoUM-UJ7rirJYP8CQ0EIaHA", "pop", League.POP, 15, 44200000, 0.041, 0.139, 0.028, 0.657, 0.097, 2.1, 0.079, 0.197),
            new Seed(511, "Billie Eilish", "UCiGm_E4ZwYSHV3bcW1pnSeQ", "pop", League.POP, 14, 58500000, 0.037, 0.123, 0.024, 0.636, 0.086, 1.9, 0.083, 0.176),
            new Seed(512, "Selena Gomez", "UCPNxhDvTcytIdvwXWAm43cA", "pop", League.POP, 13, 35800000, 0.033, 0.107, 0.021, 0.614, 0.074, 1.6, 0.087, 0.154),
            new Seed(513, "Dua Lipa", "UC-J-KZfRV8c13fOCkhXdLiQ", "pop", League.POP, 12, 25300000, 0.029, 0.091, 0.017, 0.593, 0.063, 1.4, 0.091, 0.133),
            new Seed(514, "Shawn Mendes", "UCAvCL8hyXjSUHKEGuUPr1BA", "pop", League.POP, 11, 30800000, 0.024, 0.076, 0.014, 0.571, 0.051, 1.2, 0.096, 0.111),
            new Seed(515, "Charlie Puth", "UCwppdrjsBPAZg5_cUwQjfMQ", "pop", League.POP, 10, 23100000, 0.02, 0.06, 0.01, 0.55, 0.04, 1.0, 0.1, 0.09),
            // ---- EDM (11) — real channels; momentum synthetic (labelled demo) ----
            new Seed(601, "David Guetta", "UC1l7wYrva1qCH-wgqcHaaRg", "electronic", League.EDM, 24, 27900000, 0.08, 0.28, 0.06, 0.85, 0.2, 4.0, 0.04, 0.39),
            new Seed(602, "Calvin Harris", "UCIjYyZxkFucP_W-tmXg_9Ow", "electronic", League.EDM, 23, 19600000, 0.074, 0.258, 0.055, 0.82, 0.184, 3.7, 0.046, 0.36),
            new Seed(603, "Marshmello", "UCEdvpU2pFRCVqU6yIPyTpMQ", "electronic", League.EDM, 21, 58600000, 0.068, 0.236, 0.05, 0.79, 0.168, 3.4, 0.052, 0.33),
            new Seed(604, "The Chainsmokers", "UCq3Ci-h945sbEYXpVlw7rJg", "electronic", League.EDM, 20, 23000000, 0.062, 0.214, 0.045, 0.76, 0.152, 3.1, 0.058, 0.3),
            new Seed(605, "Alan Walker", "UCJrOtniJ0-NWz37R30urifQ", "electronic", League.EDM, 18, 47700000, 0.056, 0.192, 0.04, 0.73, 0.136, 2.8, 0.064, 0.27),
            new Seed(606, "Avicii", "UCPHjpfnnGklkRBBTd0k6aHg", "electronic", League.EDM, 17, 21600000, 0.05, 0.17, 0.035, 0.7, 0.12, 2.5, 0.07, 0.24),
            new Seed(607, "Skrillex", "UC_TVqp_SyG6j5hG-xVRy95A", "electronic", League.EDM, 16, 20500000, 0.044, 0.148, 0.03, 0.67, 0.104, 2.2, 0.076, 0.21),
            new Seed(608, "Martin Garrix", "UC5H_KXkPbEsGs0tFt8R35mA", "electronic", League.EDM, 14, 15200000, 0.038, 0.126, 0.025, 0.64, 0.088, 1.9, 0.082, 0.18),
            new Seed(609, "Kygo", "UCCFJeI-2sT_cWgz-QJRgbCw", "electronic", League.EDM, 13, 7050000, 0.032, 0.104, 0.02, 0.61, 0.072, 1.6, 0.088, 0.15),
            new Seed(610, "Dimitri Vegas & Like Mike", "UCxmNWF8fQ4miqfGs84dFVrg", "electronic", League.EDM, 11, 5870000, 0.026, 0.082, 0.015, 0.58, 0.056, 1.3, 0.094, 0.12),
            new Seed(611, "ILLENIUM", "UCv0tIDoaBZCTXQvVO4zosng", "electronic", League.EDM, 10, 1060000, 0.02, 0.06, 0.01, 0.55, 0.04, 1.0, 0.1, 0.09),
            // ---- BOLLYWOOD (12) — real channels; momentum synthetic (labelled demo) ----
            new Seed(701, "T-Series", "UCq-Fj5jknLsUf-MWSy4_brA", "bollywood", League.BOLLYWOOD, 24, 314000000, 0.08, 0.28, 0.06, 0.85, 0.2, 4.0, 0.04, 0.39),
            new Seed(702, "Zee Music", "UCFFbwnve3yF62-tVXkTyHqg", "bollywood", League.BOLLYWOOD, 23, 123000000, 0.075, 0.26, 0.055, 0.823, 0.185, 3.7, 0.045, 0.363),
            new Seed(703, "Sony Music India", "UC56gTxNs4f9xZ7Pa2i5xNzg", "bollywood", League.BOLLYWOOD, 21, 72500000, 0.069, 0.24, 0.051, 0.795, 0.171, 3.5, 0.051, 0.335),
            new Seed(704, "YRF", "UCbTLwN10NoCU4WDzLf1JMOA", "bollywood", League.BOLLYWOOD, 20, 72600000, 0.064, 0.22, 0.046, 0.768, 0.156, 3.2, 0.056, 0.308),
            new Seed(705, "Tips Official", "UCJrDMFOdv1I2k8n9oK_V21w", "bollywood", League.BOLLYWOOD, 19, 82200000, 0.058, 0.2, 0.042, 0.741, 0.142, 2.9, 0.062, 0.281),
            new Seed(706, "Speed Records", "UCOsyDsO5tIt-VZ1iwjdQmew", "bollywood", League.BOLLYWOOD, 18, 48700000, 0.053, 0.18, 0.037, 0.714, 0.127, 2.6, 0.067, 0.254),
            new Seed(707, "Arijit Singh", "UCtFOW7jJXChfFNoucRFqRmw", "bollywood", League.BOLLYWOOD, 16, 6380000, 0.047, 0.16, 0.033, 0.686, 0.113, 2.4, 0.073, 0.226),
            new Seed(708, "Neha Kakkar", "UCicMnWThgzNjUmqpd-nUTXQ", "bollywood", League.BOLLYWOOD, 15, 15400000, 0.042, 0.14, 0.028, 0.659, 0.098, 2.1, 0.078, 0.199),
            new Seed(709, "Guru Randhawa", "UC8MyBFjXbTezvZgMTEBFwgA", "bollywood", League.BOLLYWOOD, 14, 6140000, 0.036, 0.12, 0.024, 0.632, 0.084, 1.8, 0.084, 0.172),
            new Seed(710, "Jubin Nautiyal", "UCzqQvVAkCEFrWI2VOPzFpeg", "bollywood", League.BOLLYWOOD, 13, 6790000, 0.031, 0.1, 0.019, 0.605, 0.069, 1.5, 0.089, 0.145),
            new Seed(711, "Darshan Raval", "UCzAn-hBNSTjX-QMnHASZFfA", "bollywood", League.BOLLYWOOD, 11, 4400000, 0.025, 0.08, 0.015, 0.577, 0.055, 1.3, 0.095, 0.117),
            new Seed(712, "Shreya Ghoshal", "UCcL78rRNuUQ8t7Dx4CLmRqA", "bollywood", League.BOLLYWOOD, 10, 2710000, 0.02, 0.06, 0.01, 0.55, 0.04, 1.0, 0.1, 0.09),
        };
    }
}
