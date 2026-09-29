package io.muleshield.core.payee;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Payee name check, as the UK's Confirmation of Payee and the EU's Verification of Payee define
 * the outcome: MATCH, CLOSE_MATCH (the payer sees the real name), NO_MATCH, or NOT_POSSIBLE.
 *                                                                  [EU-IPR VoP] [UK-SD17 CoP]
 * Names are compared after dropping titles, punctuation and accents and sorting the words, so
 * "Dr. Asha K. Rao" and "RAO ASHA K" match, then scored with Jaro-Winkler similarity.
 */
public final class NameMatcher {

    public enum Result { MATCH, CLOSE_MATCH, NO_MATCH, NOT_POSSIBLE }

    public record Check(Result result, String registeredName, double similarity) {
        /** 0 for a match or when the scheme shows the name itself, 0.5 for close, 1 for no match. Feeds the model. */
        public double mismatch() {
            return switch (result) {
                case MATCH, NOT_POSSIBLE -> 0.0;
                case CLOSE_MATCH -> 0.5;
                case NO_MATCH -> 1.0;
            };
        }
    }

    private static final Set<String> TITLES = Set.of("MR", "MRS", "MS", "MISS", "DR", "PROF", "SHRI", "SMT", "KUMARI",
            "SIR", "MADAM", "M/S", "MESSRS");
    private static final double MATCH = 0.96;
    private static final double CLOSE = 0.86;

    private NameMatcher() {
    }

    public static Check check(String typedName, String registeredName) {
        if (typedName == null || typedName.isBlank() || registeredName == null || registeredName.isBlank()) {
            return new Check(Result.NOT_POSSIBLE, registeredName, 0);
        }
        String a = normalise(typedName);
        String b = normalise(registeredName);
        double similarity = Math.max(jaroWinkler(a, b), initialsAware(a, b));
        Result result = similarity >= MATCH ? Result.MATCH : similarity >= CLOSE ? Result.CLOSE_MATCH : Result.NO_MATCH;
        return new Check(result, registeredName, similarity);
    }

    static String normalise(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return Arrays.stream(ascii.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9/ ]", " ").split("\\s+"))
                .filter(w -> !w.isBlank() && !TITLES.contains(w))
                .sorted()
                .collect(Collectors.joining(" "));
    }

    /** "A RAO" against "ASHA RAO": an initial standing for a full first name is close, not a match. */
    private static double initialsAware(String a, String b) {
        String[] x = a.split(" ");
        String[] y = b.split(" ");
        if (x.length != y.length) {
            return 0;
        }
        int exact = 0;
        for (int i = 0; i < x.length; i++) {
            if (x[i].equals(y[i])) {
                exact++;
            } else if (!(x[i].length() == 1 && y[i].startsWith(x[i]) || y[i].length() == 1 && x[i].startsWith(y[i]))) {
                return 0;
            }
        }
        return exact == x.length ? 1.0 : CLOSE;
    }

    static double jaroWinkler(String s, String t) {
        if (s.equals(t)) {
            return 1.0;
        }
        int range = Math.max(0, Math.max(s.length(), t.length()) / 2 - 1);
        boolean[] sMatched = new boolean[s.length()];
        boolean[] tMatched = new boolean[t.length()];
        int matches = 0;
        for (int i = 0; i < s.length(); i++) {
            for (int j = Math.max(0, i - range); j < Math.min(t.length(), i + range + 1); j++) {
                if (!tMatched[j] && s.charAt(i) == t.charAt(j)) {
                    sMatched[i] = true;
                    tMatched[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0;
        }
        int transpositions = 0;
        for (int i = 0, j = 0; i < s.length(); i++) {
            if (sMatched[i]) {
                while (!tMatched[j]) {
                    j++;
                }
                if (s.charAt(i) != t.charAt(j)) {
                    transpositions++;
                }
                j++;
            }
        }
        double m = matches;
        double jaro = (m / s.length() + m / t.length() + (m - transpositions / 2.0) / m) / 3.0;
        int prefix = 0;
        while (prefix < Math.min(4, Math.min(s.length(), t.length())) && s.charAt(prefix) == t.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }
}
