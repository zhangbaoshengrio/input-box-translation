package com.ibt.translate;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
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
    private static final String IME_PKG = "com.bytedance.android.doubaoime";
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
        if (IME_PKG.equals(param.getPackageName())) {
            hookIme();
            return;
        }
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

    /**
     * Keyboard-side mode: runs inside the input method's own process, so it works in every app
     * without ticking them. The keyboard talks to the focused app through the InputConnection
     * returned by InputMethodService.getCurrentInputConnection(); we hook that connection.
     */
    private void hookIme() {
        try {
            Method gic = android.inputmethodservice.InputMethodService.class
                    .getDeclaredMethod("getCurrentInputConnection");
            hook(gic).intercept(chain -> {
                Object ic = chain.proceed();
                if (ic != null) {
                    try {
                        hookImeClass(ic.getClass());
                    } catch (Throwable t) {
                        log(android.util.Log.WARN, TAG, "ime class hook failed", t);
                    }
                }
                return ic;
            });
            log(android.util.Log.INFO, TAG, "ime mode ready");
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "ime hook failed", t);
        }
    }

    private void hookImeClass(Class<?> c) throws Exception {
        Method commit = c.getMethod("commitText", CharSequence.class, int.class);
        Method key = c.getMethod("sendKeyEvent", KeyEvent.class);
        synchronized (hooked) {
            if (!hooked.add(commit)) return;
            hooked.add(key);
        }
        log(android.util.Log.INFO, TAG, "ime hooking " + c.getName());
        hook(commit).intercept(chain -> {
            Object r = chain.proceed();
            if (!replacing) {
                try {
                    onText((InputConnection) chain.getThisObject(), (CharSequence) chain.getArg(0));
                } catch (Throwable t) {
                    log(android.util.Log.WARN, TAG, "ime commit failed", t);
                }
            }
            return r;
        });
        hook(key).intercept(chain -> {
            Object r = chain.proceed();
            if (!replacing) {
                try {
                    KeyEvent e = (KeyEvent) chain.getArg(0);
                    if (e.getAction() == KeyEvent.ACTION_DOWN) {
                        onText((InputConnection) chain.getThisObject(),
                                e.getKeyCode() == KeyEvent.KEYCODE_SPACE ? " " : "");
                    }
                } catch (Throwable t) {
                    log(android.util.Log.WARN, TAG, "ime key failed", t);
                }
            }
            return r;
        });
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
                    onText((InputConnection) chain.getThisObject(), (CharSequence) chain.getArg(0));
                } catch (Throwable t) {
                    log(android.util.Log.WARN, TAG, "onText failed", t);
                }
                return r;
            } finally {
                inside.set(false);
            }
        });
    }

    private void onText(InputConnection ic, CharSequence committed) {
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
        log(android.util.Log.INFO, TAG, "trigger before=" + (b == null ? -1 : b.length())
                + " after=" + (a == null ? -1 : a.length())
                + " ends3=" + (b != null && b.toString().endsWith("   ")));
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
            toast("请先在 Input Box Translate 里填写 API key");
            return;
        }
        busy = true;
        final String original = before + after;
        showProgress();
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
                        hideProgress();
                    }
                });
            } catch (Throwable t) {
                busy = false;
                log(android.util.Log.WARN, TAG, "translate failed", t);
                hideProgress();
                toast("翻译失败: " + t.getMessage());
            }
        }, "ibt-translate").start();
    }

    private static volatile Toast progress;
    private static volatile long progressShownAt;
    private static final long MIN_PROGRESS_MS = 1200;

    /** "翻译中…" toast; on Android 12+ it carries the icon of the app it runs in (Doubao in IME mode). */
    private static void showProgress() {
        MAIN.post(() -> {
            try {
                android.content.Context c = (android.content.Context) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                if (c == null) return;
                if (progress != null) progress.cancel();
                progress = Toast.makeText(c, "翻译中…", Toast.LENGTH_LONG);
                progress.show();
                progressShownAt = System.currentTimeMillis();
            } catch (Throwable ignored) {}
        });
    }

    /** Hide the progress toast, but keep it up long enough to be read. */
    private static void hideProgress() {
        MAIN.post(() -> {
            Toast t = progress;
            if (t == null) return;
            progress = null;
            long wait = MIN_PROGRESS_MS - (System.currentTimeMillis() - progressShownAt);
            MAIN.postDelayed(t::cancel, Math.max(0, wait));
        });
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
