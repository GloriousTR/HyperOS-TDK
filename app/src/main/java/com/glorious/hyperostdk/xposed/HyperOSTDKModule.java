package com.glorious.hyperostdk.xposed;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * LSPosed bridge for Xiaomi Theme Manager.
 *
 * <p>v0.2.1 keeps the read-only readiness probe, then registers a protected one-shot control
 * receiver inside the Theme Manager process. The receiver accepts only requests sent by the
 * HyperOS TDK app's signature-protected permission and only imports readable shared-storage MTZ
 * ZIP files. No import is triggered automatically.</p>
 */
public final class HyperOSTDKModule extends XposedModule {
    private static final String TAG = "HyperOS-TDK";
    private static final String TARGET_PACKAGE = "com.android.thememanager";

    public static final String ACTION_IMPORT_MTZ =
            "com.glorious.hyperostdk.action.IMPORT_MTZ";
    public static final String CONTROL_PERMISSION =
            "com.glorious.hyperostdk.permission.IMPORT_CONTROL";
    public static final String EXTRA_PATH = "mtz_path";
    public static final String EXTRA_REQUEST_ID = "request_id";

    private static final String ACTION_IMPORT_START = "action_resource_import_start";
    private static final String ACTION_IMPORT_UPDATE = "action_resource_import_udpate";
    private static final String ACTION_IMPORT_COMPLETE = "action_resource_import_complete";
    private static final String ACTION_IMPORT_FAIL = "action_resource_import_fail";

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

    private static final long MAX_MTZ_BYTES = 512L * 1024L * 1024L;

    private final AtomicBoolean controlBridgeRegistered = new AtomicBoolean(false);
    private volatile ClassLoader themeManagerClassLoader;
    private volatile String activeRequestId;

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

