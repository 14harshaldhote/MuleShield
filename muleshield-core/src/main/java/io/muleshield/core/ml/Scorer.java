package io.muleshield.core.ml;

/**
 * Turns a feature vector into a probability and says how much each feature pushed it, in
 * log-odds. The contributions are what make every decision explainable to an analyst, a customer
 * and a regulator.
 */
public interface Scorer {

    String version();

    Score score(double[] features);

    /**
     * @param probability   0..1
     * @param bias          the log-odds before any feature is looked at
     * @param contributions per feature, in log-odds; bias + sum = logit(probability)
     */
    record Score(double probability, double bias, double[] contributions) {
    }

    static double sigmoid(double margin) {
        return 1.0 / (1.0 + Math.exp(-margin));
    }
}
