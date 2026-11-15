package com.ibt.translate;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Triple-space-to-translate. Hooks commitText on whatever InputConnection class the app uses
 * (standard EditText, Compose, WebView...), discovered at runtime from the framework's
 * RemoteInputConnectionImpl, so the app's own text field implementation doesn't matter.
 */
public class HookEntry extends XposedModule {
    private static final String TAG = "IBT";
    private static final long WINDOW_MS = 700;
    private static final int MAX_CTX = 4000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Set<Method> hooked = new HashSet<>();
    private final ThreadLocal<Boolean> inside = new ThreadLocal<>();
    private final long[] stamps = new long[3];
    private int idx;
    private volatile boolean busy;
    private volatile boolean replacing;

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        log(android.util.Log.INFO, TAG, "loaded into " + param.getPackageName());
        try {
            hookClass(android.view.inputmethod.BaseInputConnection.class);
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "base hook failed", t);
        }
        try {
            Class<?> remote;
            try {
                remote = Class.forName("android.view.inputmethod.RemoteInputConnectionImpl");
            } catch (ClassNotFoundException e) {
                remote = Class.forName("com.android.internal.inputmethod.RemoteInputConnectionImpl");
            }
            Method getIc = remote.getDeclaredMethod("getInputConnection");
            getIc.setAccessible(true);
            for (Method m : remote.getDeclaredMethods()) {
                if (!m.getName().equals("commitText")) continue;
                log(android.util.Log.INFO, TAG, "hooking remote " + m);
                hook(m).intercept(chain -> {
                    try {
                        Object ic = getIc.invoke(chain.getThisObject());
                        if (ic != null) hookClass(ic.getClass());
                    } catch (Throwable t) {
                        log(android.util.Log.WARN, TAG, "discover failed", t);
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "remote hook failed", t);
        }
    }

    private void hookClass(Class<?> c) throws Exception {
        Method m = c.getMethod("commitText", CharSequence.class, int.class);
        synchronized (hooked) {
            if (!hooked.add(m)) return;
        }
        log(android.util.Log.INFO, TAG, "hooking " + m.getDeclaringClass().getName());
        hook(m).intercept(chain -> {
            if (Boolean.TRUE.equals(inside.get()) || replacing) return chain.proceed();
            inside.set(true);
            try {
                Object r = chain.proceed();
                try {
                    onCommit((InputConnection) chain.getThisObject(), (CharSequence) chain.getArg(0));
                } catch (Throwable t) {
                    log(android.util.Log.WARN, TAG, "onCommit failed", t);
                }
                return r;
            } finally {
                inside.set(false);
            }
        });
    }

    private void onCommit(InputConnection ic, CharSequence committed) {
        if (committed == null || !committed.toString().equals(" ")) {
            idx = 0;
            stamps[0] = stamps[1] = stamps[2] = 0;
            return;
        }
        long now = System.currentTimeMillis();
        stamps[idx++ % 3] = now;
        if (idx < 3) return;
        long oldest = Math.min(stamps[0], Math.min(stamps[1], stamps[2]));
        if (now - oldest > WINDOW_MS || busy) return;
        idx = 0;
        stamps[0] = stamps[1] = stamps[2] = 0;
        // let the editor finish the current batch before reading the text back
        MAIN.postDelayed(() -> trigger(ic), 80);
    }

    private void trigger(InputConnection ic) {
        CharSequence b = ic.getTextBeforeCursor(MAX_CTX, 0);
        CharSequence a = ic.getTextAfterCursor(MAX_CTX, 0);
        if (b == null) return;
        String before = b.toString(), after = a == null ? "" : a.toString();
        if (!before.endsWith("   ")) return;
        String text = (before.substring(0, before.length() - 3) + after).trim();
        if (text.isEmpty()) return;

        SharedPreferences p = getRemotePreferences("config");
        String base = p.getString("base_url", "https://api.deepseek.com");
        String key = p.getString("api_key", "");
        String model = p.getString("model", "deepseek-chat");
        String lang = p.getString("target_lang", "English");
        if (key.isEmpty()) {
            toast("IBT: set API key in the module app");
            return;
        }
        busy = true;
        final String original = before + after;
        toast("Translating…");
        new Thread(() -> {
            try {
                String out = Translator.translate(base, key, model, lang, text);
                MAIN.post(() -> {
                    try {
                        CharSequence b2 = ic.getTextBeforeCursor(MAX_CTX, 0);
                        CharSequence a2 = ic.getTextAfterCursor(MAX_CTX, 0);
                        String now = (b2 == null ? "" : b2.toString()) + (a2 == null ? "" : a2.toString());
                        if (!now.equals(original)) return; // user kept typing; don't clobber
                        replacing = true;
                        ic.beginBatchEdit();
                        ic.deleteSurroundingText(before.length(), after.length());
                        ic.commitText(out, 1);
                        ic.endBatchEdit();
                    } finally {
                        replacing = false;
                        busy = false;
                    }
                });
            } catch (Throwable t) {
                busy = false;
                log(android.util.Log.WARN, TAG, "translate failed", t);
                toast("Translate failed: " + t.getMessage());
            }
        }, "ibt-translate").start();
    }

    private static void toast(String msg) {
        MAIN.post(() -> {
            try {
                android.content.Context c = (android.content.Context) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                if (c != null) Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
    }
}
