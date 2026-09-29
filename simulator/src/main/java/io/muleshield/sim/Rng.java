package io.muleshield.sim;

import java.util.List;
import java.util.SplittableRandom;

/** Seeded randomness with the few distributions the world needs. */
final class Rng {

    private final SplittableRandom r;

    Rng(long seed) {
        this.r = new SplittableRandom(seed);
    }

    double u() {
        return r.nextDouble();
    }

    double u(double lo, double hi) {
        return lo + (hi - lo) * r.nextDouble();
    }

    int i(int lo, int hiInclusive) {
        return r.nextInt(lo, hiInclusive + 1);
    }

    boolean p(double probability) {
        return r.nextDouble() < probability;
    }

    double normal() {
        double u1 = Math.max(r.nextDouble(), 1e-12);
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * r.nextDouble());
    }

    /** Log-normal with the given median. */
    double logNormal(double median, double sigma) {
        return median * Math.exp(sigma * normal());
    }

    int poisson(double lambda) {
        if (lambda <= 0) {
            return 0;
        }
        if (lambda > 30) {
            return Math.max(0, (int) Math.round(lambda + Math.sqrt(lambda) * normal()));
        }
        double l = Math.exp(-lambda);
        int k = 0;
        double p = 1;
        do {
            k++;
            p *= r.nextDouble();
        } while (p > l);
        return k - 1;
    }

    <T> T pick(List<T> items) {
        return items.get(r.nextInt(items.size()));
    }

    Rng split() {
        return new Rng(r.nextLong());
    }
}
