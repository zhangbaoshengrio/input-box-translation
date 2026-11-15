package com.ibt.translate;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class MainActivity extends Activity {
    private static final String DEEPSEEK_URL = "https://api.deepseek.com";
    private static final String DEEPSEEK_MODEL = "deepseek-chat";
    private static final String OPENAI_URL = "https://api.openai.com/v1";
    private static final String OPENAI_MODEL = "gpt-4o-mini";

    private volatile XposedService service;
    private View dot;
    private TextView status, statusHint, result, chipDeepseek, chipOpenai, chipCustom;
    private EditText baseUrl, apiKey, model, lang;
    private Button btnTest, btnSave;
    private boolean hasSavedKey;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        View scroll = findViewById(R.id.scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });

        dot = findViewById(R.id.dot);
        status = findViewById(R.id.status);
        statusHint = findViewById(R.id.statusHint);
        result = findViewById(R.id.result);
        chipDeepseek = findViewById(R.id.chipDeepseek);
        chipOpenai = findViewById(R.id.chipOpenai);
        chipCustom = findViewById(R.id.chipCustom);
        baseUrl = findViewById(R.id.baseUrl);
        apiKey = findViewById(R.id.apiKey);
        model = findViewById(R.id.model);
        lang = findViewById(R.id.lang);
        btnTest = findViewById(R.id.btnTest);
        btnSave = findViewById(R.id.btnSave);

        baseUrl.setText(DEEPSEEK_URL);
        model.setText(DEEPSEEK_MODEL);
        lang.setText("English");
        syncChips();
        setConnected(false);

        chipDeepseek.setOnClickListener(v -> preset(DEEPSEEK_URL, DEEPSEEK_MODEL));
        chipOpenai.setOnClickListener(v -> preset(OPENAI_URL, OPENAI_MODEL));
        chipCustom.setOnClickListener(v -> {
            baseUrl.requestFocus();
            baseUrl.selectAll();
            syncChips(true);
        });
        btnTest.setOnClickListener(v -> testConnection());
        btnSave.setOnClickListener(v -> save());

        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService s) {
                service = s;
                runOnUiThread(() -> { setConnected(true); load(); });
            }
            @Override public void onServiceDied(XposedService s) {
                service = null;
                runOnUiThread(() -> setConnected(false));
            }
        });
    }

    private void setConnected(boolean ok) {
        XposedService s = service;
        dot.getBackground().mutate().setTint(getColor(ok ? R.color.ok : R.color.err));
        if (ok && s != null) {
            status.setText("Connected · " + s.getFrameworkName() + " " + s.getFrameworkVersion());
            statusHint.setVisibility(View.GONE);
        } else {
            status.setText("Not connected to LSPosed");
            statusHint.setText("Enable this module in LSPosed, then force-stop this app and reopen it. "
                    + "You can still use Test.");
            statusHint.setVisibility(View.VISIBLE);
        }
    }

    private void preset(String url, String m) {
        baseUrl.setText(url);
        model.setText(m);
        syncChips();
    }

    private void syncChips() { syncChips(false); }

    private void syncChips(boolean forceCustom) {
        String u = baseUrl.getText().toString().trim();
        boolean ds = !forceCustom && u.equals(DEEPSEEK_URL);
        boolean oa = !forceCustom && u.equals(OPENAI_URL);
        chipDeepseek.setSelected(ds);
        chipOpenai.setSelected(oa);
        chipCustom.setSelected(!ds && !oa);
    }

    private void load() {
        XposedService s = service;
        if (s == null) return;
        SharedPreferences p = s.getRemotePreferences("config");
        baseUrl.setText(p.getString("base_url", DEEPSEEK_URL));
        model.setText(p.getString("model", DEEPSEEK_MODEL));
        lang.setText(p.getString("target_lang", "English"));
        hasSavedKey = !p.getString("api_key", "").isEmpty();
        apiKey.setText("");
        apiKey.setHint(hasSavedKey ? "Saved · leave blank to keep" : "sk-…");
        syncChips();
    }

    private String effectiveKey() {
        String typed = apiKey.getText().toString().trim();
        if (!typed.isEmpty()) return typed;
        XposedService s = service;
        return s == null ? "" : s.getRemotePreferences("config").getString("api_key", "");
    }

    private void showResult(boolean ok, String msg) {
        result.setText(msg);
        result.setTextColor(getColor(ok ? R.color.ok : R.color.err));
        result.setVisibility(View.VISIBLE);
    }

    private void testConnection() {
        final String key = effectiveKey();
        if (key.isEmpty()) {
            showResult(false, "Enter an API key first.");
            return;
        }
        final String base = baseUrl.getText().toString(), m = model.getText().toString();
        btnTest.setEnabled(false);
        btnTest.setText("Testing…");
        new Thread(() -> {
            boolean ok = true;
            String msg;
            try {
                msg = "✓ 你好世界 → " + Translator.translate(base, key, m, "English", "你好世界");
            } catch (Throwable t) {
                ok = false;
                msg = "✗ " + t.getMessage();
            }
            final boolean fok = ok;
            final String fmsg = msg;
            runOnUiThread(() -> {
                showResult(fok, fmsg);
                btnTest.setEnabled(true);
                btnTest.setText("Test");
            });
        }).start();
    }

    private void save() {
        XposedService s = service;
        if (s == null) {
            setConnected(false);
            Toast.makeText(this, "Not connected to LSPosed — force-stop and reopen this app",
                    Toast.LENGTH_LONG).show();
            return;
        }
        SharedPreferences.Editor e = s.getRemotePreferences("config").edit()
                .putString("base_url", baseUrl.getText().toString().trim())
                .putString("model", model.getText().toString().trim())
                .putString("target_lang", lang.getText().toString().trim());
        String key = apiKey.getText().toString().trim();
        if (!key.isEmpty()) e.putString("api_key", key); // blank keeps the saved key
        e.apply();
        if (!key.isEmpty()) {
            hasSavedKey = true;
            apiKey.setText("");
            apiKey.setHint("Saved · leave blank to keep");
        }
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    }
}
