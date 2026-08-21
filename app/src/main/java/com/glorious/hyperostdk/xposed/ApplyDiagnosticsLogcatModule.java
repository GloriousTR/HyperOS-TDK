package com.glorious.hyperostdk.xposed;

import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * v0.3.2 diagnostics entry for the Theme Manager apply path.
 *
 * <p>The v0.3.1 diagnostics used Xposed's own log sink. That is useful inside
 * LSPosed, but external logcat collectors such as Vector may not include those
 * records. This module mirrors every diagnostic record to Android logcat while
 * still writing the same record to the Xposed log.</p>
 *
 * <p>This module is strictly observational: it does not modify arguments,
 * return values, product identifiers, DRM state, or entitlement results.</p>
 */
public final class ApplyDiagnosticsLogcatModule extends XposedModule {
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
        diag(Log.INFO, "module loaded; process=" + param.getProcessName()
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
        diag(Log.INFO, "Theme Manager ready; installing logcat apply hooks.");

        int count = 0;
        count += hookNamedMethods(classLoader, ONLINE_BASE_CONTROLLER, "Y");
        count += hookNamedMethods(classLoader, ONLINE_CONTROLLER, "a");
        count += hookNamedMethods(classLoader, DETAIL_CONTROLLER, "c");
        count += hookNamedMethods(classLoader, DETAIL_ASYNC_TASK, "doInBackground");

        diag(Log.INFO, "installation complete; hookedMethods=" + count);
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
                    diag(Log.INFO, "ENTER " + signature
                            + " this=" + describeObject(chain.getThisObject())
                            + " args=" + describeArgs(chain.getArgs()));

                    try {
                        Object result = chain.proceed();
                        long elapsedUs = (System.nanoTime() - startedAt) / 1_000L;
                        diag(Log.INFO, "EXIT  " + signature
                                + " result=" + describeObject(result)
                                + " elapsedUs=" + elapsedUs);
                        return result;
                    } catch (Throwable error) {
                        long elapsedUs = (System.nanoTime() - startedAt) / 1_000L;
                        diag(Log.ERROR, "THROW " + signature
                                + " error=" + error.getClass().getName()
                                + ": " + safeString(error.getMessage())
                                + " elapsedUs=" + elapsedUs, error);
                        throw error;
                    }
                });

                installedCount++;
                diag(Log.INFO, "hooked " + signature);
            }

            if (installedCount == 0) {
                diag(Log.WARN, "no matching method found: " + className + "#" + methodName);
            }
            return installedCount;
        } catch (Throwable error) {
            diag(Log.ERROR, "unable to hook " + className + "#" + methodName, error);
            return 0;
        }
    }

    private void diag(int priority, String message) {
        String fullMessage = PREFIX + message;
        log(priority, TAG, fullMessage);
        Log.println(priority, TAG, fullMessage);
    }

    private void diag(int priority, String message, Throwable error) {
        String fullMessage = PREFIX + message;
        log(priority, TAG, fullMessage, error);
        Log.println(priority, TAG, fullMessage + "\n" + Log.getStackTraceString(error));
    }

    private static String signatureOf(Method method) {
        StringBuilder builder = new StringBuilder();
        builder.append(method.getDeclaringClass().getName())
                .append('#')
                .append(method.getName())
                .append('(');
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
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
            return value.getClass().getSimpleName()
                    + "(" + safeString(String.valueOf(value)) + ")";
        }
        return value.getClass().getName()
                + "@" + Integer.toHexString(System.identityHashCode(value));
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
