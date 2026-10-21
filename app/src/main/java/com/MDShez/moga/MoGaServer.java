package com.mdshez.moga;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Tiny local server: serves the MoGa web app and proxies LLM calls
 *  (same-origin => no CORS issues; the APK does all the communicating). */
public class MoGaServer extends NanoHTTPD {

    private static final Map<String, String[]> PROVIDERS = new HashMap<>();
    static {
        PROVIDERS.put("bigmodel",  new String[]{"BigModel GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"});
        PROVIDERS.put("openrouter",new String[]{"OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat"});
        PROVIDERS.put("nvidia",    new String[]{"NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "nvidia/nemotron-3.5-lightning-30b-a3b"});
        PROVIDERS.put("gemini",    new String[]{"Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/", "gemini-2.5-flash"});
        PROVIDERS.put("groq",      new String[]{"Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"});
        PROVIDERS.put("deepseek",  new String[]{"DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"});
        PROVIDERS.put("custom",    new String[]{"Custom / Home Server", "http://192.168.1.10:1234/v1", "eduqwen"});
    }

    private final Context ctx;

    public MoGaServer(Context ctx) {
        this(ctx, 8080);
    }

    public MoGaServer(Context ctx, int port) {
        super(port);
        this.ctx = ctx;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        try {
            if (uri.equals("/api/llm") && session.getMethod() == Method.POST)
                return proxyLlm(session);
            if (uri.equals("/api/providers"))
                return providersJson();
            return asset("index.html");
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain",
                    "Error: " + e.getMessage());
        }
    }

    private Response asset(String name) throws Exception {
        InputStream in = ctx.getAssets().open(name);
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        in.close();
        String mime = name.endsWith(".html") ? "text/html"
                    : name.endsWith(".js") ? "application/javascript" : "text/plain";
        return newFixedLengthResponse(Response.Status.OK, mime + "; charset=utf-8", sb.toString());
    }

    private Response providersJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, String[]> e : PROVIDERS.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(String.format("{\"id\":\"%s\",\"name\":\"%s\",\"model\":\"%s\"}",
                    e.getKey(), e.getValue()[0], e.getValue()[2]));
        }
        sb.append(']');
        return newFixedLengthResponse(Response.Status.OK, "application/json", sb.toString());
    }

    /** Body: {provider, model?, key, messages:[...]}; proxies to OpenAI-compatible API. */
    private Response proxyLlm(IHTTPSession session) throws Exception {
        Map<String, String> body = new HashMap<>();
        session.parseBody(body);
        String json = body.get("postData");
        String provider = extract(json, "provider");
        String model = extract(json, "model");
        String key = extract(json, "key");
        String messages = extractArray(json, "messages");
        String[] p = PROVIDERS.get(provider == null ? "bigmodel" : provider);
        if (p == null) { provider = "bigmodel"; p = PROVIDERS.get(provider); }
        String base = p[1];
        boolean custom = "custom".equals(provider);
        if (custom) {   // self-hosted server (LM Studio / Ollama / vLLM): URL from settings
            String b = extract(json, "base");
            if (b != null && !b.isEmpty()) base = b;
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "http://" + base;
        model = (model == null || model.isEmpty()) ? p[2] : model;

        URL url = new URL(base.replaceAll("/+$", "") + "/chat/completions");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setDoInput(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(custom ? 300000 : 120000);   // local CPU models are slow
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        String tools = extractArray(json, "tools");
        String payload = "{\"model\":\"" + model + "\",\"messages\":" + messages
                + (tools.equals("[]") ? "" : ",\"tools\":" + tools) + "}";
        try (OutputStream os = c.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return newFixedLengthResponse(Response.Status.lookup(code), "application/json", sb.toString());
    }

    /** Extract a top-level JSON array value (bracket-matched, string-aware). */
    private static String extractArray(String json, String field) {
        int i = json.indexOf("\"" + field + "\"");
        if (i < 0) return "[]";
        int start = json.indexOf('[', i);
        if (start < 0) return "[]";
        int depth = 0;
        boolean inStr = false;
        for (int pos = start; pos < json.length(); pos++) {
            char ch = json.charAt(pos);
            if (inStr) {
                if (ch == '\\') pos++;
                else if (ch == '"') inStr = false;
            } else {
                if (ch == '"') inStr = true;
                else if (ch == '[') depth++;
                else if (ch == ']') {
                    depth--;
                    if (depth == 0) return json.substring(start, pos + 1);
                }
            }
        }
        return "[]";
    }

    private static String extract(String json, String field) {
        int i = json.indexOf("\"" + field + "\"");
        if (i < 0) return null;
        int c = json.indexOf(':', i) + 1;
        while (c < json.length() && json.charAt(c) == ' ') c++;
        if (json.charAt(c) != '"') {
            int e = c;
            while (e < json.length() && ",}]".indexOf(json.charAt(e)) < 0) e++;
            return json.substring(c, e).trim();
        }
        int e = ++c;
        while (e < json.length() && json.charAt(e) != '"') {
            if (json.charAt(e) == '\\') e++;
            e++;
        }
        return json.substring(c, e);
    }
}
