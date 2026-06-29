package dev.ztros.ansac.physics.mlp;

import java.io.*;

public final class MLPPersistence {
    private static final int FILE_VERSION = 2; // v2: 支持 CombatMLP + CausalFusion

    private MLPPersistence() {
        throw new UnsupportedOperationException();
    }

    // ==================== MovementMLP ====================

    public static void saveMovement(MovementMLP mlp, File file) throws IOException {
        writeHeader(file);
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(FILE_VERSION);
            out.writeInt(1); // 网络类型: 1=MovementMLP
            out.writeInt(mlp.getInputSize());
            out.writeInt(mlp.getHidden1Size());
            out.writeInt(mlp.getHidden2Size());
            out.writeDouble(mlp.getLearningRate());

            writeMatrix(out, mlp.getW1());
            writeVector(out, mlp.getB1());
            writeMatrix(out, mlp.getW2());
            writeVector(out, mlp.getB2());
            writeVector(out, mlp.getW3());
            out.writeDouble(mlp.getB3());
        }
    }

    public static MovementMLP loadMovement(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            int version = in.readInt();
            if (version != FILE_VERSION) {
                throw new IOException("Unsupported MLP file version: " + version);
            }
            int type = in.readInt();
            if (type != 1) throw new IOException("Expected MovementMLP type=1, got " + type);

            int inputSize = in.readInt();
            int hidden1Size = in.readInt();
            int hidden2Size = in.readInt();
            double learningRate = in.readDouble();

            MovementMLP mlp = new MovementMLP(inputSize, hidden1Size, hidden2Size, learningRate);

            readMatrix(in, mlp.getW1());
            readVector(in, mlp.getB1());
            readMatrix(in, mlp.getW2());
            readVector(in, mlp.getB2());
            readVector(in, mlp.getW3());
            mlp.setB3(sanitizeWeight(in.readDouble()));
            return mlp;
        }
    }

    /** 兼容旧版 v1 格式 */
    public static MovementMLP load(File file) throws IOException {
        return loadMovement(file);
    }

    // ==================== CombatMLP ====================

    public static void saveCombat(CombatMLP mlp, File file) throws IOException {
        writeHeader(file);
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(FILE_VERSION);
            out.writeInt(2); // 网络类型: 2=CombatMLP
            out.writeInt(mlp.getInputSize());
            out.writeInt(mlp.getHidden1Size());
            out.writeInt(mlp.getHidden2Size());
            out.writeDouble(mlp.getLearningRate());

            writeMatrix(out, mlp.getW1());
            writeVector(out, mlp.getB1());
            writeMatrix(out, mlp.getW2());
            writeVector(out, mlp.getB2());
            writeMatrix(out, mlp.getW3());
            writeVector(out, mlp.getB3());
        }
    }

    public static CombatMLP loadCombat(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            int version = in.readInt();
            if (version != FILE_VERSION) throw new IOException("Unsupported file version: " + version);
            int type = in.readInt();
            if (type != 2) throw new IOException("Expected CombatMLP type=2, got " + type);

            int inputSize = in.readInt();
            int hidden1Size = in.readInt();
            int hidden2Size = in.readInt();
            double learningRate = in.readDouble();

            CombatMLP mlp = new CombatMLP(inputSize, hidden1Size, hidden2Size, learningRate);

            readMatrix(in, mlp.getW1());
            readVector(in, mlp.getB1());
            readMatrix(in, mlp.getW2());
            readVector(in, mlp.getB2());
            readMatrix(in, mlp.getW3());
            readVector(in, mlp.getB3());
            return mlp;
        }
    }

    // ==================== CausalFusion ====================

    public static void saveFusion(CausalFusion fusion, File file) throws IOException {
        writeHeader(file);
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(FILE_VERSION);
            out.writeInt(3); // 网络类型: 3=CausalFusion
            out.writeInt(fusion.getInputSize());
            out.writeInt(fusion.getHiddenSize());
            out.writeDouble(fusion.getLearningRate());

            writeMatrix(out, fusion.getW1());
            writeVector(out, fusion.getB1());
            writeMatrix(out, fusion.getW2());
            writeVector(out, fusion.getB2());
        }
    }

    public static CausalFusion loadFusion(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            int version = in.readInt();
            if (version != FILE_VERSION) throw new IOException("Unsupported file version: " + version);
            int type = in.readInt();
            if (type != 3) throw new IOException("Expected CausalFusion type=3, got " + type);

            int inputSize = in.readInt();
            int hiddenSize = in.readInt();
            double learningRate = in.readDouble();

            CausalFusion fusion = new CausalFusion(hiddenSize, learningRate);

            readMatrix(in, fusion.getW1());
            readVector(in, fusion.getB1());
            readMatrix(in, fusion.getW2());
            readVector(in, fusion.getB2());
            return fusion;
        }
    }

    // ==================== IO 工具 ====================

    private static void writeHeader(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
    }

    private static void writeMatrix(DataOutputStream out, double[][] m) throws IOException {
        for (double[] row : m) {
            for (double v : row) out.writeDouble(v);
        }
    }

    private static void writeVector(DataOutputStream out, double[] v) throws IOException {
        for (double val : v) out.writeDouble(val);
    }

    private static void readMatrix(DataInputStream in, double[][] m) throws IOException {
        for (int i = 0; i < m.length; i++) {
            for (int j = 0; j < m[i].length; j++) {
                m[i][j] = sanitizeWeight(in.readDouble());
            }
        }
    }

    private static void readVector(DataInputStream in, double[] v) throws IOException {
        for (int i = 0; i < v.length; i++) {
            v[i] = sanitizeWeight(in.readDouble());
        }
    }

    private static boolean isValidWeight(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && Double.isFinite(v);
    }

    private static double sanitizeWeight(double v) {
        return isValidWeight(v) ? Math.max(-100.0, Math.min(100.0, v)) : 0.0;
    }
}
