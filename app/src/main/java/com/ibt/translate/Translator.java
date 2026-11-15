package com.ibt.translate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** OpenAI-compatible chat completions client (DeepSeek, OpenAI, relays). */
final class Translator {
    private Translator() {}

    static String translate(String baseUrl, String apiKey, String model, String targetLang, String text)
            throws Exception {
        String base = baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        JSONObject sys = new JSONObject()
                .put("role", "system")
                .put("content", "Translate the user's text into " + targetLang
                        + ". Output only the translation, no explanations, no quotes.");
        JSONObject user = new JSONObject().put("role", "user").put("content", text);
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("temperature", 0)
                .put("stream", false)
                .put("messages", new JSONArray().put(sys).put(user));

        HttpURLConnection c = (HttpURLConnection) new URL(base + "/chat/completions").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; in != null && (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
        String resp = bo.toString("UTF-8");
        if (code >= 400) throw new Exception("HTTP " + code + ": " + resp);
        String out = new JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim();
        if (out.isEmpty()) throw new Exception("empty translation");
        return out;
    }
}
