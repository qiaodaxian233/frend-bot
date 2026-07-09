package com.frend.system;

import com.frend.FrendConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI 兼容 /chat/completions 客户端。
 *
 * <p>零第三方依赖:HTTP 用 JDK 自带 java.net.http,JSON 用 Minecraft 自带的 gson。
 * 本地 Ollama、LM Studio、云端 OpenAI 走的都是同一个协议,换 baseUrl/model/key 即可。
 *
 * <p>两条铁律:
 * <ul>
 *   <li>只产出闲聊文本,返回内容永远不会被解析成游戏操作(行为红线);</li>
 *   <li>全异步,绝不在服务器主线程上等网络——调用方拿到 future 后自己切回主线程再碰游戏状态。</li>
 * </ul>
 */
public final class FrendLlmClient {
    private FrendLlmClient() {}

    private static volatile HttpClient client;

    private static HttpClient client() {
        if (client == null) {
            synchronized (FrendLlmClient.class) {
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .build();
                }
            }
        }
        return client;
    }

    /**
     * 发起一次对话补全。history 为 {role, content} 数组列表(role: user/assistant),按时间先后排列。
     * 返回的 future 在 HTTP 线程完成——调用方负责用 server.execute 切回主线程。
     */
    public static CompletableFuture<String> chat(String systemPrompt, List<String[]> history, String userMessage) {
        FrendConfig cfg = FrendConfig.get();

        JsonArray messages = new JsonArray();
        messages.add(msg("system", systemPrompt));
        for (String[] turn : history) messages.add(msg(turn[0], turn[1]));
        messages.add(msg("user", userMessage));

        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.openaiModel);
        body.addProperty("max_tokens", 120);
        body.addProperty("temperature", 0.9);
        body.add("messages", messages);

        String base = cfg.openaiBaseUrl.endsWith("/")
                ? cfg.openaiBaseUrl.substring(0, cfg.openaiBaseUrl.length() - 1)
                : cfg.openaiBaseUrl;

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/chat/completions"))
                .timeout(Duration.ofSeconds(Math.max(3, cfg.openaiTimeoutSeconds)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (cfg.openaiApiKey != null && !cfg.openaiApiKey.isBlank()) {
            req.header("Authorization", "Bearer " + cfg.openaiApiKey.trim());
        }

        return client().sendAsync(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + snip(resp.body()));
                    }
                    return extract(resp.body());
                });
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    /** 从 OpenAI 格式响应里取 choices[0].message.content 并清洗。 */
    private static String extract(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String text = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
        return sanitize(text);
    }

    /** 洗成一条像人话的短消息:去思维链标签(部分本地模型会吐)/换行/首尾引号,超长截断。 */
    public static String sanitize(String text) {
        if (text == null) return "";
        String s = text.replaceAll("(?s)<think>.*?</think>", " ")
                .replace('\n', ' ').replace('\r', ' ')
                .replaceAll("\\s+", " ").trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("“") && s.endsWith("”")))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        int max = Math.max(10, FrendConfig.get().llmMaxReplyChars);
        if (s.length() > max) {
            s = s.substring(0, max) + "……";
        }
        return s;
    }

    private static String snip(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
