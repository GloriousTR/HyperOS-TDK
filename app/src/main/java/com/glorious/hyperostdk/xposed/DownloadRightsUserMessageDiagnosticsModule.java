package com.glorious.hyperostdk.xposed;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;

import com.glorious.hyperostdk.BuildConfig;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * Read-only diagnostics for the public/user-facing error message returned by
 * Theme Manager's download-rights parser.
 *
 * <p>Only apiCode/resultCode and the short serverToast string are emitted.
 * Raw apiData, request bodies, tokens, identifiers, DRM state, arguments and
 * return values are never modified or persisted.</p>
 */
public final class DownloadRightsUserMessageDiagnosticsModule extends XposedModule {
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
    private final AtomicBoolean messageHookInstalled = new AtomicBoolean(false);
    private volatile Context applicationContext;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "[RIGHTS-USER-MSG-DIAG] module loaded; version="
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
                            "USER_MESSAGE_DIAG_RUNTIME",
                            "moduleVersion=" + BuildConfig.VERSION_NAME
                                    + " | policy=apiCode+serverToast-only",
                            "INFO"
                    );
                    installMessageHook(classLoader);
                }
                return result;
            });
        } catch (Throwable error) {
            applicationHookInstalled.set(false);
            log(Log.ERROR, TAG, "[RIGHTS-USER-MSG-DIAG] ThemeApplication hook failed", error);
        }
    }

    private void installMessageHook(ClassLoader classLoader) {
        if (!messageHookInstalled.compareAndSet(false, true)) {
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
                    String apiCode = readApiCode(raw);
                    try {
                        Object result = chain.proceed();
                        String resultCode = readResultCode(result);
                        String serverToast = readServerToast(result);
                        publish(
                                "DOWNLOAD_RIGHTS_USER_MESSAGE",
                                signature
                                        + " | apiCode=" + apiCode
                                        + " | resultCode=" + resultCode
                                        + " | serverToast=" + serverToast,
                                "INFO"
                        );
                        return result;
                    } catch (Throwable error) {
                        publish(
                                "DOWNLOAD_RIGHTS_USER_MESSAGE_THROW",
                                signature + " | apiCode=" + apiCode
                                        + " | error=" + error.getClass().getName()
                                        + ": " + safe(error.getMessage()),
                                "ERROR"
                        );
                        throw error;
                    }
                });
                count++;
                publish(
                        "HOOK_INSTALLED",
                        signature + " | payloadPolicy=apiCode+serverToast-only",
                        "INFO"
                );
            }
            if (count == 0) {
                publish(
                        "HOOK_NOT_FOUND",
                        DOWNLOAD_RIGHTS_CONTROLLER + "#s(java.lang.String) user-message diagnostics",
                        "WARN"
                );
            }
        } catch (Throwable error) {
            messageHookInstalled.set(false);
            publish(
                    "HOOK_INSTALL_ERROR",
                    DOWNLOAD_RIGHTS_CONTROLLER + "#s user-message diagnostics | "
                            + error.getClass().getName() + ": " + safe(error.getMessage()),
                    "ERROR"
            );
            log(Log.ERROR, TAG, "[RIGHTS-USER-MSG-DIAG] message hook failed", error);
        }
    }

    private static String readApiCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "<missing>";
        }
        try {
            JSONObject object = new JSONObject(raw.trim());
            Object value = object.opt("apiCode");
            return safePrimitive(value);
        } catch (Throwable ignored) {
            return "<unavailable>";
        }
    }

    private static String readResultCode(Object result) {
        if (!(result instanceof Pair<?, ?>)) {
            return "<unavailable>";
        }
        return safePrimitive(((Pair<?, ?>) result).first);
    }

    private static String readServerToast(Object result) {
        if (!(result instanceof Pair<?, ?>)) {
            return "<unavailable>";
        }
        Object payload = ((Pair<?, ?>) result).second;
        if (!(payload instanceof JSONObject)) {
            return "<unavailable>";
        }
        Object value = ((JSONObject) payload).opt("serverToast");
        if (value == null || value == JSONObject.NULL) {
            return "null";
        }
        if (!(value instanceof CharSequence)) {
            return "<" + value.getClass().getSimpleName() + ">";
        }
        String text = String.valueOf(value);
        if (text.length() > 320) {
            return "<string length=" + text.length() + ">";
        }
        return '"' + safe(text) + '"';
    }

    private static String safePrimitive(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return safe(String.valueOf(value));
        }
        if (value instanceof CharSequence) {
            String text = String.valueOf(value);
            if (text.length() > 80) {
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
            extras.putString(KEY_SOURCE, "ThemeManager.RightsUserMessageDiagnostics");
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
            log(Log.WARN, TAG, "[RIGHTS-USER-MSG-DIAG] publish failed: " + event, error);
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
