package io.crescendo.game.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The transparent AI opponent's draft strategy (v1.4: plays to WIN). It fields the
 * <b>highest-total-organic-score legal roster</b> — exactly {@code rosterSize} artists whose
 * salaries sum to at most {@code cap} — because the game is scored on realised growth, which
 * tracks the seam's score. (v1.1's value-per-dollar greedy under-spent and routinely lost; a
 * cap-constrained max-score roster is a genuine challenge.) Inorganic artists are excluded from the
 * roster regardless of raw score, keeping the organic-authenticity story intact.
 *
 * <p>The whole point of the opponent is that it is <i>transparent</i>: every pick carries the seam's
 * own reasons plus a plain-language rationale, and the artists it passed on are surfaced as
 * {@link Snub}s with a reason. Nothing about the bot is hidden from the player.
 *
 * <p>The algorithm is pure (no Spring, no I/O) so it is unit-testable in isolation. The pool is
 * small (a genre of ~10–15 artists, choose 5), so it finds the optimal max-score roster by bounded
 * combination search rather than a heuristic — the strongest legal team it can field.
 */
public final class OpponentDrafter {

    private OpponentDrafter() {
    }

    /**
     * A candidate artist the bot may draft. {@code score} is the seam's breakout score (null when
     * the seam is unavailable — the bot then degrades to a cheapest-roster fallback, mirroring the
     * player board's salary-implied fallback). {@code inorganic} flags artists the seam discounted.
     */
    public record Candidate(
            long artistId,
            String name,
            int salary,
            Double score,
            List<String> reasons,
            boolean inorganic) {

        /** Organic value per dollar: higher is better. Null score degrades to 0 (fallback mode). */
        double value() {
            double s = score == null ? 0.0 : score;
            return salary <= 0 ? 0.0 : s / salary;
        }
    }

    /** One artist the bot drafted, with its transparent rationale. */
    public record Selection(
            long artistId,
            String name,
            int salary,
            Double score,
            List<String> reasons,
            String rationale) {
    }

    /** An artist the bot deliberately passed on, with a shown reason (the transparent "why not"). */
    public record Snub(long artistId, String name, String reason) {
    }

    /** The bot's full transparent draft: who it took (in pick order) and who it passed on and why. */
    public record Result(List<Selection> roster, List<Snub> snubs) {
    }

    /**
     * Draft a legal roster of exactly {@code rosterSize} artists whose salaries sum to at most
     * {@code cap}, greedily maximising value per dollar.
     *
     * @throws IllegalArgumentException if no legal roster of that size fits under the cap
     */
    public static Result draft(List<Candidate> candidates, int cap, int rosterSize) {
        // The bot fields organic artists only (the authenticity story). Fall back to the full set if
        // there aren't enough organic ones to make a legal roster.
        List<Candidate> organic = candidates.stream().filter(c -> !c.inorganic()).toList();
        List<Candidate> eligible = organic.size() >= rosterSize
                ? new ArrayList<>(organic) : new ArrayList<>(candidates);

        // Deterministic order so the exact search is reproducible.
        eligible.sort(Comparator
                .comparingDouble((Candidate c) -> c.score() == null ? 0.0 : c.score()).reversed()
                .thenComparingInt(Candidate::salary)
                .thenComparingLong(Candidate::artistId));

        List<Candidate> best = bestRoster(eligible, cap, rosterSize);
        if (best == null) {
            throw new IllegalArgumentException(
                    "no legal roster of size " + rosterSize + " fits under cap " + cap);
        }

        List<Selection> roster = new ArrayList<>();
        for (Candidate c : best) {
            roster.add(new Selection(
                    c.artistId(), c.name(), c.salary(), c.score(), c.reasons(), rationaleFor(c)));
        }
        List<Snub> snubs = snubsFor(candidates, best);
        return new Result(roster, snubs);
    }

    /**
     * Exact max-total-score roster of exactly {@code rosterSize} artists under {@code cap}, by
     * bounded DFS over the (small) candidate pool. Returns null if no legal roster fits. Prefers
     * higher total score; ties broken by lower total salary (leaner win).
     */
    private static List<Candidate> bestRoster(List<Candidate> pool, int cap, int rosterSize) {
        Best best = new Best();
        dfs(pool, 0, new ArrayList<>(), 0, 0.0, cap, rosterSize, best);
        return best.roster;
    }

    private static final class Best {
        List<Candidate> roster;
        double score = Double.NEGATIVE_INFINITY;
        int salary = Integer.MAX_VALUE;
    }

    private static void dfs(List<Candidate> pool, int idx, List<Candidate> chosen, int spent,
                            double score, int cap, int rosterSize, Best best) {
        if (chosen.size() == rosterSize) {
            if (score > best.score || (score == best.score && spent < best.salary)) {
                best.roster = new ArrayList<>(chosen);
                best.score = score;
                best.salary = spent;
            }
            return;
        }
        // Prune: not enough artists left to complete the roster.
        if (pool.size() - idx < rosterSize - chosen.size()) {
            return;
        }
        for (int i = idx; i < pool.size(); i++) {
            Candidate c = pool.get(i);
            if (spent + c.salary() > cap) {
                continue;
            }
            chosen.add(c);
            double s = c.score() == null ? 0.0 : c.score();
            dfs(pool, i + 1, chosen, spent + c.salary(), score + s, cap, rosterSize, best);
            chosen.remove(chosen.size() - 1);
        }
    }

    /** Plain-language, shown-to-the-player rationale for a pick. */
    private static String rationaleFor(Candidate c) {
        String head = c.score() == null
                ? "cheapest legal fill (seam unavailable)"
                : String.format("top organic momentum: %.3f score at $%d", c.score(), c.salary());
        String reason = c.reasons() == null || c.reasons().isEmpty() ? null : c.reasons().get(0);
        return reason == null ? head : head + " — " + reason;
    }

    /**
     * The transparent "why not": the highest-value artists the bot passed on. Inorganic artists get
     * a named reason; the rest are passed for weaker value per dollar. Kept short (top 3) so the
     * reveal stays readable.
     */
    private static List<Snub> snubsFor(List<Candidate> byValue, List<Candidate> picked) {
        List<Snub> snubs = new ArrayList<>();
        for (Candidate c : byValue) {
            if (picked.contains(c)) {
                continue;
            }
            String reason = c.inorganic()
                    ? "passed: growth looks inorganic"
                    : String.format("passed: lower momentum (%.3f) or wouldn't fit the cap",
                            c.score() == null ? 0.0 : c.score());
            snubs.add(new Snub(c.artistId(), c.name(), reason));
            if (snubs.size() == 3) {
                break;
            }
        }
        return snubs;
    }
}
