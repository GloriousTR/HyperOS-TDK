package com.glorious.hyperostdk.xposed;

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
 * Read-only diagnostics for HyperOS Theme Manager's theme-apply path.
 *
 * <p>This module intentionally does not alter arguments, return values, DRM state,
 * product identifiers, or entitlement results. It only records the call path that
 * leads a locally imported MTZ into the online/download-right checks.</p>
 */
public final class ApplyDiagnosticsModule extends XposedModule {
    private static final String TAG = "HyperOS-TDK";
    private static final String PREFIX = "[APPLY-DIAG] ";
    private static final String TARGET_PACKAGE = "com.android.thememanager";

    private static final String ONLINE_BASE_CONTROLLER =
            "com.android.thememanager.basemodule.controller.online.f";
    private static final String ONLINE_CONTROLLER =
            "com.android.thememanager.controller.online.a";
    private static final String DETAIL_CONTROLLER =
            "com.android.thememanager.detail.u";
    private static final String DETAIL_ASYNC_TASK =
            "com.android.thememanager.detail.u$d";

    private final AtomicBoolean installed = new AtomicBoolean(false);

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, PREFIX + "module loaded; process=" + param.getProcessName()
                + ", framework=" + getFrameworkName()
                + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        if (!installed.compareAndSet(false, true)) {
            return;
        }

        ClassLoader classLoader = param.getClassLoader();
        log(Log.INFO, TAG, PREFIX + "Theme Manager ready; installing read-only apply hooks.");

        int count = 0;
        count += hookNamedMethods(classLoader, ONLINE_BASE_CONTROLLER, "Y");
        count += hookNamedMethods(classLoader, ONLINE_CONTROLLER, "a");
        count += hookNamedMethods(classLoader, DETAIL_CONTROLLER, "c");
        count += hookNamedMethods(classLoader, DETAIL_ASYNC_TASK, "doInBackground");

        log(Log.INFO, TAG, PREFIX + "installation complete; hookedMethods=" + count);
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
                    log(Log.INFO, TAG, PREFIX + "ENTER " + signature
                            + " this=" + receiver + " args=" + args);

                    try {
                        Object result = chain.proceed();
                        long elapsedUs = (System.nanoTime() - startedAt) / 1_000L;
                        log(Log.INFO, TAG, PREFIX + "EXIT  " + signature
                                + " result=" + describeObject(result)
                                + " elapsedUs=" + elapsedUs);
                        return result;
                    } catch (Throwable error) {
                        long elapsedUs = (System.nanoTime() - startedAt) / 1_000L;
                        log(Log.ERROR, TAG, PREFIX + "THROW " + signature
                                + " error=" + error.getClass().getName()
                                + ": " + safeString(error.getMessage())
                                + " elapsedUs=" + elapsedUs,
                                error);
                        throw error;
                    }
                });

                installedCount++;
                log(Log.INFO, TAG, PREFIX + "hooked " + signature);
            }

            if (installedCount == 0) {
                log(Log.WARN, TAG, PREFIX + "no matching method found: "
                        + className + "#" + methodName);
            }
            return installedCount;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, PREFIX + "unable to hook " + className + "#" + methodName, error);
            return 0;
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
                        || lower.contains("download"))) {
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
                    // Diagnostics must never interfere with Theme Manager execution.
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
        if (oneLine.length() > 320) {
            return oneLine.substring(0, 320) + "…";
        }
        return oneLine;
    }
}
