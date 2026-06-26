package dev.ztros.ansac.physics.mlp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovementMLPTest {

    @Test
    void testInitializationProducesBoundedOutput() {
        MovementMLP mlp = new MovementMLP(24, 16, 8, 0.01);
        double out = mlp.forward(new double[24]);
        assertTrue(out > 0.0 && out < 1.0, "Initial output should be bounded in (0,1)");
    }

    @Test
    void testOutputAlwaysInUnitInterval() {
        MovementMLP mlp = new MovementMLP(24, 16, 8, 0.01);
        for (int r = 0; r < 20; r++) {
            double[] input = new double[24];
            for (int i = 0; i < 24; i++) {
                input[i] = Math.random() * 2.0 - 0.5;
            }
            double out = mlp.forward(input);
            assertTrue(out >= 0.0 && out <= 1.0,
                "Output out of bounds: " + out);
        }
    }

    @Test
    void testTrainingReducesLoss() {
        MovementMLP mlp = new MovementMLP(4, 4, 2, 0.15);
        double[] input = {0.2, 0.4, 0.6, 0.8};
        double target = 0.85;

        double firstLoss = mlp.train(input, target);
        for (int i = 0; i < 800; i++) {
            mlp.train(input, target);
        }
        double lastLoss = mlp.train(input, target);

        assertTrue(lastLoss < firstLoss,
            "Loss should decrease over training. First=" + firstLoss + ", Last=" + lastLoss);
        double finalOut = mlp.forward(input);
        assertEquals(target, finalOut, 0.03,
            "Output should converge to target");
    }

    @Test
    void testPersistenceRoundTrip() throws Exception {
        java.io.File temp = java.io.File.createTempFile("mlp", ".bin");
        temp.deleteOnExit();

        MovementMLP original = new MovementMLP(24, 16, 8, 0.01);
        double[] input = new double[24];
        for (int i = 0; i < 24; i++) input[i] = 0.33;
        double expected = original.forward(input);

        MLPPersistence.save(original, temp);
        MovementMLP loaded = MLPPersistence.load(temp);
        double actual = loaded.forward(input);

        assertEquals(expected, actual, 1e-9,
            "Serialized and loaded MLP must produce identical output");
    }
}
