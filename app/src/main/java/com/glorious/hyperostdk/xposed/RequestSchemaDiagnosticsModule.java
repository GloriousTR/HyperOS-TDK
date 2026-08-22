package com.glorious.hyperostdk.xposed;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.glorious.hyperostdk.BuildConfig;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModule;

/**
 * Read-only schema diagnostics for the RequestUrl produced by Theme Manager.
 *
 * <p>This module records field/parameter names and only null/presence/type/length
 * metadata. It never persists request values, URLs, identifiers, tokens, DRM
 * state, arguments or return-value contents, and it never modifies them.</p>
 */
public final class RequestSchemaDiagnosticsModule extends XposedModule {
    private static final String TAG = "HyperOS-TDK";
    private static final String TARGET_PACKAGE = "com.android.thememanager";
    private static final String THEME_APPLICATION = "com.android.thememanager.ThemeApplication";
    private static final String ONLINE_BASE_CONTROLLER =
            "com.android.thememanager.basemodule.controller.online.f";
    private static final String REQUEST_URL_CLASS = "com.thememanager.network.RequestUrl";

    private static final String CONTROL_AUTHORITY = "com.glorious.hyperostdk.control";
    private static final String METHOD_DIAG_APPEND = "diag_append";
    private static final String KEY_SOURCE = "diag_source";
    private static final String KEY_EVENT = "diag_event";
    private static final String KEY_DETAIL = "diag_detail";
    private static final String KEY_LEVEL = "diag_level";
    private static final String KEY_EVENT_AT = "diag_event_at";

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,80}");

    private final AtomicBoolean applicationHookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean requestHookInstalled = new AtomicBoolean(false);
    private volatile Context applicationContext;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "[REQUEST-SCHEMA-DIAG] module loaded; version="
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
                    publish(
                            "REQUEST_SCHEMA_DIAG_RUNTIME",
                            "moduleVersion=" + BuildConfig.VERSION_NAME
                                    + " | policy=names+presence+type+length-only",
                            "INFO"
                    );
                    installRequestHook(classLoader);
                }
                return result;
            });
        } catch (Throwable error) {
            applicationHookInstalled.set(false);
            log(Log.ERROR, TAG, "[REQUEST-SCHEMA-DIAG] ThemeApplication hook failed", error);
        }
    }

    private void installRequestHook(ClassLoader classLoader) {
        if (!requestHookInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> targetClass = Class.forName(ONLINE_BASE_CONTROLLER, false, classLoader);
            int count = 0;
            for (Method method : targetClass.getDeclaredMethods()) {
                if (!"Y".equals(method.getName())) {
                    continue;
                }
                if (!REQUEST_URL_CLASS.equals(method.getReturnType().getName())) {
                    continue;
                }
                int modifiers = method.getModifiers();
                if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)) {
                    continue;
                }

                method.setAccessible(true);
                final String signature = signatureOf(method);
                hook(method).intercept(chain -> {
                    try {
                        Object result = chain.proceed();
                        publishRequestSchema(signature, result);
                        return result;
                    } catch (Throwable error) {
                        publish(
                                "REQUEST_SCHEMA_THROW",
                                signature + " | error=" + error.getClass().getName()
                                        + ": " + safe(error.getMessage()),
                                "ERROR"
                        );
                        throw error;
                    }
                });
                count++;
                publish(
                        "HOOK_INSTALLED",
                        signature + " | schemaPolicy=names+presence+type+length-only",
                        "INFO"
                );
            }
            if (count == 0) {
                publish(
                        "HOOK_NOT_FOUND",
                        ONLINE_BASE_CONTROLLER + "#Y(...)->" + REQUEST_URL_CLASS + " request schema",
                        "WARN"
                );
            }
        } catch (Throwable error) {
            requestHookInstalled.set(false);
            publish(
                    "HOOK_INSTALL_ERROR",
                    ONLINE_BASE_CONTROLLER + "#Y request schema | "
                            + error.getClass().getName() + ": " + safe(error.getMessage()),
                    "ERROR"
            );
            log(Log.ERROR, TAG, "[REQUEST-SCHEMA-DIAG] request hook failed", error);
        }
    }

    private void publishRequestSchema(String signature, Object requestUrl) {
        if (requestUrl == null) {
            publish("REQUEST_URL_SCHEMA", signature + " | result=null", "WARN");
            return;
        }

        List<FieldSnapshot> snapshots = collectFields(requestUrl);
        List<String> topLevel = new ArrayList<>();
        for (FieldSnapshot snapshot : snapshots) {
            topLevel.add(snapshot.name + '=' + summarizeValue(snapshot.value));
        }
        publish(
                "REQUEST_URL_SCHEMA",
                signature + " | type=" + requestUrl.getClass().getName()
                        + " | fields={" + String.join(", ", topLevel) + "}",
                "INFO"
        );

        for (FieldSnapshot snapshot : snapshots) {
            Object value = snapshot.value;
            if (value instanceof Map<?, ?>) {
                publish(
                        "REQUEST_MAP_SCHEMA",
                        "field=" + snapshot.name + " | " + summarizeMap((Map<?, ?>) value),
                        "INFO"
                );
            } else if (value != null
                    && value.getClass().getName().startsWith("com.thememanager.network.")) {
                List<String> nested = new ArrayList<>();
                for (FieldSnapshot child : collectFields(value)) {
                    nested.add(child.name + '=' + summarizeValue(child.value));
                }
                publish(
                        "REQUEST_NESTED_SCHEMA",
                        "field=" + snapshot.name + " | type=" + value.getClass().getName()
                                + " | fields={" + String.join(", ", nested) + "}",
                        "INFO"
                );
            }
        }
    }

    private static List<FieldSnapshot> collectFields(Object value) {
        List<FieldSnapshot> out = new ArrayList<>();
        Class<?> current = value.getClass();
        int inspected = 0;
        while (current != null && current != Object.class && inspected < 96 && out.size() < 40) {
            for (Field field : current.getDeclaredFields()) {
                if (inspected++ >= 96 || out.size() >= 40) {
                    break;
                }
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    out.add(new FieldSnapshot(safeName(field.getName()), field.get(value)));
                } catch (Throwable ignored) {
                    out.add(new FieldSnapshot(safeName(field.getName()), FieldSnapshot.UNAVAILABLE));
                }
            }
            current = current.getSuperclass();
        }
        return out;
    }

    private static String summarizeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value == FieldSnapshot.UNAVAILABLE) {
            return "<unavailable>";
        }
        if (value instanceof CharSequence) {
            String text = String.valueOf(value);
            return value.getClass().getSimpleName() + "(len=" + text.length()
                    + ",empty=" + text.isEmpty() + ")";
        }
        if (value instanceof Boolean) {
            return "Boolean(" + value + ")";
        }
        if (value instanceof Number) {
            return value.getClass().getSimpleName() + "(present)";
        }
        if (value instanceof Enum<?>) {
            return value.getClass().getSimpleName() + "(" + safeName(((Enum<?>) value).name()) + ")";
        }
        if (value instanceof Map<?, ?>) {
            return "Map(size=" + ((Map<?, ?>) value).size() + ")";
        }
        if (value instanceof Collection<?>) {
            return value.getClass().getSimpleName() + "(size=" + ((Collection<?>) value).size() + ")";
        }
        if (value.getClass().isArray()) {
            return value.getClass().getComponentType().getSimpleName()
                    + "[](len=" + Array.getLength(value) + ")";
        }
        return value.getClass().getName() + "(present)";
    }

    private static String summarizeMap(Map<?, ?> map) {
        List<String> entries = new ArrayList<>();
        int seen = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (seen++ >= 40) {
                entries.add("…+" + (map.size() - 40));
                break;
            }
            String key = safeMapKey(entry.getKey());
            entries.add(key + '=' + summarizeValue(entry.getValue()));
        }
        return "size=" + map.size() + " | entries={" + String.join(", ", entries) + "}";
    }

    private static String safeMapKey(Object key) {
        if (key == null) {
            return "<null-key>";
        }
        String text = String.valueOf(key);
        if (SAFE_NAME.matcher(text).matches()) {
            return text;
        }
        return "<key:" + key.getClass().getSimpleName() + ">";
    }

    private static String safeName(String value) {
        if (value == null) {
            return "null";
        }
        if (SAFE_NAME.matcher(value).matches()) {
            return value;
        }
        return "<redacted-name>";
    }

    private void publish(String event, String detail, String level) {
        Context context = applicationContext;
        if (context == null) {
            return;
        }
        try {
            Bundle extras = new Bundle();
            extras.putString(KEY_SOURCE, "ThemeManager.RequestSchemaDiagnostics");
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
            log(Log.WARN, TAG, "[REQUEST-SCHEMA-DIAG] publish failed: " + event, error);
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

    private static final class FieldSnapshot {
        static final Object UNAVAILABLE = new Object();
        final String name;
        final Object value;

        FieldSnapshot(String name, Object value) {
            this.name = name;
            this.value = value;
        }
    }
}
