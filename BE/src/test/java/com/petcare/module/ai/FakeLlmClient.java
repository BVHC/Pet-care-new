package com.petcare.module.ai;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.petcare.module.ai.llm.AiProviderException;
import com.petcare.module.ai.llm.LlmAnswer;
import com.petcare.module.ai.llm.LlmClient;

/**
 * {@link LlmClient} giả cho test (CI và IT không gọi mạng): ghi lại mọi lệnh gọi (lời nhắc hệ thống và khối người dùng),
 * trả câu trả lời theo kịch bản hoặc ném lỗi nhà cung cấp. Mặc định trả một câu {@code inScope = true} vô hại.
 */
public class FakeLlmClient implements LlmClient {

    public static final String MODEL = "fake-llm-it";
    public static final LlmAnswer DEFAULT_ANSWER = new LlmAnswer(true, "Câu trả lời thử nghiệm.", 120, 18, 0);

    /** Một lệnh gọi đã nhận: đúng hai tham số của {@link LlmClient#generate}. */
    public record Call(String systemPrompt, String userBlock) {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile Function<Call, LlmAnswer> reply = call -> DEFAULT_ANSWER;
    private volatile AiProviderException failure;
    private volatile Runnable beforeCall = () -> { };

    public void reset() {
        calls.clear();
        reply = call -> DEFAULT_ANSWER;
        failure = null;
        beforeCall = () -> { };
    }

    /** Móc chạy trước mỗi lệnh gọi (đo số kết nối CSDL đang giữ). */
    public void beforeCall(Runnable hook) {
        this.beforeCall = hook;
    }

    public void reply(LlmAnswer answer) {
        reply = call -> answer;
    }

    public void reply(boolean inScope, String answer) {
        reply(new LlmAnswer(inScope, answer, 120, 18, 0));
    }

    /** Mọi lệnh gọi sau đó ném {@code error}. */
    public void failWith(AiProviderException error) {
        failure = error;
    }

    public List<Call> calls() {
        return calls;
    }

    @Override
    public String llmModel() {
        return MODEL;
    }

    @Override
    public LlmAnswer generate(String systemPrompt, String userBlock) {
        Call call = new Call(systemPrompt, userBlock);
        calls.add(call);
        beforeCall.run();
        if (failure != null) {
            throw failure;
        }
        return reply.apply(call);
    }
}
