package com.glorious.hyperostdk.xposed;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;

import com.glorious.hyperostdk.BuildConfig;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * Read-only companion diagnostics for the parsed download-rights response.
 *
 * <p>This module deliberately avoids persisting raw response bodies. It emits
 * only top-level JSON key names plus a small allow-list of primitive error
 * fields (code/resultCode/status/message/msg/reason/error/errorCode/
 * description/detail). Arguments, return values and DRM state are never
 * modified.</p>
 */
public final class DownloadRightsPayloadDiagnosticsModule extends XposedModule {
    private static final String TAG = "HyperOS-TDK";
    private static final String TARGET_PACKAGE = "com.android.thememanager";
    private static final String THEME_APPLICATION = "com.android.thememanager.ThemeApplication";
    private static final String DOWNLOAD_RIGHTS_CONTROLLER =
            "com.android.thememanager.basemodule.controller.online.e";

    private static final String CONTROL_AUTHORITY = "com.glorious.hyperostdk.control";
    private static final String METHOD_DIAG_APPEND = "diag_append";
    private static final String KEY_SOURCE = "diag_source";
    private static final String KEY_EVENT = "diag_event";
    private static final String KEY_DETAIL = "diag_detail";
    private static final String KEY_LEVEL = "diag_level";
    private static final String KEY_EVENT_AT = "diag_event_at";

