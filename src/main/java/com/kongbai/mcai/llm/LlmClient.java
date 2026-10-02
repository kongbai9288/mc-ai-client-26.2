package com.kongbai.mcai.llm;

import com.kongbai.mcai.config.Config;
import com.kongbai.mcai.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容对话客户端。
 *
 * <p>只用 JDK 自带 {@link HttpClient}，零依赖。
 * 兼容 DeepSeek / OpenAI / 智谱 / Moonshot / 本地 Ollama / vLLM / 任何
 * {@code /v1/chat/completions} 网关 —— 改 {@code api.base} 即可。
 *
 * <p>采用原生 function calling（tools），不是"让模型输出 JSON 再正则抠字段"。
 * 后者是这类项目最常见的低级错误来源：模型多说一句话就解析失败。
 */
public final class LlmClient {

    /** 一次工具调用请求。 */
    public record ToolCall(String id, String name, String arguments) {}

    /** 模型回复。 */
    public record Reply(String content, List<ToolCall> toolCalls, String finishReason) {}

    private final HttpClient http;
    private final Config cfg;

    public LlmClient() {
        this.cfg = Config.get();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public boolean configured() {
        return cfg.apiKey != null && !cfg.apiKey.isBlank();
    }

    /**
     * 发起一次对话。
     *
     * @param messages 完整消息列表（system / user / assistant / tool）
     * @param tools    OpenAI 格式的 tools 数组；可为 null
     */
    public Reply chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools)
            throws Exception {
        if (!configured()) {
            throw new IllegalStateException(
                    "未配置 API Key：请设置环境变量 MCAI_API_KEY（不要把密钥写进配置文件）");
        }

        Json.Obj body = Json.obj();
        body.put("model", cfg.model);
        body.put("messages", messages);
        body.put("temperature", cfg.temperature);
        body.put("max_tokens", cfg.maxTokens);
        body.put("stream", false);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        String url = cfg.apiBase;
        if (!url.endsWith("/chat/completions")) {
            url = url.replaceAll("/+$", "") + "/chat/completions";
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body),
                        java.nio.charset.StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        String raw = resp.body();

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("LLM 返回 " + resp.statusCode() + ": "
                    + trim(raw, 400));
        }

        Map<String, Object> parsed = Json.parseObj(raw);
        Object err = parsed.get("error");
        if (err != null) {
            throw new RuntimeException("LLM 错误: " + trim(Json.write(err), 300));
        }

        @SuppressWarnings("unchecked")
        List<Object> choices = (List<Object>) parsed.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("LLM 返回空 choices");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> msg = (Map<String, Object>)
                ((Map<String, Object>) choices.get(0)).get("message");
        if (msg == null) {
            throw new RuntimeException("LLM 返回缺少 message");
        }

        String content = msg.get("content") == null ? "" : String.valueOf(msg.get("content"));
        String finish = msg.get("finish_reason") == null
                ? ((Map<String, Object>) choices.get(0)).get("finish_reason") == null
                    ? "" : String.valueOf(((Map<String, Object>) choices.get(0)).get("finish_reason"))
                : String.valueOf(msg.get("finish_reason"));

        List<ToolCall> calls = new ArrayList<>();
        Object tcs = msg.get("tool_calls");
        if (tcs instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String id = m.get("id") == null ? "" : String.valueOf(m.get("id"));
                @SuppressWarnings("unchecked")
                Map<String, Object> fn = (Map<String, Object>) m.get("function");
                if (fn == null) continue;
                String name = fn.get("name") == null ? "" : String.valueOf(fn.get("name"));
                String args = fn.get("arguments") == null ? "{}" : String.valueOf(fn.get("arguments"));
                calls.add(new ToolCall(id, name, args));
            }
        }
        return new Reply(content, calls, String.valueOf(finish));
    }

    // ==================================================================
    // 消息构造助手
    // ==================================================================

    public static Map<String, Object> system(String text) {
        return msg("system", text, null, null, null);
    }

    public static Map<String, Object> user(String text) {
        return msg("user", text, null, null, null);
    }

    public static Map<String, Object> assistant(String text, List<Map<String, Object>> toolCalls) {
        Map<String, Object> m = msg("assistant", text, null, null, null);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            m.put("tool_calls", toolCalls);
        }
        return m;
    }

    /** 工具结果消息（OpenAI 规范：role=tool + tool_call_id）。 */
    public static Map<String, Object> toolResult(String callId, String name, String content) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", callId);
        m.put("name", name);
        m.put("content", content);
        return m;
    }

    private static Map<String, Object> msg(String role, String content,
                                           String name, String id, Object unused) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("role", role);
        if (content != null) {
            m.put("content", content);
        }
        if (name != null) {
            m.put("name", name);
        }
        return m;
    }

    /** OpenAI function 定义。 */
    public static Map<String, Object> tool(String name, String description,
                                           Map<String, Object> parameters) {
        Map<String, Object> fn = new java.util.LinkedHashMap<>();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", parameters);
        Map<String, Object> t = new java.util.LinkedHashMap<>();
        t.put("type", "function");
        t.put("function", fn);
        return t;
    }

    /** JSON Schema object。 */
    public static Map<String, Object> params(Map<String, Object> properties, List<String> required) {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("type", "object");
        p.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            p.put("required", required);
        }
        p.put("additionalProperties", false);
        return p;
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
