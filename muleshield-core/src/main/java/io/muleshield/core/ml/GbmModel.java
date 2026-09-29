package io.muleshield.core.ml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A gradient-boosted tree model trained in Python with XGBoost and evaluated here in plain Java:
 * no native library, no Python sidecar, microseconds per payment.
 * <p>
 * It reads XGBoost's own JSON model format and computes, besides the probability, a per-feature
 * contribution for every prediction using Saabas' path attribution (XGBoost's
 * {@code approx_contribs}): walking the decision path, each split credits its feature with the
 * change in the node's expected value. A parity test checks both the probabilities and the
 * contributions against XGBoost's own output.
 */
public final class GbmModel implements Scorer {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String version;
    private final double baseMargin;
    private final Tree[] trees;
    private final int featureCount;

    private GbmModel(String version, double baseMargin, Tree[] trees, int featureCount) {
        this.version = version;
        this.baseMargin = baseMargin;
        this.trees = trees;
        this.featureCount = featureCount;
    }

    /**
     * @param expectedFeatures the feature names the serving code produces, in order; loading fails
     *                         if the model was trained on anything else
     */
    public static GbmModel load(InputStream json, List<String> expectedFeatures) {
        try {
            byte[] bytes = json.readAllBytes();
            JsonNode root = JSON.readTree(bytes);
            JsonNode learner = root.path("learner");
            String objective = learner.path("objective").path("name").asString();
            if (!"binary:logistic".equals(objective)) {
                throw new IllegalArgumentException("Expected a binary:logistic model, got " + objective);
            }
            List<String> names = learner.path("feature_names").valueStream().map(JsonNode::asString).toList();
            if (!names.equals(expectedFeatures)) {
                throw new IllegalArgumentException("Model features " + names + " don't match serving features " + expectedFeatures);
            }
            double baseScore = Double.parseDouble(learner.path("learner_model_param").path("base_score").asString()
                    .replace("[", "").replace("]", ""));
            JsonNode treeNodes = learner.path("gradient_booster").path("model").path("trees");
            Tree[] trees = new Tree[treeNodes.size()];
            for (int i = 0; i < trees.length; i++) {
                trees[i] = Tree.parse(treeNodes.get(i));
            }
            String version = "gbm-" + HexFormat.of().formatHex(sha256(bytes)).substring(0, 12);
            return new GbmModel(version, logit(baseScore), trees, names.size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String version() {
        return version;
    }

    public int treeCount() {
        return trees.length;
    }

    @Override
    public Score score(double[] x) {
        if (x.length != featureCount) {
            throw new IllegalArgumentException("Expected " + featureCount + " features, got " + x.length);
        }
        double[] contributions = new double[featureCount];
        double margin = baseMargin;
        double bias = baseMargin;
        for (Tree tree : trees) {
            int node = 0;
            bias += tree.mean[0];
            while (tree.left[node] >= 0) {
                int feature = tree.feature[node];
                double value = x[feature];
                int next = Double.isNaN(value)
                        ? (tree.defaultLeft[node] ? tree.left[node] : tree.right[node])
                        : ((float) value < tree.threshold[node] ? tree.left[node] : tree.right[node]);
                contributions[feature] += tree.mean[next] - tree.mean[node];
                node = next;
            }
            margin += tree.value[node];
        }
        return new Score(Scorer.sigmoid(margin), bias, contributions);
    }

    private static double logit(double p) {
        return Math.log(p / (1 - p));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One tree in XGBoost's array layout. {@code mean} is each node's cover-weighted expected leaf value. */
    private record Tree(int[] left, int[] right, int[] feature, float[] threshold, boolean[] defaultLeft,
                        double[] value, double[] mean) {

        static Tree parse(JsonNode t) {
            int[] left = ints(t.path("left_children"));
            int[] right = ints(t.path("right_children"));
            int[] feature = ints(t.path("split_indices"));
            // XGBoost compares in 32-bit floats; doing the same keeps borderline values on the same branch.
            double[] conditions = doubles(t.path("split_conditions"));
            float[] threshold = new float[conditions.length];
            for (int i = 0; i < conditions.length; i++) {
                threshold[i] = (float) conditions[i];
            }
            double[] cover = doubles(t.path("sum_hessian"));
            JsonNode dl = t.path("default_left");
            boolean[] defaultLeft = new boolean[left.length];
            for (int i = 0; i < left.length; i++) {
                defaultLeft[i] = dl.get(i).asInt() != 0;
            }
            // For a leaf, split_conditions holds its (learning-rate scaled) value.
            double[] value = conditions;
            double[] mean = new double[left.length];
            fillMean(0, left, right, value, cover, mean);
            return new Tree(left, right, feature, threshold, defaultLeft, value, mean);
        }

        private static double fillMean(int node, int[] left, int[] right, double[] value, double[] cover, double[] mean) {
            if (left[node] < 0) {
                mean[node] = value[node];
            } else {
                double l = fillMean(left[node], left, right, value, cover, mean);
                double r = fillMean(right[node], left, right, value, cover, mean);
                mean[node] = (l * cover[left[node]] + r * cover[right[node]]) / cover[node];
            }
            return mean[node];
        }

        private static int[] ints(JsonNode array) {
            int[] out = new int[array.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = array.get(i).asInt();
            }
            return out;
        }

        private static double[] doubles(JsonNode array) {
            double[] out = new double[array.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = array.get(i).asDouble();
            }
            return out;
        }
    }

    public static String hash(String text) {
        return HexFormat.of().formatHex(sha256(text.getBytes(StandardCharsets.UTF_8)));
    }
}
