package com.glorious.hyperostdk.xposed;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * Streams read-only Theme Manager apply diagnostics directly into HyperOS TDK.
 *
 * <p>No arguments, return values, DRM state, product identifiers, entitlement
 * results, or Theme Manager files are modified by this module.</p>
 */
public final class ApplyDiagnosticsInAppModule extends XposedModule {
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

    private static final String ONLINE_BASE_CONTROLLER =
            "com.android.thememanager.basemodule.controller.online.f";
    private static final String ONLINE_CONTROLLER =
            "com.android.thememanager.controller.online.a";
    private static final String DETAIL_CONTROLLER =
            "com.android.thememanager.detail.u";
    private static final String DETAIL_ASYNC_TASK =
            "com.android.thememanager.detail.u$d";

    private final AtomicBoolean applicationHookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean applyHooksInstalled = new AtomicBoolean(false);
    private volatile Context applicationContext;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "[APPLY-DIAG] module loaded; process=" + param.getProcessName()
                + ", framework=" + getFrameworkName()
                + ", api=" + getApiVersion());
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
                    publish("MODULE_READY",
                            "Theme Manager hazır; in-app Apply Diagnostics bağlanıyor.",
                            "INFO");
                    installApplyHooks(classLoader);
                } else {
                    log(Log.ERROR, TAG, "[APPLY-DIAG] ThemeApplication receiver is not Context");
                }
                return result;
            });
            log(Log.INFO, TAG, "[APPLY-DIAG] ThemeApplication bridge installed");
        } catch (Throwable error) {
            applicationHookInstalled.set(false);
            log(Log.ERROR, TAG, "[APPLY-DIAG] Unable to hook ThemeApplication.onCreate", error);
        }
    }

    private void installApplyHooks(ClassLoader classLoader) {
        if (!applyHooksInstalled.compareAndSet(false, true)) {
            return;
        }

        int count = 0;
        count += hookNamedMethods(classLoader, ONLINE_BASE_CONTROLLER, "Y");
        count += hookNamedMethods(classLoader, ONLINE_CONTROLLER, "a");
        count += hookNamedMethods(classLoader, DETAIL_CONTROLLER, "c");
        count += hookNamedMethods(classLoader, DETAIL_ASYNC_TASK, "doInBackground");

        publish("HOOK_INSTALL_COMPLETE", "hookedMethods=" + count, count > 0 ? "INFO" : "WARN");
        log(Log.INFO, TAG, "[APPLY-DIAG] installation complete; hookedMethods=" + count);
    }

    private int hookNamedMethods(ClassLoader classLoader, String className, String methodName) {
        try {
            Class<?> targetClass = Class.forName(className, false, classLoader);
            int installedCount = 0;

            for (Method method : targetClass.getDeclaredMethods()) {
                if (!methodName.equals(method.getName())) {
                    continue;
                }
                int modifiers = method.getModifiers();
                if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)) {
                    continue;
                }

                method.setAccessible(true);
                final String signature = signatureOf(method);

                hook(method).intercept(chain -> {
                    long startedAt = System.nanoTime();
                    String receiver = describeObject(chain.getThisObject());
                    String args = describeArgs(chain.getArgs());
                    publish("HOOK_ENTER", signature + " | this=" + receiver + " | args=" + args, "INFO");

                    try {
                        Object result = chain.proceed();
                        long elapsedUs = (System.nanoTime() - startedAt) / 1_000L;
                        publish("HOOK_EXIT", signature + " | result=" + describeObject(result)
                                + " | elapsedUs=" + elapsedUs, "INFO");
                        return result;
                    } catch (Throwable error) {
                        long elapsedUs = (System.nanoTime() - startedAt) / 1_000L;
                        publish("HOOK_THROW", signature
                                + " | error=" + error.getClass().getName()
                                + ": " + safeString(error.getMessage())
                                + " | elapsedUs=" + elapsedUs, "ERROR");
                        throw error;
                    }
                });

                installedCount++;
                publish("HOOK_INSTALLED", signature, "INFO");
            }

            if (installedCount == 0) {
                publish("HOOK_NOT_FOUND", className + "#" + methodName, "WARN");
            }
            return installedCount;
        } catch (Throwable error) {
            publish("HOOK_INSTALL_ERROR", className + "#" + methodName + " | "
                    + error.getClass().getName() + ": " + safeString(error.getMessage()), "ERROR");
            log(Log.ERROR, TAG, "[APPLY-DIAG] unable to hook " + className + "#" + methodName, error);
            return 0;
        }
    }

    private void publish(String event, String detail, String level) {
        Context context = applicationContext;
        if (context == null) {
            return;
        }
        try {
            Bundle extras = new Bundle();
            extras.putString(KEY_SOURCE, "ThemeManager.ApplyDiagnostics");
            extras.putString(KEY_EVENT, event);
            extras.putString(KEY_DETAIL, safeString(detail));
            extras.putString(KEY_LEVEL, level);
            extras.putLong(KEY_EVENT_AT, System.currentTimeMillis());
            context.getContentResolver().call(
                    CONTROL_AUTHORITY,
                    METHOD_DIAG_APPEND,
                    null,
                    extras
            );
        } catch (Throwable error) {
            log(Log.WARN, TAG, "[APPLY-DIAG] unable to publish in-app diagnostic: " + event, error);
        }
    }

    private static String signatureOf(Method method) {
        StringBuilder builder = new StringBuilder();
        builder.append(method.getDeclaringClass().getName())
                .append('#')
                .append(method.getName())
                .append('(');
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(parameterTypes[i].getName());
        }
        return builder.append(") -> ")
                .append(method.getReturnType().getName())
                .toString();
    }

    private static String describeArgs(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return "[]";
        }
        List<String> values = new ArrayList<>(args.size());
        for (int i = 0; i < args.size(); i++) {
            values.add(i + "=" + describeObject(args.get(i)));
        }
        return values.toString();
    }

    private static String describeObject(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence || value instanceof Number
                || value instanceof Boolean || value instanceof Enum<?>) {
            return value.getClass().getSimpleName() + "(" + safeString(String.valueOf(value)) + ")";
        }

        Class<?> type = value.getClass();
        StringBuilder builder = new StringBuilder(type.getName());
        List<String> properties = collectKnownProperties(value);
        if (!properties.isEmpty()) {
            builder.append('{').append(String.join(", ", properties)).append('}');
        } else {
            builder.append('@').append(Integer.toHexString(System.identityHashCode(value)));
        }
        return builder.toString();
    }

    private static List<String> collectKnownProperties(Object value) {
        List<String> properties = new ArrayList<>();
        String[] getterNames = {
                "getTitle", "getName", "getLocalId", "getOnlineId", "getAssembleId",
                "getProductId", "getId", "getResourceId", "getDownloadPath", "getDownloadUrl"
        };

        for (String getterName : getterNames) {
            Object property = invokeZeroArgGetter(value, getterName);
            if (property != null) {
                properties.add(propertyLabel(getterName) + '=' + safeString(String.valueOf(property)));
            }
        }

        if (properties.size() < 12) {
            collectInterestingFields(value, properties);
        }
        return properties;
    }

    private static Object invokeZeroArgGetter(Object value, String name) {
        Class<?> current = value.getClass();
        while (current != null && current != Object.class) {
            try {
                Method method = current.getDeclaredMethod(name);
                if (method.getParameterCount() != 0) {
                    return null;
                }
                method.setAccessible(true);
                return method.invoke(value);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static void collectInterestingFields(Object value, List<String> properties) {
        Class<?> current = value.getClass();
        int inspected = 0;
        while (current != null && current != Object.class && inspected < 80 && properties.size() < 12) {
            for (Field field : current.getDeclaredFields()) {
                if (inspected++ >= 80 || properties.size() >= 12) {
                    return;
                }
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                String lower = field.getName().toLowerCase(Locale.ROOT);
                if (!(lower.contains("id") || lower.contains("title") || lower.contains("name")
                        || lower.contains("product") || lower.contains("online")
                        || lower.contains("assemble") || lower.contains("local")
                        || lower.contains("download") || lower.contains("login"))) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object fieldValue = field.get(value);
                    if (fieldValue == null || fieldValue instanceof CharSequence
                            || fieldValue instanceof Number || fieldValue instanceof Boolean
                            || fieldValue instanceof Enum<?>) {
                        properties.add(field.getName() + '=' + safeString(String.valueOf(fieldValue)));
                    }
                } catch (Throwable ignored) {
                    // Read-only diagnostics must never interfere with Theme Manager execution.
                }
            }
            current = current.getSuperclass();
        }
    }

    private static String propertyLabel(String getterName) {
        if (getterName.startsWith("get") && getterName.length() > 3) {
            String raw = getterName.substring(3);
            return Character.toLowerCase(raw.charAt(0)) + raw.substring(1);
        }
        return getterName;
    }

    private static String safeString(String value) {
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
