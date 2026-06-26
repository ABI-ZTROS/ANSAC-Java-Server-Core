package dev.ztros.ansac.physics.mlp;

import dev.ztros.ansac.physics.PlayerPhysicsState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MLPFeatureExtractorTest {

    @Test
    void testFeatureCountConstant() {
        assertEquals(24, MLPFeatureExtractor.FEATURE_COUNT);
    }

    @Test
    void testExtractReturnsCorrectLength() {
        PlayerPhysicsState state = new PlayerPhysicsState();
        double[] features = MLPFeatureExtractor.extract(state);
        assertEquals(MLPFeatureExtractor.FEATURE_COUNT, features.length);
    }

    @Test
    void testExtractValuesAreNormalized() {
        PlayerPhysicsState state = new PlayerPhysicsState();
        double[] features = MLPFeatureExtractor.extract(state);
        for (int i = 0; i < features.length; i++) {
            assertTrue(features[i] >= -1.0 && features[i] <= 1.0,
                "Feature " + i + " out of normalized range: " + features[i]);
        }
    }

    @Test
    void testExtractWithNullState() {
        double[] features = MLPFeatureExtractor.extract(null);
        assertNotNull(features);
        assertEquals(MLPFeatureExtractor.FEATURE_COUNT, features.length);
        for (double v : features) {
            assertEquals(0.0, v);
        }
    }
}
