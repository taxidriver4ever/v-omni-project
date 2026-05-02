package org.example.vomnisearch.util;

import java.nio.ByteBuffer;
import java.util.Random;

public class VectorUtil {
    public static float[] generateRandomUnitVector(int dims) {
        float[] vec = new float[dims];
        Random rand = new Random();
        float normSq = 0;
        for (int i = 0; i < dims; i++) {
            vec[i] = rand.nextFloat() * 2 - 1; // 随机 -1 到 1
            normSq += vec[i] * vec[i];
        }
        float invNorm = (float) (1.0 / Math.sqrt(normSq));
        for (int i = 0; i < dims; i++) vec[i] *= invNorm; // 归一化
        return vec;
    }
    public static float[] decodeFloatArray(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] floats = new float[bytes.length / 4];
        buffer.asFloatBuffer().get(floats);
        return floats;
    }
}
