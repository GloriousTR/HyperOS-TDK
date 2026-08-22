package com.glorious.hyperostdk.xposed;

import android.content.Context;
import android.os.Bundle;
import android.util.JsonReader;
import android.util.Log;

import com.glorious.hyperostdk.BuildConfig;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModule;

/**
 * Build 36 read-only JsonReader tracer for Theme Manager local-resource parsing.
 *
 * <p>The target crash is "Expected BEGIN_OBJECT but was BEGIN_ARRAY" inside the
 * ThemeDetailActivity/local-resource adapter. This module never changes reader state,
 * arguments, return values, theme metadata, DRM state or resource files. It only records
 * field names, container token types and a compact Theme Manager stack around mismatches.</p>
 */
public final class LocalJsonSchemaDiagnosticsModule extends XposedModule {
    private static final String TAG = "HyperOS-TDK";
    private static final String TARGET_PACKAGE = "com.android.thememanager";
    private static final String THEME_APPLICATION = "com.android.thememanager.ThemeApplication";

    private static final String CONTROL_AUTHORITY = "com.glorious.hyperostdk.control";
    private static final String METHOD_DIAG_APPEND = "diag_append";
    private static final String KEY_SOURCE = "diag_source";
    private static final String KEY_EVENT = "diag_event";
    private static final String KEY_DETAIL = "diag_detail";
    private static final String KEY_LEVEL = "diag_level";
    private static final String KEY_EVENT_AT = "diag_event_at";

    private static final Pattern SAFE_FIELD = Pattern.compile("[A-Za-z0-9_.-]{1,120}");
    private static final int MAX_RECENT = 12;
    private static final int MAX_CONTAINER_EVENTS_PER_THREAD = 180;

    private final AtomicBoolean applicationHookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean readerHooksInstalled = new AtomicBoolean(false);
    private volatile Context applicationContext;
    private volatile String readerHookStatus = "not-attempted";

