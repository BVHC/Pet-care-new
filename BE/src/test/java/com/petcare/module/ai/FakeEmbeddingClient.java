package com.petcare.module.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.petcare.module.ai.llm.AiProviderException;
import com.petcare.module.ai.llm.EmbeddingClient;

/**
 * {@link EmbeddingClient} giả cho IT (CI và IT không gọi mạng): vector tất định theo nội dung, chuẩn hóa độ dài, nên
 * cùng một văn bản cho độ tương đồng cosine 1. Ghi lại mọi lệnh gọi; có thể gài móc chạy trước mỗi lệnh gọi (để sửa dữ
 * liệu giữa lúc nhúng, hay đo số kết nối đang giữ) và gài lỗi cho các lần gọi kế tiếp.
 */
public class FakeEmbeddingClient implements EmbeddingClient {

    public static final String MODEL = "fake-embedding-it";
    public static final int DIMENSIONS = 768;

    private final List<List<String>> batches = new CopyOnWriteArrayList<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private volatile AiProviderException failure;
    private volatile Runnable beforeCall = () -> { };

    public void reset() {
        batches.clear();
        queries.clear();
        failuresLeft.set(0);
        failure = null;
        beforeCall = () -> { };
    }

    /** {@code times} lệnh gọi kế tiếp ném {@code error}, sau đó chạy bình thường. */
    public void failNext(int times, AiProviderException error) {
        failure = error;
        failuresLeft.set(times);
    }

    public void failAlways(AiProviderException error) {
        failNext(Integer.MAX_VALUE, error);
    }

    public void beforeCall(Runnable hook) {
        this.beforeCall = hook;
    }

    public List<List<String>> batches() {
        return batches;
    }

    public List<String> queries() {
        return queries;
    }

    public int calls() {
        return batches.size() + queries.size();
    }

    @Override
    public String embeddingModel() {
        return MODEL;
    }

    @Override
    public float[] embedQuery(String text) {
        queries.add(text);
        enter();
        return vectorOf(text);
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        batches.add(List.copyOf(texts));
        enter();
        List<float[]> vectors = new ArrayList<>();
        for (String text : texts) {
            vectors.add(vectorOf(text));
        }
        return vectors;
    }

    private void enter() {
        beforeCall.run();
        if (failure != null && failuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw failure;
        }
    }

    public static float[] vectorOf(String text) {
        Random random = new Random(text.hashCode());
        float[] vector = new float[DIMENSIONS];
        double norm = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = (float) random.nextGaussian();
            norm += vector[i] * vector[i];
        }
        float scale = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] *= scale;
        }
        return vector;
    }
}
