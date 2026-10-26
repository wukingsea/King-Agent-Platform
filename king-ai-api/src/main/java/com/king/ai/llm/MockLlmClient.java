package com.king.ai.llm;

import com.king.ai.common.Texts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

public class MockLlmClient implements LlmClient {

    public static final String KNOWLEDGE_MARKER = "【知识库参考资料】";
    public static final int DEFAULT_DIMENSION = 256;

    private static final int CHUNK_SIZE = 6;
    private static final long CHUNK_DELAY_MS = 6L;

    private final int dimension;

    public MockLlmClient() {
        this(DEFAULT_DIMENSION);
    }

    public MockLlmClient(int dimension) {
        this.dimension = Math.max(8, dimension);
    }

    @Override
    public LlmResult streamChat(LlmChatRequest request, LlmStreamListener listener, CancellationToken token) {
        LlmMessage last = lastMessage(request.messages());
        String userText = lastUserText(request.messages());
        String systemText = systemText(request.messages());
        String timeTool = timeTool(request.tools());

        String reasoning = buildReasoning(userText, last);
        StringBuilder reasoningOut = new StringBuilder();
        emit(reasoning, listener::onReasoning, token, reasoningOut);

        int promptTokens = estimateTokens(request.messages());
        if (last != null && "tool".equals(last.role())) {
            String answer = toolAnswer(last.content());
            StringBuilder contentOut = new StringBuilder();
            emit(answer, listener::onContent, token, contentOut);
            return new LlmResult(contentOut.toString(), reasoningOut.toString(), List.of(),
                    "stop", promptTokens, estimateTokens(answer));
        }

        if (timeTool != null && last != null && "user".equals(last.role()) && asksForTime(userText)) {
            String prefix = "好的，我先调用工具查询一下当前时间。\n\n";
            StringBuilder contentOut = new StringBuilder();
            emit(prefix, listener::onContent, token, contentOut);
            ToolCall call = new ToolCall("call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                    timeTool, "{}");
            return new LlmResult(contentOut.toString(), reasoningOut.toString(), List.of(call),
                    "tool_calls", promptTokens, estimateTokens(prefix));
        }

        String answer = buildAnswer(userText, systemText);
        StringBuilder contentOut = new StringBuilder();
        emit(answer, listener::onContent, token, contentOut);
        return new LlmResult(contentOut.toString(), reasoningOut.toString(), List.of(),
                "stop", promptTokens, estimateTokens(answer));
    }

    @Override
    public List<float[]> embed(String model, List<String> inputs) {
        List<float[]> vectors = new ArrayList<>();
        if (inputs == null) {
            return vectors;
        }
        for (String input : inputs) {
            vectors.add(embedding(input));
        }
        return vectors;
    }

    @Override
    public List<String> listModels() {
        return List.of("king-chat-demo", "king-embed-demo");
    }

    private float[] embedding(String text) {
        float[] vector = new float[dimension];
        if (Texts.isBlank(text)) {
            vector[0] = 1f;
            return vector;
        }
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        for (int i = 0; i < normalized.length(); i++) {
            add(vector, fnv("u:" + normalized.charAt(i)), 0.6f);
            if (i + 1 < normalized.length()) {
                add(vector, fnv("b:" + normalized.charAt(i) + normalized.charAt(i + 1)), 1.3f);
            }
        }
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm < 1e-9) {
            vector[0] = 1f;
            return vector;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return vector;
    }

    private static int fnv(String token) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < token.length(); i++) {
            hash ^= token.charAt(i);
            hash *= 0x01000193;
        }
        return hash;
    }

    private void add(float[] vector, int hash, float weight) {
        int index = Math.floorMod(hash, dimension);
        float sign = ((hash >>> 8) & 1) == 0 ? 1f : -1f;
        vector[index] += sign * weight;
    }

    private void emit(String text, Consumer<String> sink, CancellationToken token, StringBuilder collected) {
        if (Texts.isBlank(text)) {
            return;
        }
        for (int i = 0; i < text.length(); i += CHUNK_SIZE) {
            token.throwIfCancelled();
            String chunk = text.substring(i, Math.min(text.length(), i + CHUNK_SIZE));
            sink.accept(chunk);
            collected.append(chunk);
            try {
                Thread.sleep(CHUNK_DELAY_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new LlmCancelledException();
            }
        }
    }

    private String buildReasoning(String userText, LlmMessage last) {
        if (last != null && "tool".equals(last.role())) {
            return "工具已经返回结果，我来整理并给出结论。\n";
        }
        if (Texts.isBlank(userText)) {
            return "正在确认用户意图，准备开始回答。\n";
        }
        return "用户正在询问「" + Texts.oneLine(userText, 60) + "」。\n"
                + "先回顾对话上下文，再结合智能体设定组织回答。\n";
    }

    private String buildAnswer(String userText, String systemText) {
        if (Texts.isBlank(userText)) {
            return "你好，我是 King AI 演示助手。请告诉我你想了解什么，我会为你解答。";
        }
        String lower = userText.toLowerCase(Locale.ROOT);
        String knowledge = extractKnowledge(systemText);
        if (knowledge != null) {
            return "根据当前智能体绑定的知识库，我检索到以下相关内容：\n\n"
                    + knowledge
                    + "\n\n以上内容来自知识库检索结果。你可以继续追问具体细节，我会围绕检索到的资料继续回答。";
        }
        if (containsAny(lower, "你好", "您好", "hello", "hi", "嗨")) {
            return "你好，我是 King AI 演示助手。当前运行在离线演示模型上，我依然可以演示流式输出、知识库检索与 MCP 工具调用。你想先了解哪一部分？";
        }
        if (containsAny(lower, "你能做什么", "介绍一下", "你是谁", "有什么能力", "平台")) {
            return "我是 King AI 平台中的智能体，当前使用内置的离线演示模型。平台提供这些能力：\n\n"
                    + "1. 多轮对话与 SSE 流式输出，支持展示模型推理过程。\n"
                    + "2. 知识库检索增强，回答时自动召回相关文档片段并标注引用。\n"
                    + "3. MCP 工具调用，智能体可以按需调用外部工具完成任务。\n"
                    + "4. SKILL 技能规范，把业务工作流注入智能体的系统提示词。\n"
                    + "5. 供应商与模型管理，兼容 OpenAI 协议的多模型接入。\n\n"
                    + "在管理后台为智能体绑定模型、知识库、MCP 服务与技能后，即可发布并对外提供对话入口。";
        }
        return "关于「" + Texts.oneLine(userText, 80) + "」，我先给出一个整体思路：\n\n"
                + "1. 明确目标：把问题拆解成可以验证的具体结果。\n"
                + "2. 收集信息：结合已有资料与上下文，确认关键约束。\n"
                + "3. 给出方案：按优先级排列可执行步骤，并说明每一步的产出。\n\n"
                + "当前回答由本地离线演示模型生成。配置真实的 OpenAI 兼容模型后，智能体会基于完整上下文给出更深入的分析。";
    }

    private String toolAnswer(String toolResult) {
        String result = Texts.blankToDefault(toolResult, "（工具未返回内容）").trim();
        if (result.length() > 600) {
            result = result.substring(0, 600) + "…";
        }
        return "工具调用已完成，返回结果如下：\n\n" + result
                + "\n\n这说明 MCP 工具链路已经打通。如果你需要，我可以继续调用其他工具或基于结果做进一步分析。";
    }

    private String extractKnowledge(String systemText) {
        if (systemText == null) {
            return null;
        }
        int index = systemText.indexOf(KNOWLEDGE_MARKER);
        if (index < 0) {
            return null;
        }
        String content = systemText.substring(index + KNOWLEDGE_MARKER.length()).trim();
        if (content.isEmpty()) {
            return null;
        }
        if (content.length() > 700) {
            content = content.substring(0, 700) + "…";
        }
        return content;
    }

    private static boolean asksForTime(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("几点") || lower.contains("时间") || lower.contains("日期")
                || lower.contains("几号") || lower.contains("今天") || lower.contains("time");
    }

    private String timeTool(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        for (ToolDefinition tool : tools) {
            String name = Texts.blankToDefault(tool.name(), "").toLowerCase(Locale.ROOT);
            if (name.equals("get_current_time") || name.equals("current_time") || name.equals("now")
                    || name.equals("time") || name.endsWith("_get_current_time")
                    || name.contains("current_time")) {
                return tool.name();
            }
        }
        for (ToolDefinition tool : tools) {
            String description = Texts.blankToDefault(tool.description(), "");
            if (description.contains("当前时间") || description.contains("当前日期")
                    || description.contains("日期、时间") || description.contains("现在几点")) {
                return tool.name();
            }
        }
        return null;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static LlmMessage lastMessage(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        return messages.get(messages.size() - 1);
    }

    private static String lastUserText(List<LlmMessage> messages) {
        if (messages == null) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage message = messages.get(i);
            if ("user".equals(message.role()) && message.content() != null) {
                return message.content();
            }
        }
        return "";
    }

    private static String systemText(List<LlmMessage> messages) {
        if (messages == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (LlmMessage message : messages) {
            if ("system".equals(message.role()) && message.content() != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(message.content());
            }
        }
        return builder.toString();
    }

    private static int estimateTokens(List<LlmMessage> messages) {
        if (messages == null) {
            return 1;
        }
        int chars = 0;
        for (LlmMessage message : messages) {
            if (message.content() != null) {
                chars += message.content().length();
            }
        }
        return Math.max(1, chars / 3);
    }

    private static int estimateTokens(String text) {
        return text == null ? 1 : Math.max(1, text.length() / 3);
    }
}