    private final ThreadLocal<TraceState> traceState = ThreadLocal.withInitial(TraceState::new);
    private final ThreadLocal<Boolean> publishingGuard = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "[LOCAL-JSON-SCHEMA] module loaded; version="
                + BuildConfig.VERSION_NAME + "(" + BuildConfig.VERSION_CODE + ")"
                + ", process=" + param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        installReaderHooks();
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
                            "JSON_SCHEMA_TRACER_RUNTIME",
                            "moduleVersion=" + BuildConfig.VERSION_NAME
                                    + " | versionCode=" + BuildConfig.VERSION_CODE
                                    + " | readerHooks=" + readerHookStatus
                                    + " | policy=field-name+container-type+compact-stack-only",
                            "INFO"
                    );
                }
                return result;
            });
        } catch (Throwable error) {
            applicationHookInstalled.set(false);
            readerHookStatus = "application-hook-error:" + error.getClass().getSimpleName();
            log(Log.ERROR, TAG, "[LOCAL-JSON-SCHEMA] ThemeApplication bridge failed", error);
        }
    }

    private void installReaderHooks() {
        if (!readerHooksInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            Method nextName = JsonReader.class.getDeclaredMethod("nextName");
            Method beginObject = JsonReader.class.getDeclaredMethod("beginObject");
            Method beginArray = JsonReader.class.getDeclaredMethod("beginArray");

            hook(nextName).intercept(chain -> {
                Object result = chain.proceed();
                Object receiver = chain.getThisObject();
                if (!(receiver instanceof JsonReader) || isPublishing() || !isRelevantStack()) {
                    return result;
                }

                String field = result == null ? "<null>" : String.valueOf(result);
                String actual = safePeek((JsonReader) receiver);
                TraceState state = traceState.get();
                state.lastName = safeField(field);
                state.add(state.lastName + ':' + actual);

                if (("BEGIN_OBJECT".equals(actual) || "BEGIN_ARRAY".equals(actual))
                        && state.containerEvents++ < MAX_CONTAINER_EVENTS_PER_THREAD) {
                    publish(
                            "JSON_FIELD_CONTAINER_TYPE",
                            "field=" + state.lastName
                                    + " | actual=" + actual
                                    + " | recent=" + state.recentSummary()
                                    + " | stack=" + stackSummary(),
                            "INFO"
                    );
                }
                return result;
            });

            hook(beginObject).intercept(chain -> {
                Object receiver = chain.getThisObject();
                boolean relevant = receiver instanceof JsonReader && !isPublishing() && isRelevantStack();
                String actual = relevant ? safePeek((JsonReader) receiver) : "n/a";
                try {
                    return chain.proceed();
                } catch (Throwable error) {
                    if (relevant) {
                        TraceState state = traceState.get();
                        publish(
                                "JSON_BEGIN_OBJECT_MISMATCH",
                                "field=" + state.lastName
                                        + " | expected=BEGIN_OBJECT"
                                        + " | actual=" + actual
                                        + " | recent=" + state.recentSummary()
                                        + " | stack=" + stackSummary()
                                        + " | error=" + error.getClass().getName()
                                        + ": " + safe(error.getMessage()),
                                "ERROR"
                        );
                    }
                    throw error;
                }
            });

            hook(beginArray).intercept(chain -> {
                Object receiver = chain.getThisObject();
                boolean relevant = receiver instanceof JsonReader && !isPublishing() && isRelevantStack();
                String actual = relevant ? safePeek((JsonReader) receiver) : "n/a";
                try {
                    return chain.proceed();
                } catch (Throwable error) {
                    if (relevant) {
                        TraceState state = traceState.get();
                        publish(
                                "JSON_BEGIN_ARRAY_MISMATCH",
                                "field=" + state.lastName
                                        + " | expected=BEGIN_ARRAY"
                                        + " | actual=" + actual
                                        + " | recent=" + state.recentSummary()
                                        + " | stack=" + stackSummary()
                                        + " | error=" + error.getClass().getName()
                                        + ": " + safe(error.getMessage()),
                                "ERROR"
                        );
                    }
                    throw error;
                }
            });

            readerHookStatus = "installed(nextName+beginObject+beginArray)";
            log(Log.INFO, TAG, "[LOCAL-JSON-SCHEMA] JsonReader hooks installed");
        } catch (Throwable error) {
            readerHooksInstalled.set(false);
            readerHookStatus = "install-error:" + error.getClass().getSimpleName()
                    + ':' + safe(error.getMessage());
            log(Log.ERROR, TAG, "[LOCAL-JSON-SCHEMA] JsonReader hooks failed", error);
        }
    }

    private static String safePeek(JsonReader reader) {
        try {
            Object token = reader.peek();
            return token == null ? "null" : String.valueOf(token);
        } catch (Throwable error) {
            return "peek-error:" + error.getClass().getSimpleName();
        }
    }

    private static boolean isRelevantStack() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        boolean themeManager = false;
        boolean localPath = false;
        boolean l3Adapter = false;
        for (StackTraceElement frame : stack) {
            String name = frame.getClassName();
            if (name.startsWith("com.android.thememanager.")) {
                themeManager = true;
            }
            if (name.startsWith("com.android.thememanager.activity.ThemeDetailActivity")
                    || name.startsWith("com.android.thememanager.mine.local.")
                    || name.startsWith("com.android.thememanager.basemodule.controller.r")) {
                localPath = true;
            }
            if (name.startsWith("l3.")) {
                l3Adapter = true;
            }
        }
        return localPath || (themeManager && l3Adapter);
    }

    private static String stackSummary() {
        List<String> frames = new ArrayList<>();
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String name = frame.getClassName();
            if (!(name.startsWith("com.android.thememanager.") || name.startsWith("l3."))) {
                continue;
            }
            frames.add(name + '#' + frame.getMethodName() + ':' + frame.getLineNumber());
            if (frames.size() >= 10) {
                break;
            }
        }
        return frames.isEmpty() ? "no-relevant-stack" : String.join(" > ", frames);
    }

    private boolean isPublishing() {
        return Boolean.TRUE.equals(publishingGuard.get());
    }

    private void publish(String event, String detail, String level) {
        String safeDetail = safe(detail);
        int priority = "ERROR".equals(level) ? Log.ERROR : ("WARN".equals(level) ? Log.WARN : Log.INFO);
        log(priority, TAG, "[LOCAL-JSON-SCHEMA] " + event + " | " + safeDetail);

        Context context = applicationContext;
        if (context == null || isPublishing()) {
            return;
        }
        try {
            publishingGuard.set(Boolean.TRUE);
            Bundle extras = new Bundle();
            extras.putString(KEY_SOURCE, "ThemeManager.LocalJsonSchemaDiagnostics");
            extras.putString(KEY_EVENT, event);
            extras.putString(KEY_DETAIL, safeDetail);
            extras.putString(KEY_LEVEL, level);
            extras.putLong(KEY_EVENT_AT, System.currentTimeMillis());
            context.getContentResolver().call(
                    CONTROL_AUTHORITY,
                    METHOD_DIAG_APPEND,
                    null,
                    extras
            );
        } catch (Throwable error) {
            log(Log.WARN, TAG, "[LOCAL-JSON-SCHEMA] publish failed: " + event, error);
        } finally {
            publishingGuard.set(Boolean.FALSE);
        }
    }

    private static String safeField(String value) {
        if (value == null) {
            return "null";
        }
        return SAFE_FIELD.matcher(value).matches() ? value : "<redacted-field>";
    }

    private static String safe(String value) {
        if (value == null) {
            return "null";
        }
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > 2800 ? oneLine.substring(0, 2800) + "…" : oneLine;
    }

    private static final class TraceState {
        String lastName = "<root>";
        int containerEvents = 0;
        final ArrayDeque<String> recent = new ArrayDeque<>();

        void add(String value) {
            if (recent.size() >= MAX_RECENT) {
                recent.removeFirst();
            }
            recent.addLast(value);
        }

        String recentSummary() {
            return recent.isEmpty() ? "none" : String.join(" > ", recent);
        }
    }
}
