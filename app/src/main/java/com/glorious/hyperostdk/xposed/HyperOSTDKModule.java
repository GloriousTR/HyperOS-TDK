package com.glorious.hyperostdk.xposed;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * LSPosed bridge for Xiaomi Theme Manager.
 *
 * <p>v0.2.5 keeps the proven ContentProvider + ContentObserver command channel and adds direct
 * hooks around ThemeImportManager's start/complete/fail lifecycle methods. This avoids relying on
 * custom broadcast delivery for result tracking and lets HyperOS TDK report the final state in its
 * own UI. Import still requires explicit user selection and confirmation.</p>
 */
public final class HyperOSTDKModule extends XposedModule {
    private static final String TAG = "HyperOS-TDK";
    private static final String TARGET_PACKAGE = "com.android.thememanager";

    private static final String CONTROL_AUTHORITY = "com.glorious.hyperostdk.control";
    private static final Uri CONTROL_URI = Uri.parse("content://" + CONTROL_AUTHORITY + "/command");
    private static final String METHOD_CONSUME = "consume";
    private static final String METHOD_REPORT_RESULT = "report_result";
    private static final String KEY_PRESENT = "present";
    private static final String KEY_REQUEST_ID = "request_id";
    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String KEY_URI = "uri";
    private static final String KEY_STATUS = "status";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_RESULT_AT = "result_at";

    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_START = "start";
    private static final String STATUS_COMPLETE = "complete";
    private static final String STATUS_FAIL = "fail";
    private static final String STATUS_QUEUE_ERROR = "queue_error";