    private final AtomicBoolean applicationHookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean payloadHookInstalled = new AtomicBoolean(false);
    private volatile Context applicationContext;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "[RIGHTS-PAYLOAD-DIAG] module loaded; version="
                + BuildConfig.VERSION_NAME + ", process=" + param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        installApplicationBridge(param.getClassLoader());
    }

    private void installApplicationBridge(ClassLoader classLoader) {
        if (!applicationHookInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> appClass = Class.forName(THEME_APPLICATION, false, classLoader);
            Method onCreate = appClass.getDeclaredMethod("onCreate");
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                Object receiver = chain.getThisObject();
                if (receiver instanceof Context) {
                    Context context = ((Context) receiver).getApplicationContext();
                    applicationContext = context != null ? context : (Context) receiver;
                    publish("PAYLOAD_DIAG_RUNTIME",
                            "moduleVersion=" + BuildConfig.VERSION_NAME
                                    + " | policy=safe-json-summary-only",
                            "INFO");
                    installPayloadHook(classLoader);
                }
                return result;
            });
        } catch (Throwable error) {
            applicationHookInstalled.set(false);
            log(Log.ERROR, TAG, "[RIGHTS-PAYLOAD-DIAG] ThemeApplication hook failed", error);
        }
    }

    private void installPayloadHook(ClassLoader classLoader) {
        if (!payloadHookInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> targetClass = Class.forName(DOWNLOAD_RIGHTS_CONTROLLER, false, classLoader);
            int count = 0;
            for (Method method : targetClass.getDeclaredMethods()) {
                if (!"s".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || params[0] != String.class) {
                    continue;
                }
                int modifiers = method.getModifiers();
                if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)) {
                    continue;
                }

                method.setAccessible(true);
                final String signature = signatureOf(method);
                hook(method).intercept(chain -> {
                    List<Object> args = chain.getArgs();
                    String raw = args != null && !args.isEmpty() && args.get(0) != null
                            ? String.valueOf(args.get(0)) : null;
                    String inputSummary = summarizeRawJson(raw);
                    try {
                        Object result = chain.proceed();
                        publish("DOWNLOAD_RIGHTS_PAYLOAD_SUMMARY",
                                signature
                                        + " | input=" + inputSummary
                                        + " | output=" + summarizePairPayload(result),
                                "INFO");
                        return result;
                    } catch (Throwable error) {
                        publish("DOWNLOAD_RIGHTS_PAYLOAD_THROW",
                                signature + " | input=" + inputSummary
                                        + " | error=" + error.getClass().getName()
                                        + ": " + safe(error.getMessage()),
                                "ERROR");
                        throw error;
                    }
                });
                count++;
                publish("HOOK_INSTALLED",
                        signature + " | payloadPolicy=safe-json-summary-only",
                        "INFO");
            }
            if (count == 0) {
                publish("HOOK_NOT_FOUND",
                        DOWNLOAD_RIGHTS_CONTROLLER + "#s(java.lang.String) payload summary",
                        "WARN");
            }
        } catch (Throwable error) {
            payloadHookInstalled.set(false);
            publish("HOOK_INSTALL_ERROR",
                    DOWNLOAD_RIGHTS_CONTROLLER + "#s payload summary | "
                            + error.getClass().getName() + ": " + safe(error.getMessage()),
                    "ERROR");
            log(Log.ERROR, TAG, "[RIGHTS-PAYLOAD-DIAG] payload hook failed", error);
        }
    }

    private static String summarizePairPayload(Object result) {
        if (!(result instanceof Pair<?, ?>)) {
            return "responseType=" + (result == null ? "null" : result.getClass().getName());
        }
        Pair<?, ?> pair = (Pair<?, ?>) result;
        Object payload = pair.second;
        StringBuilder out = new StringBuilder();
        out.append("resultCode=").append(safe(String.valueOf(pair.first)));
        out.append(", payloadType=")
                .append(payload == null ? "null" : payload.getClass().getName());
        if (payload instanceof JSONObject) {
            out.append(", payload=").append(summarizeJsonObject((JSONObject) payload));
        }
        return out.toString();
    }

    private static String summarizeRawJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "<empty>";
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{")) {
            return "<non-json length=" + raw.length() + ">";
        }
        try {
            return summarizeJsonObject(new JSONObject(trimmed));
        } catch (Throwable ignored) {
            return "<json-parse-failed length=" + raw.length() + ">";
        }
    }

    private static String summarizeJsonObject(JSONObject object) {
        List<String> keys = new ArrayList<>();
        List<String> safeFields = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        int seen = 0;
        while (iterator.hasNext() && seen < 24) {
            String key = iterator.next();
            keys.add(safe(key));
            if (isAllowedField(key)) {
                Object value = object.opt(key);
                String described = describeAllowedValue(value);
                if (described != null) {
                    safeFields.add(safe(key) + '=' + described);
                }
            }
            seen++;
        }
        String suffix = object.length() > seen ? ",…+" + (object.length() - seen) : "";
        return "json{keys=[" + String.join(",", keys) + suffix + "]"
                + ", safeFields={" + String.join(",", safeFields) + "}}";
    }

    private static boolean isAllowedField(String key) {
        if (key == null) {
            return false;
        }
        switch (key.toLowerCase(Locale.ROOT)) {
            case "code":
            case "resultcode":
            case "status":
            case "message":
            case "msg":
            case "reason":
            case "error":
            case "errorcode":
            case "description":
            case "detail":
                return true;
            default:
                return false;
        }
    }

    private static String describeAllowedValue(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return safe(String.valueOf(value));
        }
        if (value instanceof CharSequence) {
            String text = String.valueOf(value);
            if (text.length() > 320) {
                return "<string length=" + text.length() + ">";
            }
            return '"' + safe(text) + '"';
        }
        return "<" + value.getClass().getSimpleName() + ">";
    }

    private void publish(String event, String detail, String level) {
        Context context = applicationContext;
        if (context == null) {
            return;
        }
        try {
            Bundle extras = new Bundle();
            extras.putString(KEY_SOURCE, "ThemeManager.RightsPayloadDiagnostics");
            extras.putString(KEY_EVENT, event);
            extras.putString(KEY_DETAIL, safe(detail));
            extras.putString(KEY_LEVEL, level);
            extras.putLong(KEY_EVENT_AT, System.currentTimeMillis());
            context.getContentResolver().call(
                    CONTROL_AUTHORITY,
                    METHOD_DIAG_APPEND,
                    null,
                    extras
            );
        } catch (Throwable error) {
            log(Log.WARN, TAG, "[RIGHTS-PAYLOAD-DIAG] publish failed: " + event, error);
        }
    }

    private static String signatureOf(Method method) {
        StringBuilder builder = new StringBuilder();
        builder.append(method.getDeclaringClass().getName())
                .append('#').append(method.getName()).append('(');
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(parameterTypes[i].getName());
        }
        return builder.append(") -> ")
                .append(method.getReturnType().getName())
                .toString();
    }

    private static String safe(String value) {
        if (value == null) {
            return "null";
        }
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        if (oneLine.length() > 1800) {
            return oneLine.substring(0, 1800) + "…";
        }
        return oneLine;
    }
}
