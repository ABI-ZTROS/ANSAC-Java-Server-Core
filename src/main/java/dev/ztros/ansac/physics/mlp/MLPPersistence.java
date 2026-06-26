package dev.ztros.ansac.physics.mlp;

import java.io.*;

public final class MLPPersistence {
    private static final int FILE_VERSION = 1;

    private MLPPersistence() {
        throw new UnsupportedOperationException();
    }

    public static void save(MovementMLP mlp, File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(FILE_VERSION);
            out.writeInt(mlp.getInputSize());
            out.writeInt(mlp.getHidden1Size());
            out.writeInt(mlp.getHidden2Size());
            out.writeDouble(mlp.getLearningRate());

            double[][] w1 = mlp.getW1();
            for (int i = 0; i < w1.length; i++) {
                for (int j = 0; j < w1[i].length; j++) {
                    out.writeDouble(w1[i][j]);
                }
            }

            double[] b1 = mlp.getB1();
            for (double v : b1) out.writeDouble(v);

            double[][] w2 = mlp.getW2();
            for (int i = 0; i < w2.length; i++) {
                for (int j = 0; j < w2[i].length; j++) {
                    out.writeDouble(w2[i][j]);
                }
            }

            double[] b2 = mlp.getB2();
            for (double v : b2) out.writeDouble(v);

            double[] w3 = mlp.getW3();
            for (double v : w3) out.writeDouble(v);

            out.writeDouble(mlp.getB3());
        }
    }

    public static MovementMLP load(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            int version = in.readInt();
            if (version != FILE_VERSION) {
                throw new IOException("Unsupported MLP file version: " + version);
            }
            int inputSize = in.readInt();
            int hidden1Size = in.readInt();
            int hidden2Size = in.readInt();
            double learningRate = in.readDouble();

            MovementMLP mlp = new MovementMLP(inputSize, hidden1Size, hidden2Size, learningRate);

            double[][] w1 = mlp.getW1();
            for (int i = 0; i < w1.length; i++) {
                for (int j = 0; j < w1[i].length; j++) {
                    w1[i][j] = in.readDouble();
                }
            }

            double[] b1 = mlp.getB1();
            for (int j = 0; j < b1.length; j++) {
                b1[j] = in.readDouble();
            }

            double[][] w2 = mlp.getW2();
            for (int i = 0; i < w2.length; i++) {
                for (int j = 0; j < w2[i].length; j++) {
                    w2[i][j] = in.readDouble();
                }
            }

            double[] b2 = mlp.getB2();
            for (int j = 0; j < b2.length; j++) {
                b2[j] = in.readDouble();
            }

            double[] w3 = mlp.getW3();
            for (int i = 0; i < w3.length; i++) {
                w3[i] = in.readDouble();
            }

            mlp.setB3(in.readDouble());
            return mlp;
        }
    }
}
