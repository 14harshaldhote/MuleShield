package io.muleshield.sim;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Feature rows for model training, written by the same feature code that serves. Legitimate rows
 * are sampled and carry a weight so the trained model's probabilities reflect the true base rate.
 */
final class TrainingLog implements AutoCloseable {

    private final BufferedWriter payments;
    private final BufferedWriter mules;
    private final Rng rng;
    private final double legitPaymentSample;
    private final double legitMuleSample;

    TrainingLog(Path dir, long seed, List<String> paymentFeatures, List<String> muleFeatures) {
        try {
            Files.createDirectories(dir);
            payments = Files.newBufferedWriter(dir.resolve("payments.csv"));
            mules = Files.newBufferedWriter(dir.resolve("mules.csv"));
            payments.write(String.join(",", paymentFeatures) + ",label,typology,day,weight\n");
            mules.write(String.join(",", muleFeatures) + ",label,account,day,weight\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.rng = new Rng(seed ^ 0x5eed);
        this.legitPaymentSample = 0.05;
        this.legitMuleSample = 0.05;
    }

    void payment(double[] x, Typology typology, int day) {
        boolean positive = typology.isScam();
        if (!positive && !rng.p(legitPaymentSample)) {
            return;
        }
        write(payments, x, (positive ? "1," : "0,") + typology.name() + "," + day + "," + (positive ? 1 : fmt(1 / legitPaymentSample)));
    }

    void mule(double[] x, boolean isMule, String account, int day) {
        if (!isMule && !rng.p(legitMuleSample)) {
            return;
        }
        write(mules, x, (isMule ? "1," : "0,") + Integer.toHexString(account.hashCode()) + "," + day + ","
                + (isMule ? 1 : fmt(1 / legitMuleSample)));
    }

    private static void write(BufferedWriter w, double[] x, String tail) {
        StringBuilder sb = new StringBuilder(256);
        for (double v : x) {
            sb.append(Double.isNaN(v) ? "" : fmt(v)).append(',');
        }
        sb.append(tail).append('\n');
        try {
            w.write(sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6g", v);
    }

    @Override
    public void close() throws IOException {
        payments.close();
        mules.close();
    }
}