    private static final String THEME_APPLICATION =
            "com.android.thememanager.ThemeApplication";
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
    private final AtomicBoolean lifecycleHooksInstalled = new AtomicBoolean(false);
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();
    private volatile ClassLoader themeManagerClassLoader;
    private volatile String activeRequestId;
    private volatile File activeStagedFile;
    private volatile ContentObserver controlObserver;

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
        installThemeApplicationBridge(param.getClassLoader());
    }

    private void installThemeApplicationBridge(ClassLoader classLoader) {
        try {
            Class<?> themeApplicationClass = Class.forName(THEME_APPLICATION, false, classLoader);
            Method onCreate = themeApplicationClass.getDeclaredMethod("onCreate");
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object thisObject = chain.getThisObject();
                    if (thisObject instanceof Context) {
                        registerControlBridge((Context) thisObject);
                    } else {
                        log(Log.ERROR, TAG, "ThemeApplication.onCreate thisObject is not a Context");
                    }
                } catch (Throwable error) {
                    log(Log.ERROR, TAG, "Failed to register provider IPC bridge after ThemeApplication.onCreate", error);
                }
                return result;
            });
            log(Log.INFO, TAG, "[OK] ThemeApplication.onCreate bridge installed; no import is triggered automatically.");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Unable to install ThemeApplication.onCreate bridge", error);
        }
    }

    private void registerControlBridge(Context applicationContextSource) {
        if (!controlBridgeRegistered.compareAndSet(false, true)) {
            return;
        }

        Context context = applicationContextSource.getApplicationContext();
        if (context == null) {
            context = applicationContextSource;
        }
        final Context appContext = context;

        installImportLifecycleHooks(themeManagerClassLoader, appContext);

        controlObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                log(Log.INFO, TAG, "CONTROLLED IMPORT provider change observed: uri=" + uri);
                importExecutor.execute(() -> consumeProviderCommand(appContext));
            }
        };
        appContext.getContentResolver().registerContentObserver(CONTROL_URI, false, controlObserver);

        log(Log.INFO, TAG,
                "CONTROLLED IMPORT BRIDGE READY: provider observer + direct lifecycle hooks active in Theme Manager process.");
    }

    private void installImportLifecycleHooks(ClassLoader classLoader, Context context) {
        if (classLoader == null) {
            log(Log.ERROR, TAG, "Cannot install lifecycle hooks: Theme Manager ClassLoader unavailable");
            return;
        }
        if (!lifecycleHooksInstalled.compareAndSet(false, true)) {
            return;
        }

        try {
            Class<?> resourceClass = Class.forName(RESOURCE, false, classLoader);
            Class<?> importManagerClass = Class.forName(THEME_IMPORT_MANAGER, false, classLoader);
            Method startMethod = importManagerClass.getDeclaredMethod("s", resourceClass);
            Method completeMethod = importManagerClass.getDeclaredMethod("t", resourceClass, String.class);
            Method failMethod = importManagerClass.getDeclaredMethod("r", resourceClass, String.class);

            hook(startMethod).intercept(chain -> {
                String request = activeRequestId;
                if (request != null) {
                    log(Log.INFO, TAG, "CONTROLLED IMPORT LIFECYCLE START: request=" + request);
                    reportImportResult(context, request, STATUS_START,
                            "ThemeImportManager.s(Resource) reached");
                }
                return chain.proceed();
            });

            hook(completeMethod).intercept(chain -> {
                String request = activeRequestId;
                if (request != null) {
                    log(Log.INFO, TAG, "CONTROLLED IMPORT LIFECYCLE COMPLETE: request=" + request);
                    reportImportResult(context, request, STATUS_COMPLETE,
                            "ThemeImportManager.t(Resource, String) reached");
                }
                try {
                    return chain.proceed();
                } finally {
                    finishActiveRequest(request);
                }
            });

            hook(failMethod).intercept(chain -> {
                String request = activeRequestId;
                if (request != null) {
                    log(Log.ERROR, TAG, "CONTROLLED IMPORT LIFECYCLE FAIL: request=" + request);
                    reportImportResult(context, request, STATUS_FAIL,
                            "ThemeImportManager.r(Resource, String) reached");
                }
                try {
                    return chain.proceed();
                } finally {
                    finishActiveRequest(request);
                }
            });

            log(Log.INFO, TAG,
                    "[OK] Direct ThemeImportManager lifecycle hooks installed: s=start, t=complete, r=fail.");
        } catch (Throwable error) {
            lifecycleHooksInstalled.set(false);
            log(Log.ERROR, TAG, "Unable to install direct ThemeImportManager lifecycle hooks", error);
        }
    }

    private void consumeProviderCommand(Context context) {
        try {
            Bundle command = context.getContentResolver().call(
                    CONTROL_AUTHORITY,
                    METHOD_CONSUME,
                    null,
                    null
            );
            if (command == null || !command.getBoolean(KEY_PRESENT, false)) {
                log(Log.WARN, TAG, "CONTROLLED IMPORT provider notification had no live command.");
                return;
            }

            String requestId = command.getString(KEY_REQUEST_ID);
            String displayName = command.getString(KEY_DISPLAY_NAME);
            String uriText = command.getString(KEY_URI);
            Uri uri = uriText == null ? null : Uri.parse(uriText);

            log(Log.INFO, TAG, "CONTROLLED IMPORT PROVIDER IPC consumed: request=" + requestId
                    + " displayName=" + displayName + " uri=" + uri);
            handleControlledImport(context, requestId, displayName, uri);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "CONTROLLED IMPORT provider IPC consume failed", error);
        }
    }

    private void handleControlledImport(Context context, String requestId, String displayName, Uri uri) {
        String safeRequestId = requestId == null || requestId.isBlank() ? "unknown" : requestId;
        try {
            if (activeRequestId != null) {
                throw new IllegalStateException("Another import request is still active: " + activeRequestId);
            }
            validateRequestMetadata(displayName, uri);
            activeRequestId = safeRequestId;

            File mtz = stageMtzIntoThemeManagerCache(context, safeRequestId, uri);
            activeStagedFile = mtz;
            validateStagedMtz(mtz);

            log(Log.INFO, TAG, "CONTROLLED IMPORT REQUEST accepted: request=" + safeRequestId
                    + " source=" + uri + " staged=" + mtz.getAbsolutePath()
                    + " size=" + mtz.length());

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
            reportImportResult(context, safeRequestId, STATUS_QUEUED,
                    "ThemeImportManager.v(ResourceContext, Resource) queued");
            log(Log.INFO, TAG, "CONTROLLED IMPORT queued successfully: request=" + safeRequestId
                    + ". Direct lifecycle hooks will report the final result.");
        } catch (Throwable error) {
            if (safeRequestId.equals(activeRequestId)) {
                activeRequestId = null;
            }
            cleanupActiveStagedFile();
            Throwable cause = error instanceof InvocationTargetException && error.getCause() != null
                    ? error.getCause()
                    : error;
            reportImportResult(context, safeRequestId, STATUS_QUEUE_ERROR,
                    cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage()));
            log(Log.ERROR, TAG, "CONTROLLED IMPORT failed before/while queueing: request="
                    + safeRequestId + " uri=" + uri, cause);
        }
    }

    private void reportImportResult(
            Context context,
            String requestId,
            String status,
            String message
    ) {
        try {
            Bundle result = new Bundle();
            result.putString(KEY_REQUEST_ID, requestId);
            result.putString(KEY_STATUS, status);
            result.putString(KEY_MESSAGE, message == null ? "" : message);
            result.putLong(KEY_RESULT_AT, System.currentTimeMillis());
            context.getContentResolver().call(
                    CONTROL_AUTHORITY,
                    METHOD_REPORT_RESULT,
                    null,
                    result
            );
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Unable to report import result to HyperOS TDK provider: request="
                    + requestId + " status=" + status, error);
        }
    }

    private void finishActiveRequest(String requestId) {
        if (requestId != null && requestId.equals(activeRequestId)) {
            activeRequestId = null;
        }
        cleanupActiveStagedFile();
    }

    private void validateRequestMetadata(String displayName, Uri uri) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("MTZ display name is empty");
        }
        if (!displayName.toLowerCase(Locale.ROOT).endsWith(".mtz")) {
            throw new IllegalArgumentException("Selected file does not end with .mtz: " + displayName);
        }
        if (uri == null) {
            throw new IllegalArgumentException("MTZ content URI is missing");
        }
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            throw new SecurityException("Only content:// MTZ URIs are accepted");
        }
    }

    private File stageMtzIntoThemeManagerCache(Context context, String requestId, Uri uri)
            throws Exception {
        File root = new File(context.getCacheDir(), "hyperos-tdk-import");
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IllegalStateException("Unable to create Theme Manager staging directory: " + root);
        }

        String safeFileName = requestId.replaceAll("[^A-Za-z0-9._-]", "_") + ".mtz";
        File outputFile = new File(root, safeFileName).getCanonicalFile();
        if (!outputFile.getParentFile().equals(root.getCanonicalFile())) {
            throw new SecurityException("Invalid staging target");
        }

        long total = 0L;
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(outputFile, false)) {
            if (input == null) {
                throw new SecurityException("Theme Manager ContentResolver could not open granted MTZ URI");
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_MTZ_BYTES) {
                    throw new IllegalArgumentException("MTZ exceeds maximum allowed size: " + total);
                }
                output.write(buffer, 0, read);
            }
            output.flush();
        } catch (Throwable error) {
            if (outputFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outputFile.delete();
            }
            throw error;
        }

        if (total < 4L) {
            //noinspection ResultOfMethodCallIgnored
            outputFile.delete();
            throw new IllegalArgumentException("MTZ is too small: " + total);
        }

        log(Log.INFO, TAG, "CONTROLLED IMPORT URI staged in Theme Manager cache: request="
                + requestId + " bytes=" + total + " file=" + outputFile.getAbsolutePath());
        return outputFile;
    }

    private void validateStagedMtz(File file) throws Exception {
        if (!file.exists() || !file.isFile() || !file.canRead()) {
            throw new SecurityException("Staged MTZ is not readable by Theme Manager: " + file);
        }
        if (file.length() < 4L || file.length() > MAX_MTZ_BYTES) {
            throw new IllegalArgumentException("Staged MTZ size is outside allowed range: " + file.length());
        }
        try (FileInputStream input = new FileInputStream(file)) {
            int first = input.read();
            int second = input.read();
            if (first != 'P' || second != 'K') {
                throw new IllegalArgumentException("Staged MTZ is not a ZIP container (missing PK signature)");
            }
        }
    }

    private void cleanupActiveStagedFile() {
        File file = activeStagedFile;
        activeStagedFile = null;
        if (file != null && file.exists()) {
            boolean deleted = file.delete();
            log(Log.INFO, TAG, "CONTROLLED IMPORT staged file cleanup: deleted=" + deleted
                    + " file=" + file.getAbsolutePath());
        }
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
            log(Log.INFO, TAG, "[OK] Import method is present and remains idle until a provider IPC user request: "
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
