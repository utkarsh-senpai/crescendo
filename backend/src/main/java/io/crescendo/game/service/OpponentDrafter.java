package io.crescendo.game.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The transparent AI opponent's draft strategy: a greedy <b>value</b> drafter that maximises
 * organic breakout score per dollar under the same salary cap and roster size as the player.
 *
 * <p>The whole point of the v1.1 opponent is that it is <i>transparent</i>: every pick carries the
 * seam's own reasons plus a plain-language rationale ("best organic value at this price"), and the
 * artists it passed on are surfaced as {@link Snub}s with a reason ("passed: growth looks
 * inorganic", "passed: weaker value per $"). Nothing about the bot is hidden from the player.
 *
 * <p>The algorithm is pure (no Spring, no I/O) so it is unit-testable in isolation. It sorts
 * candidates by value ({@code score / salary}) descending and greedily fills the roster, but guards
 * each pick with a feasibility check: it only takes a candidate if the roster can still be completed
 * within the remaining cap using the cheapest players left. That guard matters because a naive
 * value-greedy could spend early and be unable to field a legal roster (e.g. 5 × the priciest
 * artist would blow a cap that any five-artist roster must respect).
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
        // Highest value first; break ties by higher score, then cheaper, then id (deterministic).
        List<Candidate> byValue = new ArrayList<>(candidates);
        byValue.sort(Comparator
                .comparingDouble(Candidate::value).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (Candidate c) -> c.score() == null ? 0.0 : c.score()).reversed())
                .thenComparingInt(Candidate::salary)
                .thenComparingLong(Candidate::artistId));

        List<Candidate> remaining = new ArrayList<>(byValue);
        List<Candidate> picked = new ArrayList<>();
        int spent = 0;

        for (Candidate c : byValue) {
            if (picked.size() == rosterSize) {
                break;
            }
            int slotsLeft = rosterSize - picked.size();
            // Feasibility: after taking c, can we still fill the remaining slots under the cap using
            // the cheapest artists left (excluding c)? If not, skip c and keep it as a fallback.
            if (c.salary() + spent > cap) {
                continue;
            }
            List<Candidate> others = new ArrayList<>(remaining);
            others.remove(c);
            if (canComplete(others, slotsLeft - 1, cap - spent - c.salary())) {
                picked.add(c);
                remaining.remove(c);
                spent += c.salary();
            }
        }

        if (picked.size() != rosterSize) {
            throw new IllegalArgumentException(
                    "no legal roster of size " + rosterSize + " fits under cap " + cap);
        }

        List<Selection> roster = new ArrayList<>();
        for (Candidate c : picked) {
            roster.add(new Selection(
                    c.artistId(), c.name(), c.salary(), c.score(), c.reasons(), rationaleFor(c)));
        }
        List<Snub> snubs = snubsFor(byValue, picked);
        return new Result(roster, snubs);
    }

    /** Can we still pick {@code slots} more artists from {@code pool} without exceeding {@code budget}? */
    private static boolean canComplete(List<Candidate> pool, int slots, int budget) {
        if (slots <= 0) {
            return budget >= 0;
        }
        if (pool.size() < slots) {
            return false;
        }
        int cheapest = pool.stream()
                .mapToInt(Candidate::salary)
                .sorted()
                .limit(slots)
                .sum();
        return cheapest <= budget;
    }

    /** Plain-language, shown-to-the-player rationale for a pick. */
    private static String rationaleFor(Candidate c) {
        String head = c.score() == null
                ? "cheapest legal fill (seam unavailable)"
                : String.format("best organic value: %.3f score at $%d (%.4f per $)",
                        c.score(), c.salary(), c.value());
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
                    : String.format("passed: weaker value per $ (%.4f)", c.value());
            snubs.add(new Snub(c.artistId(), c.name(), reason));
            if (snubs.size() == 3) {
                break;
            }
        }
        return snubs;
    }
}
