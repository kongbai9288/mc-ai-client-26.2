package com.kongbai.mcai.bridge;

import com.kongbai.mcai.act.AntiCheat;
import com.kongbai.mcai.act.BaritoneLink;
import com.kongbai.mcai.config.Config;
import com.kongbai.mcai.llm.Agent;
import com.kongbai.mcai.platform.Env;
import com.kongbai.mcai.sense.Scanner;
import com.kongbai.mcai.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 本地桥接 API。
 *
 * <p>目的：让<strong>别的程序</strong>也能驱动这个 mod —— 无论是 Claude Desktop、
 * DeepSeek Harness、自己写的脚本，还是手机上的遥控器，都走同一套接口。
 *
 * <p>提供两套协议，覆盖不同客户端：
 * <ul>
 *   <li><b>REST</b>：{@code /health /observe /act /goal /agent/* /tools /trace} —— 简单直接。</li>
 *   <li><b>MCP</b>：{@code /mcp}（JSON-RPC 2.0，{@code initialize / tools/list / tools/call}）
 *       —— 让任何支持 MCP 的客户端零适配接入。</li>
 * </ul>
 *
 * <p>安全：只绑 {@code 127.0.0.1}，默认带 Bearer token（配置了才校验）。
 */
public final class HttpApi {

    private HttpServer server;
    private volatile int port;

    private static HttpApi INSTANCE;

    public static HttpApi get() {
        if (INSTANCE == null) {
            synchronized (HttpApi.class) {
                if (INSTANCE == null) INSTANCE = new HttpApi();
            }
        }
        return INSTANCE;
    }

    private HttpApi() {}

    /** 启动。手机启动器上直接拒绝启动。 */
    public synchronized boolean start() {
        if (server != null) {
            return true;
        }
        if (!Env.bridgeEnabled()) {
            McAiModLog.warn("检测到手机启动器环境（" + Env.reason()
                    + "），桥接 API 已关闭以避免崩溃");
            return false;
        }
        Config c = Config.get();
        if (!c.bridgeEnabled) {
            McAiModLog.info("bridge.enabled=false，桥接 API 未启动");
            return false;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(c.bridgeHost, c.bridgePort), 0);
            server.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "mcai-http");
                t.setDaemon(true);
                return t;
            }));
            server.createContext("/health", guard(this::health));
            server.createContext("/observe", guard(this::observe));
            server.createContext("/act", guard(this::act));
            server.createContext("/goal", guard(this::goal));
            server.createContext("/tools", guard(this::tools));
            server.createContext("/trace", guard(this::trace));
            server.createContext("/agent/start", guard(ex -> agent(ex, true)));
            server.createContext("/agent/stop", guard(ex -> agent(ex, false)));
            server.createContext("/agent/run", guard(this::agentRun));
            server.createContext("/config/anticheat", guard(this::anticheat));
            server.createContext("/mcp", guard(this::mcp));
            server.start();
            port = server.getAddress().getPort();
            McAiModLog.info("MCAI 桥接已启动: http://" + c.bridgeHost + ":" + port
                    + "  (REST + MCP /mcp)");
            return true;
        } catch (IOException e) {
            McAiModLog.warn("桥接启动失败: " + e);
            return false;
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            McAiModLog.info("MCAI 桥接已停止");
        }
    }

    public int port() {
        return port;
    }

    // ==================================================================
    // REST
    // ==================================================================

    private void health(HttpExchange ex) throws IOException {
        Config c = Config.get();
        Scanner s = Tools.scanner();
        Json.Obj o = Json.obj();
        o.put("ok", true);
        o.put("service", "mcai");
        o.put("version", "0.1.0");
        o.put("minecraft", "26.2");
        o.put("port", port);
        o.put("environment", Env.isMobileLauncher() ? Env.reason() : "desktop");
        o.put("bridge_enabled", Env.bridgeEnabled() && c.bridgeEnabled);
        o.put("baritone", BaritoneLink.available());
        o.put("baritone_detail", BaritoneLink.available() ? "" : BaritoneLink.failureReason());
        o.put("litematica", com.kongbai.mcai.act.LitematicaLink.available());
        o.put("litematica_detail", com.kongbai.mcai.act.LitematicaLink.status());
        o.put("via", com.kongbai.mcai.platform.ViaLink.status());
        o.put("agent_mode", c.mode);
        o.put("agent_running", Agent.get().isRunning());
        o.put("agent_goal", Agent.get().goal());
        o.put("llm_configured", !c.apiKey.isBlank());
        o.put("scan_cache_hit_rate", Math.round(s.cache().hitRate() * 100.0) / 100.0);
        o.put("last_scan_ms", s.lastScanMs());
        o.put("anticheat", AntiCheat.get().status());
        send(ex, 200, o);
    }

    private void observe(HttpExchange ex) throws IOException {
        String r = Tools.invoke("observe", Map.of());
        sendRaw(ex, 200, r == null ? "{\"ok\":false,\"error\":\"timeout\"}" : r);
    }

    /** POST /act  {"tool":"go_to","args":{...}} */
    private void act(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, Json.obj().put("ok", false).put("error", "use POST"));
            return;
        }
        Map<String, Object> body = Json.parseObj(readBody(ex));
        String tool = Json.str(body, "tool", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) body.getOrDefault("args", Map.of());
        String r = Tools.invoke(tool, args);
        sendRaw(ex, 200, r == null ? "{\"ok\":false,\"error\":\"timeout\"}" : r);
    }

    /** POST /goal  {"goal":"去挖 20 个钻石"} */
    private void goal(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, Json.obj().put("ok", false).put("error", "use POST"));
            return;
        }
        Map<String, Object> body = Json.parseObj(readBody(ex));
        String g = Json.str(body, "goal", "");
        Agent.get().setGoal(g);
        if (Json.bool(body, "start", true)) {
            Agent.get().start();
        }
        send(ex, 200, Json.obj().put("ok", true).put("goal", g)
                .put("agent_running", Agent.get().isRunning()));
    }

    private void tools(HttpExchange ex) throws IOException {
        List<Map<String, Object>> ts = Tools.schemas();
        Json.Arr a = Json.arr();
        for (Map<String, Object> t : ts) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fn = (Map<String, Object>) t.get("function");
            a.add(Json.obj().put("name", fn.get("name"))
                    .put("description", fn.get("description")));
        }
        send(ex, 200, Json.obj().put("ok", true).put("count", ts.size()).put("tools", a));
    }

    private void trace(HttpExchange ex) throws IOException {
        send(ex, 200, Json.obj().put("ok", true)
                .put("rounds_completed", Agent.get().roundsCompleted())
                .put("last_round_ms", Agent.get().lastRoundMs())
                .put("last_error", Agent.get().lastError())
                .put("lines", Agent.get().trace()));
    }

    private void agent(HttpExchange ex, boolean startFlag) throws IOException {
        if (startFlag) {
            Agent.get().start();
        } else {
            Agent.get().stop();
        }
        send(ex, 200, Json.obj().put("ok", true)
                .put("agent_running", Agent.get().isRunning()));
    }

    private void agentRun(HttpExchange ex) throws IOException {
        send(ex, 200, Agent.get().runOnce());
    }

    private void anticheat(HttpExchange ex) throws IOException {
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            Map<String, Object> body = Json.parseObj(readBody(ex));
            send(ex, 200, AntiCheat.get().tune(body));
        } else {
            send(ex, 200, AntiCheat.get().status()
                    .put("recommend", AntiCheat.get().recommend()));
        }
    }

    // ==================================================================
    // MCP（JSON-RPC 2.0 over HTTP）
    // ==================================================================

    private void mcp(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendRaw(ex, 405, "{\"error\":\"use POST\"}");
            return;
        }
        String body = readBody(ex);
        Map<String, Object> req;
        try {
            req = Json.parseObj(body);
        } catch (RuntimeException e) {
            sendRaw(ex, 400, rpcError(null, -32700, "解析失败: " + e.getMessage()));
            return;
        }
        Object id = req.get("id");
        String method = Json.str(req, "method", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) req.getOrDefault("params", Map.of());

        String result = switch (method) {
            case "initialize" -> rpcResult(id, Json.obj()
                    .put("protocolVersion", "2024-11-05")
                    .put("capabilities", Json.obj().put("tools", Json.obj()))
                    .put("serverInfo", Json.obj().put("name", "mcai").put("version", "0.1.0")));
            case "tools/list" -> {
                Json.Arr arr = Json.arr();
                for (Map<String, Object> t : Tools.schemas()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fn = (Map<String, Object>) t.get("function");
                    arr.add(Json.obj()
                            .put("name", fn.get("name"))
                            .put("description", fn.get("description"))
                            .put("inputSchema", fn.get("parameters")));
                }
                yield rpcResult(id, Json.obj().put("tools", arr));
            }
            case "tools/call" -> {
                String name = Json.str(params, "name", "");
                @SuppressWarnings("unchecked")
                Map<String, Object> args =
                        (Map<String, Object>) params.getOrDefault("arguments", Map.of());
                String r = Tools.invoke(name, args);
                if (r == null) {
                    yield rpcError(id, -32000, "工具执行超时");
                }
                yield rpcResult(id, Json.obj()
                        .put("content", Json.arr().add(Json.obj()
                                .put("type", "text").put("text", r)))
                        .put("isError", !r.contains("\"ok\":true")));
            }
            case "notifications/initialized", "initialized" -> "";
            default -> rpcError(id, -32601, "未知方法: " + method);
        };

        if (result.isEmpty()) {
            // 通知类消息不回 body
            ex.sendResponseHeaders(202, -1);
            ex.close();
            return;
        }
        sendRaw(ex, 200, result);
    }

    private static String rpcResult(Object id, Json.Obj result) {
        Json.Obj o = Json.obj();
        o.put("jsonrpc", "2.0");
        o.put("id", id == null ? 0 : id);
        o.put("result", result);
        return Json.write(o);
    }

    private static String rpcError(Object id, int code, String msg) {
        Json.Obj o = Json.obj();
        o.put("jsonrpc", "2.0");
        o.put("id", id == null ? 0 : id);
        o.put("error", Json.obj().put("code", code).put("message", msg));
        return Json.write(o);
    }

    // ==================================================================
    // HTTP 工具
    // ==================================================================

    private void send(HttpExchange ex, int code, Json.Obj o) throws IOException {
        sendRaw(ex, code, Json.write(o));
    }

    private void sendRaw(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        ex.sendResponseHeaders(code, b.length);
        try (var os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 简易 token 校验：配置了 token 才启用。 */
    private boolean authorized(HttpExchange ex) {
        String want = Config.get().bridgeToken;
        if (want == null || want.isBlank()) {
            return true;
        }
        String got = ex.getRequestHeaders().getFirst("Authorization");
        return got != null && got.equals("Bearer " + want);
    }

    /** 把 handler 包一层校验。/health 无需 token 时把 want 留空即可。 */
    private com.sun.net.httpserver.HttpHandler guard(com.sun.net.httpserver.HttpHandler h) {
        return ex -> {
            try {
                if (!authorized(ex)) {
                    send(ex, 401, Json.obj().put("ok", false)
                            .put("error", "unauthorized")
                            .put("hint", "请求头加 Authorization: Bearer <bridge.token>"));
                    return;
                }
                if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(204, -1);
                    ex.close();
                    return;
                }
                h.handle(ex);
            } catch (Throwable t) {
                try {
                    send(ex, 500, Json.obj().put("ok", false).put("error", String.valueOf(t)));
                } catch (IOException ignored) {
                    // 连接可能已断开，忽略
                }
            }
        };
    }
}