        themeManagerClassLoader = param.getClassLoader();
        log(Log.INFO, TAG, "Theme Manager package ready; starting import readiness probe.");
        runReadOnlyProbe(param.getClassLoader());
        installApplicationAttachBridge();
    }

    private void installApplicationAttachBridge() {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object thisObject = chain.getThisObject();
                    if (thisObject instanceof Application) {
                        registerControlBridge((Application) thisObject);
                    }
                } catch (Throwable error) {
                    log(Log.ERROR, TAG, "Failed to register controlled import bridge after Application.attach", error);
                }
                return result;
            });
            log(Log.INFO, TAG, "[OK] Application.attach bridge installed; no import is triggered automatically.");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Unable to install Application.attach bridge", error);
        }
    }

    private void registerControlBridge(Application application) {
        if (!controlBridgeRegistered.compareAndSet(false, true)) {
            return;
        }

        Context context = application.getApplicationContext();

        BroadcastReceiver controlReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (!ACTION_IMPORT_MTZ.equals(intent.getAction())) {
                    return;
                }
                String requestId = intent.getStringExtra(EXTRA_REQUEST_ID);
                String path = intent.getStringExtra(EXTRA_PATH);
                handleControlledImport(receiverContext, requestId, path);
            }
        };

        IntentFilter controlFilter = new IntentFilter(ACTION_IMPORT_MTZ);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                    controlReceiver,
                    controlFilter,
                    CONTROL_PERMISSION,
                    null,
                    Context.RECEIVER_EXPORTED
            );
        } else {
            context.registerReceiver(controlReceiver, controlFilter, CONTROL_PERMISSION, null);
        }

        BroadcastReceiver resultMonitor = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                String action = intent.getAction();
                if (action == null) {
                    return;
                }
                String request = activeRequestId == null ? "none" : activeRequestId;
                log(Log.INFO, TAG, "[IMPORT EVENT] request=" + request + " action=" + action);
                if (ACTION_IMPORT_COMPLETE.equals(action) || ACTION_IMPORT_FAIL.equals(action)) {
                    activeRequestId = null;
                }
            }
        };

        IntentFilter resultFilter = new IntentFilter();
        resultFilter.addAction(ACTION_IMPORT_START);
        resultFilter.addAction(ACTION_IMPORT_UPDATE);
        resultFilter.addAction(ACTION_IMPORT_COMPLETE);
        resultFilter.addAction(ACTION_IMPORT_FAIL);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(resultMonitor, resultFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(resultMonitor, resultFilter);
        }

        log(Log.INFO, TAG, "CONTROLLED IMPORT BRIDGE READY: protected receiver registered in Theme Manager process.");
    }

    private void handleControlledImport(Context context, String requestId, String rawPath) {
        String safeRequestId = requestId == null || requestId.isBlank() ? "unknown" : requestId;
        try {
            File mtz = validateMtz(rawPath);
            activeRequestId = safeRequestId;
            log(Log.INFO, TAG, "CONTROLLED IMPORT REQUEST accepted: request=" + safeRequestId
                    + " file=" + mtz.getName() + " size=" + mtz.length());

            ClassLoader classLoader = themeManagerClassLoader;
            if (classLoader == null) {
                throw new IllegalStateException("Theme Manager ClassLoader is unavailable");
            }

            Class<?> controllerClass = Class.forName(CONTROLLER, false, classLoader);
            Class<?> resourceContextControllerClass =
                    Class.forName(CONTROLLER_RESOURCE_CONTEXT, false, classLoader);
            Class<?> resourceContextClass = Class.forName(RESOURCE_CONTEXT, false, classLoader);
            Class<?> resourceClass = Class.forName(RESOURCE, false, classLoader);
            Class<?> importManagerClass = Class.forName(THEME_IMPORT_MANAGER, false, classLoader);

            Method controllerSingleton = controllerClass.getDeclaredMethod("e");
            Method resourceContextController = controllerClass.getDeclaredMethod("g");
            Method getResourceContext = resourceContextControllerClass.getDeclaredMethod("a");
            Constructor<?> resourceConstructor = resourceClass.getDeclaredConstructor();
            Method setDownloadPath = resourceClass.getDeclaredMethod("setDownloadPath", String.class);
            Method getImportManager = controllerClass.getDeclaredMethod("i");
            Method importMethod = importManagerClass.getDeclaredMethod(
                    "v",
                    resourceContextClass,
                    resourceClass
            );

            Object controller = controllerSingleton.invoke(null);
            Object resourceContextControllerObject = resourceContextController.invoke(controller);
            Object resourceContext = getResourceContext.invoke(resourceContextControllerObject);
            Object resource = resourceConstructor.newInstance();
            setDownloadPath.invoke(resource, mtz.getAbsolutePath());
            Object importManager = getImportManager.invoke(controller);

            log(Log.INFO, TAG, "CONTROLLED IMPORT invoking ThemeImportManager.v(...): request="
                    + safeRequestId);
            importMethod.invoke(importManager, resourceContext, resource);
            log(Log.INFO, TAG, "CONTROLLED IMPORT queued successfully: request=" + safeRequestId
                    + ". Waiting for Theme Manager import event broadcasts.");
        } catch (Throwable error) {
            activeRequestId = null;
            Throwable cause = error instanceof InvocationTargetException && error.getCause() != null
                    ? error.getCause()
                    : error;
            log(Log.ERROR, TAG, "CONTROLLED IMPORT failed before/while queueing: request="
                    + safeRequestId + " path=" + rawPath, cause);
        }
    }

    private File validateMtz(String rawPath) throws Exception {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("MTZ path is empty");
        }

        File file = new File(rawPath).getCanonicalFile();
        String canonical = file.getPath();
        if (!canonical.startsWith("/storage/emulated/0/")) {
            throw new SecurityException("Only shared storage paths under /storage/emulated/0 are accepted");
        }
        if (!canonical.toLowerCase(Locale.ROOT).endsWith(".mtz")) {
            throw new IllegalArgumentException("Selected file does not end with .mtz");
        }
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("MTZ file does not exist: " + canonical);
        }
        if (!file.canRead()) {
            throw new SecurityException("Theme Manager process cannot read MTZ path: " + canonical);
        }
        if (file.length() < 4L || file.length() > MAX_MTZ_BYTES) {
            throw new IllegalArgumentException("MTZ size is outside allowed range: " + file.length());
        }

        try (FileInputStream input = new FileInputStream(file)) {
            int first = input.read();
            int second = input.read();
            if (first != 'P' || second != 'K') {
                throw new IllegalArgumentException("MTZ is not a ZIP container (missing PK signature)");
            }
        }

        return file;
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
            log(Log.INFO, TAG, "[OK] Import method is present and remains idle until a protected user request: "
                    + importMethod);

            Method localImportCaller = requireMethod(localCustomizeTaskClass, "e", Void[].class);
            log(Log.INFO, TAG, "[OK] Local customize task import caller present: " + localImportCaller);
            passed++;

            log(Log.INFO, TAG, "READINESS RESULT: " + passed + "/" + total
                    + " checks passed. No Theme Manager import was invoked during readiness.");
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
