package io.muleshield.core.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Java evaluator must agree with XGBoost itself: same probabilities, same per-feature contributions. */
class GbmModelParityTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void matchesXgboostPredictionsAndContributions() throws Exception {
        JsonNode expected = read("/parity/expected.json");
        List<String> features = expected.path("features").valueStream().map(JsonNode::asString).toList();
        GbmModel model;
        try (InputStream in = getClass().getResourceAsStream("/parity/model.json")) {
            model = GbmModel.load(in, features);
        }
        assertThat(model.treeCount()).isEqualTo(40);
        assertThat(model.version()).startsWith("gbm-").hasSize(16);

        for (JsonNode row : expected.path("rows")) {
            double[] x = row.path("x").valueStream().mapToDouble(v -> v.isNull() ? Double.NaN : v.asDouble()).toArray();
            Scorer.Score s = model.score(x);
            assertThat(s.probability()).isCloseTo(row.path("p").asDouble(), within(1e-5));
            assertThat(s.bias()).isCloseTo(row.path("bias").asDouble(), within(1e-4));
            double[] contribs = row.path("contribs").valueStream().mapToDouble(JsonNode::asDouble).toArray();
            for (int i = 0; i < contribs.length; i++) {
                assertThat(s.contributions()[i]).as("feature %s", features.get(i)).isCloseTo(contribs[i], within(1e-4));
            }
        }
    }

    @Test
    void refusesAModelTrainedOnDifferentFeatures() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/parity/model.json")) {
            assertThatThrownBy(() -> GbmModel.load(in, List.of("amount", "velocity")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("don't match serving features");
        }
    }

    private JsonNode read(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            return JSON.readTree(in);
        }
    }
}
