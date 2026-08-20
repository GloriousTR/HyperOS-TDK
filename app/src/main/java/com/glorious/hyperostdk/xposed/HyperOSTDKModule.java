package com.glorious.hyperostdk.xposed;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.libxposed.api.XposedModule;

/**
 * Read-only LSPosed readiness probe for Xiaomi Theme Manager.
 *
 * <p>This entry point deliberately does not invoke Theme Manager private methods and does not
 * hook or alter their behavior. It only resolves the exact classes/methods discovered during
 * static analysis of Theme Manager 3.0.6.8-global.</p>
 */
public final class HyperOSTDKModule extends XposedModule {
    private static final String TAG = "HyperOS-TDK";
    private static final String TARGET_PACKAGE = "com.android.thememanager";

    private static final String CONTROLLER =
            "com.android.thememanager.basemodule.controller.a";
    private static final String CONTROLLER_RESOURCE_CONTEXT =
            "com.android.thememanager.basemodule.controller.s";
    private static final String RESOURCE_CONTEXT =
            "com.android.thememanager.basemodule.model.ResourceContext";
    private static final String RESOURCE =
            "com.android.thememanager.basemodule.resource.model.Resource";
    private static final String THEME_IMPORT_MANAGER =
            "com.android.thememanager.basemodule.unzip.c";
    private static final String LOCAL_CUSTOMIZE_TASK =
            "com.android.thememanager.mine.local.customize.a";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Module loaded; process=" + param.getProcessName()
                + ", framework=" + getFrameworkName()
                + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }

        log(Log.INFO, TAG, "Theme Manager package ready; starting read-only import readiness probe.");
        runReadOnlyProbe(param.getClassLoader());
    }

    private void runReadOnlyProbe(ClassLoader classLoader) {
        int passed = 0;
        final int total = 10;

        try {
            Class<?> controllerClass = load(classLoader, CONTROLLER);
            passed++;
            Class<?> controllerResourceContextClass = load(classLoader, CONTROLLER_RESOURCE_CONTEXT);
            passed++;
            Class<?> resourceContextClass = load(classLoader, RESOURCE_CONTEXT);
            passed++;
            Class<?> resourceClass = load(classLoader, RESOURCE);
            passed++;
            Class<?> importManagerClass = load(classLoader, THEME_IMPORT_MANAGER);
            passed++;
            Class<?> localCustomizeTaskClass = load(classLoader, LOCAL_CUSTOMIZE_TASK);
            passed++;

            Method controllerSingleton = requireMethod(controllerClass, "e");
            requireStatic(controllerSingleton, CONTROLLER + ".e()");
            passed++;

            Method resourceContextController = requireMethod(controllerClass, "g");
            assertReturnType(resourceContextController, controllerResourceContextClass,
                    CONTROLLER + ".g()");
            Method getResourceContext = requireMethod(controllerResourceContextClass, "a");
            assertReturnType(getResourceContext, resourceContextClass,
                    CONTROLLER_RESOURCE_CONTEXT + ".a()");
            passed++;

            Constructor<?> resourceConstructor = resourceClass.getDeclaredConstructor();
            log(Log.INFO, TAG, "[OK] Resource default constructor: " + resourceConstructor);
            Method setDownloadPath = requireMethod(resourceClass, "setDownloadPath", String.class);
            assertReturnType(setDownloadPath, void.class, RESOURCE + ".setDownloadPath(String)");
            passed++;

            Method getImportManager = requireMethod(controllerClass, "i");
            assertReturnType(getImportManager, importManagerClass, CONTROLLER + ".i()");
            Method importMethod = requireMethod(
                    importManagerClass,
                    "v",
                    resourceContextClass,
                    resourceClass
            );
            assertReturnType(importMethod, void.class,
                    THEME_IMPORT_MANAGER + ".v(ResourceContext, Resource)");
            log(Log.INFO, TAG, "[OK] Import method is present but was NOT invoked: " + importMethod);

            Method localImportCaller = requireMethod(localCustomizeTaskClass, "e", Void[].class);
            log(Log.INFO, TAG, "[OK] Local customize task import caller present: " + localImportCaller);
            passed++;

            log(Log.INFO, TAG, "READINESS RESULT: " + passed + "/" + total
                    + " checks passed. No Theme Manager method was invoked.");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "READINESS RESULT: " + passed + "/" + total
                    + " checks passed before failure.", error);
        }
    }

    private Class<?> load(ClassLoader classLoader, String name) throws ClassNotFoundException {
        Class<?> clazz = Class.forName(name, false, classLoader);
        log(Log.INFO, TAG, "[OK] class " + name);
        return clazz;
    }

    private Method requireMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameterTypes);
        log(Log.INFO, TAG, "[OK] method " + method);
        return method;
    }

    private void requireStatic(Method method, String label) {
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new IllegalStateException(label + " is not static");
        }
        log(Log.INFO, TAG, "[OK] static " + label);
    }

    private void assertReturnType(Method method, Class<?> expected, String label) {
        if (!method.getReturnType().equals(expected)) {
            throw new IllegalStateException(label + " return type changed: "
                    + method.getReturnType().getName() + " != " + expected.getName());
        }
        log(Log.INFO, TAG, "[OK] return type " + label + " -> " + expected.getName());
    }
}
