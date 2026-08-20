package com.glorious.hyperostdk.data

import android.os.IBinder
import com.glorious.hyperostdk.model.ThemeInterfaceReflectionResult
import com.glorious.hyperostdk.model.ThemeTransactionInfo

/**
 * Read-only reflection probe for Xiaomi's ThemeService Binder interface.
 *
 * This class never calls an IThemeService method and never performs an IBinder transaction.
 * It only asks the local runtime which classes/method names/transaction-name mappings are visible.
 */
object ThemeInterfaceReflectionProbe {
    private const val DEFAULT_DESCRIPTOR = "miui.content.res.IThemeService"
    private const val MAX_TRANSACTION_CODE = 256

    fun probe(interfaceDescriptor: String?): ThemeInterfaceReflectionResult {
        val descriptor = interfaceDescriptor?.takeIf { it.isNotBlank() } ?: DEFAULT_DESCRIPTOR
        val errors = mutableListOf<String>()

        val interfaceClass = loadClass(descriptor, "interface", errors)
        val stubClassName = "$descriptor\$Stub"
        val stubClass = loadClass(stubClassName, "stub", errors)

        val interfaceMethods = interfaceClass?.let { clazz ->
            runCatching {
                clazz.declaredMethods
                    .map(::methodSignature)
                    .distinct()
                    .sorted()
            }.onFailure {
                errors += "Interface method enumeration failed: ${it.javaClass.simpleName}: ${it.message}"
            }.getOrDefault(emptyList())
        }.orEmpty()

        val stubMethods = stubClass?.let { clazz ->
            runCatching {
                clazz.declaredMethods
                    .map(::methodSignature)
                    .distinct()
                    .sorted()
            }.onFailure {
                errors += "Stub method enumeration failed: ${it.javaClass.simpleName}: ${it.message}"
            }.getOrDefault(emptyList())
        }.orEmpty()

        val transactionFields = stubClass?.let { clazz ->
            runCatching {
                clazz.declaredFields
                    .filter { it.name.startsWith("TRANSACTION_") }
                    .map { field ->
                        val methodName = field.name.removePrefix("TRANSACTION_")
                        val code = runCatching {
                            @Suppress("DEPRECATION")
                            field.isAccessible = true
                            field.getInt(null)
                        }.getOrNull()
                        if (code == null) "$methodName = <value blocked>" else "$methodName = $code"
                    }
                    .sorted()
            }.onFailure {
                errors += "Transaction field enumeration failed: ${it.javaClass.simpleName}: ${it.message}"
            }.getOrDefault(emptyList())
        }.orEmpty()

        val transactionNames = stubClass?.let { clazz ->
            readDefaultTransactionNames(clazz, errors)
        }.orEmpty()

        return ThemeInterfaceReflectionResult(
            descriptor = descriptor,
            interfaceClassLoaded = interfaceClass != null,
            stubClassLoaded = stubClass != null,
            interfaceMethods = interfaceMethods,
            stubMethods = stubMethods,
            transactionFields = transactionFields,
            transactionNames = transactionNames,
            errors = errors
        )
    }

    private fun loadClass(
        className: String,
        label: String,
        errors: MutableList<String>
    ): Class<*>? {
        val attempts = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            ThemeInterfaceReflectionProbe::class.java.classLoader,
            ClassLoader.getSystemClassLoader()
        ).distinct()

        attempts.forEach { loader ->
            val loaded = runCatching { Class.forName(className, false, loader) }.getOrNull()
            if (loaded != null) return loaded
        }

        return runCatching { Class.forName(className) }
            .onFailure {
                errors += "$label class load failed ($className): ${it.javaClass.simpleName}: ${it.message}"
            }
            .getOrNull()
    }

    private fun readDefaultTransactionNames(
        stubClass: Class<*>,
        errors: MutableList<String>
    ): List<ThemeTransactionInfo> {
        val method = runCatching {
            stubClass.declaredMethods.firstOrNull {
                it.name == "getDefaultTransactionName" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }?.also {
                @Suppress("DEPRECATION")
                it.isAccessible = true
            }
        }.onFailure {
            errors += "getDefaultTransactionName lookup failed: ${it.javaClass.simpleName}: ${it.message}"
        }.getOrNull()

        if (method == null) return emptyList()

        val results = mutableListOf<ThemeTransactionInfo>()
        for (code in IBinder.FIRST_CALL_TRANSACTION..MAX_TRANSACTION_CODE) {
            val name = runCatching { method.invoke(null, code) as? String }
                .onFailure {
                    if (results.isEmpty()) {
                        errors += "getDefaultTransactionName invocation blocked: ${it.javaClass.simpleName}: ${it.message}"
                    }
                }
                .getOrNull()
            if (!name.isNullOrBlank()) results += ThemeTransactionInfo(code, name)
        }
        return results.distinctBy { it.code to it.name }
    }

    private fun methodSignature(method: java.lang.reflect.Method): String = runCatching {
        val parameters = method.parameterTypes.joinToString(", ") { it.typeName }
        "${method.returnType.typeName} ${method.name}($parameters)"
    }.getOrElse {
        method.name
    }
}
