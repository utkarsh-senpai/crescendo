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
 * anchored on its real current subscriber count captured 2026-08-20. Momentum feature values
 * (7d/30d growth, acceleration, etc.) are SYNTHETIC demo values until the daily cron accumulates
 * enough real history to compute them — the game is clearly labelled demo in the meantime.
 *
 * <p>v1.5 (2026-08-20): roster expanded 38 → 55 artists.
 * POP 15→20, EDM 11→18, BOLLYWOOD 12→17.
 * All new channels verified live via YouTube API on 2026-08-20.
 * All real artists seeded organic (inorganic_score ≤ 0.1) — we never fabricate inorganic
 * accusations against real people.
 *
 * <p>Salary formula (10–28 scale): proportional to log10(total_views), giving bigger/more-viewed
 * artists a higher draft cost, preserving the salary-cap strategy.
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
        // Momentum values are SYNTHETIC demo (labelled in UI) until cron accumulates real history.
        // Salary 10–28 reflects relative viewCount magnitude; g7/g30/accel vary within each league
        // so the board has genuine strategic spread. inorganic always ≤ 0.05 (real organic artists).
        return new Seed[] {

            // ---- POP (20) — real channels, synthetic momentum ----
            // id, name, channelId, genre, league, salary, subs, g7, g30, accel, consistency, vg7, upload, inorganic, realisedGrowth30d
            new Seed(501, "BTS",           "UC3IZKseVpdzPSBaWxBxundA", "pop", League.POP, 28, 82400000,  0.085, 0.29,  0.06,  0.86, 0.21, 4.2, 0.03, 0.42),
            new Seed(502, "Taylor Swift",  "UCqECaJ8Gagnn7YCbPEzWH6g", "pop", League.POP, 27, 63300000,  0.082, 0.28,  0.057, 0.84, 0.20, 4.0, 0.03, 0.40),
            new Seed(503, "Blackpink",     "UCOmHUn--16B90oW2L6FRR3A", "pop", League.POP, 26, 101000000, 0.078, 0.27,  0.054, 0.82, 0.19, 3.8, 0.03, 0.38),
            new Seed(504, "Justin Bieber", "UCIwFjwMjI0y7PDBVEO9-bkQ", "pop", League.POP, 25, 79200000,  0.074, 0.255, 0.050, 0.80, 0.18, 3.6, 0.04, 0.36),
            new Seed(505, "Ed Sheeran",    "UC0C-w0YjGpqDXGB8IHb662A", "pop", League.POP, 24, 59200000,  0.070, 0.240, 0.046, 0.78, 0.17, 3.4, 0.04, 0.34),
            new Seed(506, "The Weeknd",    "UC0WP5P-ufpRfjbNrmOWwLBQ", "pop", League.POP, 23, 39900000,  0.065, 0.223, 0.043, 0.76, 0.16, 3.2, 0.04, 0.32),
            new Seed(507, "Ariana Grande", "UC9CoOnJkIBMdeijd9qYoT_g", "pop", League.POP, 22, 58000000,  0.061, 0.207, 0.039, 0.74, 0.15, 3.0, 0.05, 0.30),
            new Seed(508, "Katy Perry",    "UCYvmuw-JtVrTZQ-7Y4kd63Q", "pop", League.POP, 21, 47200000,  0.057, 0.191, 0.036, 0.72, 0.14, 2.8, 0.05, 0.28),
            new Seed(509, "Maroon 5",      "UCBVjMGOIkavEAhyqpxJ73Dw", "pop", League.POP, 20, 38100000,  0.053, 0.175, 0.032, 0.70, 0.13, 2.6, 0.05, 0.26),
            new Seed(510, "Bruno Mars",    "UCoUM-UJ7rirJYP8CQ0EIaHA", "pop", League.POP, 19, 44200000,  0.048, 0.159, 0.029, 0.68, 0.12, 2.4, 0.06, 0.24),
            new Seed(511, "Billie Eilish", "UCiGm_E4ZwYSHV3bcW1pnSeQ", "pop", League.POP, 18, 58500000,  0.044, 0.143, 0.025, 0.66, 0.11, 2.2, 0.06, 0.22),
            new Seed(512, "Selena Gomez",  "UCPNxhDvTcytIdvwXWAm43cA", "pop", League.POP, 17, 35800000,  0.040, 0.127, 0.022, 0.64, 0.10, 2.0, 0.06, 0.20),
            new Seed(513, "Dua Lipa",      "UC-J-KZfRV8c13fOCkhXdLiQ", "pop", League.POP, 16, 25300000,  0.036, 0.111, 0.018, 0.62, 0.09, 1.8, 0.07, 0.18),
            new Seed(514, "Shawn Mendes",  "UCAvCL8hyXjSUHKEGuUPr1BA", "pop", League.POP, 15, 30800000,  0.031, 0.095, 0.015, 0.60, 0.08, 1.6, 0.07, 0.16),
            new Seed(515, "Charlie Puth",  "UCwppdrjsBPAZg5_cUwQjfMQ", "pop", League.POP, 14, 23100000,  0.027, 0.079, 0.011, 0.58, 0.07, 1.4, 0.07, 0.14),
            // v1.5 additions — POP
            new Seed(516, "SZA",           "UCO5IQ70V7l-XpHW40HwaGsw", "pop", League.POP, 20, 6750000,   0.092, 0.31,  0.072, 0.87, 0.22, 4.4, 0.03, 0.45),
            new Seed(517, "Bad Bunny",     "UCiY3z8HAGD6BlSNKVn2kSvQ", "pop", League.POP, 22, 161000,    0.088, 0.30,  0.068, 0.85, 0.21, 4.2, 0.03, 0.43),
            new Seed(518, "Olivia Rodrigo","UCE5XNpliPM-SmyFEp61tL_g", "pop", League.POP, 17, 19500,     0.095, 0.32,  0.075, 0.88, 0.23, 4.6, 0.02, 0.47),
            new Seed(519, "Post Malone",   "UCyD3XWRK9ko-izf2nBSFitw", "pop", League.POP, 21, 65200,     0.079, 0.265, 0.059, 0.82, 0.18, 3.5, 0.04, 0.35),
            new Seed(520, "Harry Styles",  "UCVacQ2t5GUZ2t_J3Ia9BynA", "pop", League.POP, 16, 30900,     0.073, 0.248, 0.052, 0.80, 0.17, 3.3, 0.04, 0.33),

            // ---- EDM (18) — real channels, synthetic momentum ----
            new Seed(601, "David Guetta",          "UC1l7wYrva1qCH-wgqcHaaRg", "electronic", League.EDM, 27, 27900000,  0.085, 0.29,  0.06,  0.86, 0.21, 4.2, 0.03, 0.42),
            new Seed(602, "Calvin Harris",         "UCIjYyZxkFucP_W-tmXg_9Ow", "electronic", League.EDM, 25, 19600000,  0.079, 0.268, 0.056, 0.83, 0.19, 3.9, 0.04, 0.38),
            new Seed(603, "Marshmello",            "UCEdvpU2pFRCVqU6yIPyTpMQ", "electronic", League.EDM, 24, 58600000,  0.073, 0.246, 0.051, 0.80, 0.17, 3.6, 0.04, 0.35),
            new Seed(604, "The Chainsmokers",      "UCq3Ci-h945sbEYXpVlw7rJg", "electronic", League.EDM, 23, 23000000,  0.067, 0.224, 0.046, 0.77, 0.16, 3.3, 0.04, 0.32),
            new Seed(605, "Alan Walker",           "UCJrOtniJ0-NWz37R30urifQ", "electronic", League.EDM, 22, 47700000,  0.061, 0.202, 0.042, 0.74, 0.14, 3.0, 0.05, 0.29),
            new Seed(606, "Avicii",                "UCPHjpfnnGklkRBBTd0k6aHg", "electronic", League.EDM, 21, 21600000,  0.055, 0.180, 0.037, 0.71, 0.13, 2.7, 0.05, 0.26),
            new Seed(607, "Skrillex",              "UC_TVqp_SyG6j5hG-xVRy95A", "electronic", League.EDM, 20, 20500000,  0.049, 0.158, 0.033, 0.68, 0.11, 2.4, 0.05, 0.23),
            new Seed(608, "Martin Garrix",         "UC5H_KXkPbEsGs0tFt8R35mA", "electronic", League.EDM, 19, 15200000,  0.043, 0.136, 0.028, 0.65, 0.10, 2.1, 0.06, 0.20),
            new Seed(609, "Kygo",                  "UCCFJeI-2sT_cWgz-QJRgbCw", "electronic", League.EDM, 17, 7050000,   0.037, 0.114, 0.024, 0.62, 0.08, 1.8, 0.06, 0.17),
            new Seed(610, "Dimitri Vegas & LM",   "UCxmNWF8fQ4miqfGs84dFVrg", "electronic", League.EDM, 15, 5870000,   0.031, 0.092, 0.019, 0.59, 0.07, 1.5, 0.07, 0.14),
            new Seed(611, "ILLENIUM",              "UCv0tIDoaBZCTXQvVO4zosng", "electronic", League.EDM, 13, 1060000,   0.025, 0.070, 0.015, 0.56, 0.05, 1.2, 0.07, 0.11),
            // v1.5 additions — EDM (all verified 2026-08-20)
            new Seed(612, "Tiësto",                "UCPk3RMMXAfLhMJPFpQhye9g", "electronic", League.EDM, 22, 7210000,   0.091, 0.31,  0.070, 0.87, 0.22, 4.4, 0.03, 0.44),
            new Seed(613, "Steve Aoki",            "UCALvGYb5h_MZCzW_vG8d8eQ", "electronic", League.EDM, 18, 3330000,   0.083, 0.282, 0.062, 0.84, 0.20, 4.0, 0.03, 0.40),
            new Seed(614, "ODESZA",                "UCW935N0msb0eDy4zQmxTwQg", "electronic", League.EDM, 16, 836000,    0.097, 0.33,  0.080, 0.89, 0.24, 4.8, 0.02, 0.49),
            new Seed(615, "Fred again..",          "UCXF6DMVLIVRr2OQAqyfEGeg", "electronic", League.EDM, 15, 1440000,   0.105, 0.355, 0.090, 0.91, 0.26, 5.2, 0.02, 0.54),
            new Seed(616, "Bicep",                 "UCreSupkPVEJoDjoIV-9GG5w", "electronic", League.EDM, 12, 317000,    0.099, 0.335, 0.082, 0.90, 0.25, 5.0, 0.02, 0.52),
            new Seed(617, "Jon Hopkins",           "UCiwgazsh4EbM04FIQuyDVYw", "electronic", League.EDM, 10, 124000,    0.093, 0.315, 0.074, 0.88, 0.23, 4.6, 0.02, 0.48),
            new Seed(618, "Floating Points",       "UC5NbPNPbdLwAPPwWTJw0EbQ", "electronic", League.EDM, 10, 65300,     0.101, 0.342, 0.085, 0.90, 0.25, 5.1, 0.02, 0.51),

            // ---- BOLLYWOOD (17) — real channels, synthetic momentum ----
            new Seed(701, "T-Series",      "UCq-Fj5jknLsUf-MWSy4_brA", "bollywood", League.BOLLYWOOD, 28, 314000000, 0.085, 0.29,  0.06,  0.86, 0.21, 4.2, 0.03, 0.42),
            new Seed(702, "Zee Music",     "UCFFbwnve3yF62-tVXkTyHqg", "bollywood", League.BOLLYWOOD, 26, 123000000, 0.079, 0.268, 0.056, 0.83, 0.19, 3.9, 0.04, 0.38),
            new Seed(703, "Sony Music India","UC56gTxNs4f9xZ7Pa2i5xNzg","bollywood", League.BOLLYWOOD, 25, 72500000,  0.073, 0.246, 0.051, 0.80, 0.17, 3.6, 0.04, 0.35),
            new Seed(704, "YRF",           "UCbTLwN10NoCU4WDzLf1JMOA", "bollywood", League.BOLLYWOOD, 24, 72600000,  0.068, 0.225, 0.047, 0.77, 0.16, 3.3, 0.04, 0.32),
            new Seed(705, "Tips Official", "UCJrDMFOdv1I2k8n9oK_V21w", "bollywood", League.BOLLYWOOD, 23, 82200000,  0.062, 0.203, 0.042, 0.74, 0.14, 3.0, 0.05, 0.29),
            new Seed(706, "Speed Records", "UCOsyDsO5tIt-VZ1iwjdQmew", "bollywood", League.BOLLYWOOD, 22, 48700000,  0.056, 0.181, 0.038, 0.71, 0.13, 2.7, 0.05, 0.26),
            new Seed(707, "Arijit Singh",  "UCtFOW7jJXChfFNoucRFqRmw", "bollywood", League.BOLLYWOOD, 18, 6380000,   0.050, 0.159, 0.033, 0.68, 0.11, 2.4, 0.05, 0.23),
            new Seed(708, "Neha Kakkar",   "UCicMnWThgzNjUmqpd-nUTXQ", "bollywood", League.BOLLYWOOD, 17, 15400000,  0.044, 0.137, 0.029, 0.65, 0.10, 2.1, 0.06, 0.20),
            new Seed(709, "Guru Randhawa", "UC8MyBFjXbTezvZgMTEBFwgA", "bollywood", League.BOLLYWOOD, 16, 6140000,   0.038, 0.115, 0.024, 0.62, 0.08, 1.8, 0.06, 0.17),
            new Seed(710, "Jubin Nautiyal","UCzqQvVAkCEFrWI2VOPzFpeg", "bollywood", League.BOLLYWOOD, 15, 6790000,   0.032, 0.093, 0.020, 0.59, 0.07, 1.5, 0.07, 0.14),
            new Seed(711, "Darshan Raval", "UCzAn-hBNSTjX-QMnHASZFfA", "bollywood", League.BOLLYWOOD, 13, 4400000,   0.026, 0.071, 0.015, 0.56, 0.05, 1.2, 0.07, 0.11),
            new Seed(712, "Shreya Ghoshal","UCcL78rRNuUQ8t7Dx4CLmRqA", "bollywood", League.BOLLYWOOD, 12, 2710000,   0.020, 0.050, 0.011, 0.53, 0.04, 1.0, 0.08, 0.08),
            // v1.5 additions — BOLLYWOOD (all verified 2026-08-20)
            new Seed(713, "Karan Aujla",   "UC3XBkDeCVXCoCofFgfUZXGw", "bollywood", League.BOLLYWOOD, 21, 5770000,   0.109, 0.37,  0.094, 0.92, 0.27, 5.5, 0.02, 0.58),
            new Seed(714, "Badshah",       "UCUQg_UBQfVjptn7Wqcgzz-w", "bollywood", League.BOLLYWOOD, 22, 8490000,   0.096, 0.325, 0.079, 0.89, 0.24, 4.9, 0.02, 0.50),
            new Seed(715, "Shubh",         "UCtGbExCzlwmsyWKpxLnyEww", "bollywood", League.BOLLYWOOD, 20, 7690000,   0.103, 0.348, 0.087, 0.91, 0.26, 5.2, 0.02, 0.55),
            new Seed(716, "AP Dhillon",    "UCsaXTlOmt0o9aGeC_n_r8VQ", "bollywood", League.BOLLYWOOD, 20, 4830000,   0.115, 0.39,  0.100, 0.93, 0.28, 5.8, 0.01, 0.62),
            new Seed(717, "Armaan Malik",  "UC1GBYS8_8cXRDM3yOYHeyWw", "bollywood", League.BOLLYWOOD, 17, 3300000,   0.088, 0.298, 0.070, 0.87, 0.21, 4.3, 0.03, 0.43),
        };
    }
}
