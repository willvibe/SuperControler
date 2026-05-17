package com.yourapp.remotectrl.controlled

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.yourapp.remotectrl.root.RootManager
import java.lang.reflect.Method

object MediaProjectionHelper {

    private const val TAG = "ControlledService"
    private const val PREFS_NAME = "media_projection_prefs"
    private const val KEY_HAS_GRANTED = "has_granted"

    @Volatile
    private var cachedProjection: MediaProjection? = null

    @Volatile
    private var cachedResultCode: Int = 0

    @Volatile
    private var cachedResultData: Intent? = null

    @Volatile
    private var projectionCallback: MediaProjection.Callback? = null

    @Volatile
    private var hiddenApiBypassed = false

    @Volatile
    private var permissionsGranted = false

    fun hasGrantedBefore(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAS_GRANTED, false)
    }

    fun markGranted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAS_GRANTED, true)
            .apply()
    }

    fun savePermission(resultCode: Int, data: Intent) {
        cachedResultCode = resultCode
        cachedResultData = data
        Log.i(TAG, "Permission cached: resultCode=$resultCode (RESULT_OK=${resultCode == -1})")
    }

    fun hasCachedPermission(): Boolean {
        return cachedResultCode == -1 && cachedResultData != null
    }

    fun clearAll(context: Context) {
        try {
            cachedProjection?.stop()
        } catch (_: Exception) {}
        cachedProjection = null
        cachedResultCode = 0
        cachedResultData = null
        projectionCallback = null
        permissionsGranted = false
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_HAS_GRANTED)
            .apply()
        Log.i(TAG, "All permission data cleared")
    }

    fun getCachedProjection(): MediaProjection? = cachedProjection

    fun getCachedProjectionData(): Intent? = cachedResultData

    fun isProjectionValid(): Boolean = cachedProjection != null

    fun tryGetProjection(context: Context): MediaProjection? {
        cachedProjection?.let {
            Log.i(TAG, "Reusing cached MediaProjection object")
            return it
        }

        val code = cachedResultCode
        val data = cachedResultData
        if (code == -1 && data != null) {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            return try {
                val projection = mpm.getMediaProjection(code, data)
                if (projection != null) {
                    cachedProjection = projection
                    registerProjectionCallback(projection)
                    Log.i(TAG, "Successfully created projection from cached data")
                } else {
                    Log.w(TAG, "getMediaProjection returned null, token expired")
                    cachedResultCode = 0
                    cachedResultData = null
                }
                projection
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create projection from cached data: ${e.message}")
                cachedResultCode = 0
                cachedResultData = null
                null
            }
        }

        Log.w(TAG, "No cached permission data available (code=$code, data=${data != null})")
        return null
    }

    private fun registerProjectionCallback(projection: MediaProjection) {
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system")
                cachedProjection = null
                cachedResultCode = 0
                cachedResultData = null
            }
        }
        projection.registerCallback(projectionCallback!!, null)
    }

    fun bypassHiddenApiRestrictions(): Boolean {
        if (hiddenApiBypassed) return true

        if (RootManager.isRootAvailable()) {
            Log.i(TAG, "HiddenAPI: Setting hidden_api_policy via root...")
            val results = Shell.cmd(
                "settings put global hidden_api_policy 1",
                "settings put global hidden_api_policy_p 1",
                "settings put global hidden_api_blacklist_exemptions \"L\"",
                "setprop persist.sys.hidden_api_policy 1 2>/dev/null || true"
            ).exec()
            for ((idx, result) in results.out.withIndex()) {
                Log.i(TAG, "HiddenAPI: policy cmd[$idx]: $result")
            }
            Log.i(TAG, "HiddenAPI: Policy commands executed, success=${results.isSuccess}")
        }

        try {
            Log.i(TAG, "HiddenAPI: Trying direct reflection bypass...")
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
            val vmRuntime = getRuntime.invoke(null)

            val exemptMethod = vmRuntimeClass.getDeclaredMethod(
                "setHiddenApiExemptions", Array<String>::class.java
            )
            exemptMethod.isAccessible = true
            exemptMethod.invoke(vmRuntime, arrayOf("L"))

            hiddenApiBypassed = true
            Log.i(TAG, "HiddenAPI: Direct bypass succeeded!")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "HiddenAPI: Direct bypass failed: ${e.javaClass.simpleName} - ${e.message}")
        }

        try {
            Log.i(TAG, "HiddenAPI: Trying meta-reflection bypass...")
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val classGetDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, Class.forName("[Ljava.lang.Class;")
            )

            val getRuntimeMethod = classGetDeclaredMethod.invoke(
                vmRuntimeClass, "getRuntime", null
            ) as? Method
            if (getRuntimeMethod == null) {
                Log.e(TAG, "HiddenAPI: getRuntime method is null")
                return false
            }
            val vmRuntime = getRuntimeMethod.invoke(null)
            if (vmRuntime == null) {
                Log.e(TAG, "HiddenAPI: VMRuntime instance is null")
                return false
            }

            val paramTypes = arrayOfNulls<Class<*>>(1)
            paramTypes[0] = Array<String>::class.java
            val exemptMethod = classGetDeclaredMethod.invoke(
                vmRuntimeClass, "setHiddenApiExemptions", paramTypes
            ) as? Method

            if (exemptMethod != null) {
                exemptMethod.isAccessible = true
                exemptMethod.invoke(vmRuntime, arrayOf("L"))
                hiddenApiBypassed = true
                Log.i(TAG, "HiddenAPI: Meta-reflection bypass succeeded!")
                return true
            } else {
                Log.e(TAG, "HiddenAPI: setHiddenApiExemptions method is null")
            }
        } catch (e: Exception) {
            Log.w(TAG, "HiddenAPI: Meta-reflection failed: ${e.javaClass.simpleName} - ${e.message}")
        }

        try {
            Log.i(TAG, "HiddenAPI: Trying getDeclaredMethods listing...")
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
            val vmRuntime = getRuntime.invoke(null)

            for (m in vmRuntimeClass.declaredMethods) {
                val name = m.name
                val params = m.parameterTypes.map { it.simpleName }
                if (name.contains("hidden", ignoreCase = true) ||
                    name.contains("exempt", ignoreCase = true)) {
                    Log.i(TAG, "HiddenAPI: Found candidate: $name($params)")
                    try {
                        m.isAccessible = true
                        if (m.parameterTypes.isNotEmpty() &&
                            m.parameterTypes[0] == Array<String>::class.java) {
                            m.invoke(vmRuntime, arrayOf("L"))
                            hiddenApiBypassed = true
                            Log.i(TAG, "HiddenAPI: Bypass succeeded via $name!")
                            return true
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "HiddenAPI: $name failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HiddenAPI: Listing failed: ${e.message}")
        }

        Log.w(TAG, "HiddenAPI: All bypass methods failed")
        return false
    }

    fun grantAllRootPermissions(context: Context): Boolean {
        if (!RootManager.isRootAvailable()) {
            Log.w(TAG, "grantAllRootPermissions: Root not available")
            return false
        }

        if (permissionsGranted) {
            Log.i(TAG, "Root permissions already granted")
            return true
        }

        val packageName = context.packageName
        val uid = context.applicationInfo.uid

        val commands = listOf(
            "settings put global hidden_api_policy 1" to "HIDDEN_API_POLICY",
            "settings put global hidden_api_policy_p 1" to "HIDDEN_API_POLICY_P",
            "settings put global hidden_api_blacklist_exemptions \"L\"" to "HIDDEN_API_EXEMPTIONS",
            "appops set $packageName PROJECT_MEDIA allow" to "PROJECT_MEDIA",
            "appops set --uid $uid PROJECT_MEDIA allow" to "PROJECT_MEDIA(uid)",
            "cmd appops set $packageName PROJECT_MEDIA allow" to "PROJECT_MEDIA(cmd)",
            "pm grant $packageName android.permission.MANAGE_MEDIA_PROJECTION 2>/dev/null || true" to "MANAGE_MEDIA_PROJECTION",
            "pm grant $packageName android.permission.RECORD_AUDIO 2>/dev/null || true" to "RECORD_AUDIO",
            "appops set $packageName RUN_IN_BACKGROUND allow" to "RUN_IN_BACKGROUND",
            "appops set $packageName RUN_ANY_IN_BACKGROUND allow" to "RUN_ANY_IN_BACKGROUND",
            "dumpsys deviceidle whitelist +$packageName 2>/dev/null || true" to "DEVICE_IDLE_WHITELIST"
        )

        var successCount = 0
        for ((cmd, name) in commands) {
            val result = Shell.cmd(cmd).exec()
            val ok = result.isSuccess
            if (ok) successCount++
            val errOutput = result.err.joinToString("").trim()
            if (errOutput.isNotEmpty() && errOutput.contains("Exception", ignoreCase = true)) {
                Log.w(TAG, "Root cmd [$name]: FAILED - ${errOutput.take(100)}")
            } else {
                Log.i(TAG, "Root cmd [$name]: ${if (ok) "OK" else "FAIL"}")
            }
        }

        val success = successCount > 0
        if (success) {
            permissionsGranted = true
            hiddenApiBypassed = false
        }
        Log.i(TAG, "Root permissions: $successCount/${commands.size} succeeded")
        return success
    }

    fun createProjectionViaReflection(context: Context): MediaProjection? {
        if (!RootManager.isRootAvailable()) return null

        bypassHiddenApiRestrictions()

        try {
            Log.i(TAG, "Reflection: Getting MediaProjectionManager fields...")
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            val fields = mpm.javaClass.declaredFields
            Log.i(TAG, "Reflection: Found ${fields.size} fields in MediaProjectionManager")
            for (f in fields) {
                Log.i(TAG, "Reflection: Field: ${f.name} (${f.type.simpleName})")
            }

            var mService: Any? = null
            for (field in fields) {
                try {
                    field.isAccessible = true
                    val value = field.get(mpm)
                    if (value != null) {
                        Log.i(TAG, "Reflection: Field '${field.name}' = ${value.javaClass.name}")
                        if (value.javaClass.name.contains("IMediaProjectionManager") ||
                            value.javaClass.name.contains("Proxy") ||
                            value is IBinder) {
                            mService = value
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Reflection: Field '${field.name}' access failed: ${e.message}")
                }
            }

            if (mService == null) {
                Log.w(TAG, "Reflection: No suitable service field found")
                return null
            }

            Log.i(TAG, "Reflection: Got service = ${mService.javaClass.name}")

            val methods = mService.javaClass.methods.filter { it.name == "createProjection" }
            Log.i(TAG, "Reflection: Found ${methods.size} createProjection methods")

            val uid = context.applicationInfo.uid
            val packageName = context.packageName

            for ((idx, method) in methods.withIndex()) {
                try {
                    val pc = method.parameterCount
                    val args = when (pc) {
                        4 -> arrayOf(uid, packageName, 0, false)
                        5 -> arrayOf(uid, packageName, 0, false, 0)
                        6 -> arrayOf(uid, packageName, 0, false, 0, 0)
                        else -> continue
                    }

                    Log.i(TAG, "Reflection: Trying Method[$idx]($pc)...")
                    val result = method.invoke(mService, *args)

                    if (result != null) {
                        Log.i(TAG, "Reflection: Method[$idx] returned ${result.javaClass.name}")
                        val projection = wrapResultAsProjection(context, result)
                        if (projection != null) {
                            Log.i(TAG, "Reflection: Successfully created MediaProjection!")
                            return projection
                        }
                    } else {
                        Log.w(TAG, "Reflection: Method[$idx] returned null")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Reflection: Method[$idx] failed: ${e.javaClass.simpleName} - ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reflection: Failed: ${e.javaClass.simpleName} - ${e.message}")
        }

        return null
    }

    fun createProjectionViaServiceManager(context: Context): MediaProjection? {
        if (!RootManager.isRootAvailable()) return null

        bypassHiddenApiRestrictions()

        try {
            Log.i(TAG, "ServiceManager: Getting media_projection service...")
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getDeclaredMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "media_projection") as? IBinder

            if (binder == null) {
                Log.e(TAG, "ServiceManager: media_projection service not found")
                return null
            }
            Log.i(TAG, "ServiceManager: Got binder = ${binder.javaClass.name}")

            try {
                val stubClass = Class.forName("android.media.projection.IMediaProjectionManager\$Stub")
                val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
                val manager = asInterface.invoke(null, binder)
                Log.i(TAG, "ServiceManager: Got manager = ${manager.javaClass.name}")

                val methods = manager.javaClass.methods.filter { it.name == "createProjection" }
                Log.i(TAG, "ServiceManager: Found ${methods.size} createProjection methods")

                val uid = context.applicationInfo.uid
                val packageName = context.packageName

                for ((idx, method) in methods.withIndex()) {
                    try {
                        val pc = method.parameterCount
                        val args = when (pc) {
                            4 -> arrayOf(uid, packageName, 0, false)
                            5 -> arrayOf(uid, packageName, 0, false, 0)
                            6 -> arrayOf(uid, packageName, 0, false, 0, 0)
                            else -> continue
                        }

                        Log.i(TAG, "ServiceManager: Trying Method[$idx]($pc)...")
                        val result = method.invoke(manager, *args)

                        if (result != null) {
                            Log.i(TAG, "ServiceManager: Method[$idx] returned ${result.javaClass.name}")
                            val projection = wrapResultAsProjection(context, result)
                            if (projection != null) {
                                Log.i(TAG, "ServiceManager: Successfully created MediaProjection!")
                                return projection
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "ServiceManager: Method[$idx] failed: ${e.javaClass.simpleName} - ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ServiceManager: Stub approach failed: ${e.javaClass.simpleName} - ${e.message}")
            }

            Log.i(TAG, "ServiceManager: Trying direct binder transaction...")
            return createProjectionViaBinderTransaction(context, binder)
        } catch (e: Exception) {
            Log.e(TAG, "ServiceManager: Failed: ${e.javaClass.simpleName} - ${e.message}")
        }

        return null
    }

    private fun createProjectionViaBinderTransaction(context: Context, serviceBinder: IBinder): MediaProjection? {
        try {
            Log.i(TAG, "BinderTransaction: Trying to call createProjection via Parcel...")

            val uid = context.applicationInfo.uid
            val packageName = context.packageName

            val data = android.os.Parcel.obtain()
            val reply = android.os.Parcel.obtain()
            try {
                data.writeInterfaceToken("android.media.projection.IMediaProjectionManager")
                data.writeInt(uid)
                data.writeString(packageName)
                data.writeInt(0)
                data.writeInt(0)

                serviceBinder.transact(1, data, reply, 0)
                reply.readException()

                val binder = reply.readStrongBinder()
                if (binder != null) {
                    Log.i(TAG, "BinderTransaction: Got projection binder = ${binder.javaClass.name}")
                    return wrapBinderAsProjection(context, binder)
                } else {
                    Log.w(TAG, "BinderTransaction: readStrongBinder returned null")
                }
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "BinderTransaction: Failed: ${e.javaClass.simpleName} - ${e.message}")
        }
        return null
    }

    fun createProjectionViaRootServiceCall(context: Context): MediaProjection? {
        if (!RootManager.isRootAvailable()) return null

        try {
            Log.i(TAG, "RootServiceCall: Trying different transaction codes...")

            val uid = context.applicationInfo.uid
            val packageName = context.packageName

            val commands = listOf(
                "service call media_projection 1 i32 $uid s16 $packageName i32 0 i32 0" to "tx1",
                "service call media_projection 1 i32 $uid s16 $packageName i32 0 i32 1" to "tx1_permanent",
                "service call media_projection 2 i32 $uid s16 $packageName i32 0 i32 0" to "tx2"
            )

            for ((cmd, name) in commands) {
                Log.i(TAG, "RootServiceCall: [$name] $cmd")
                val result = Shell.cmd(cmd).exec()
                val output = result.out.joinToString("\n")
                val hasError = output.contains("Exception", ignoreCase = true) ||
                        output.contains("Error", ignoreCase = true) ||
                        output.contains("fffffffe", ignoreCase = false) ||
                        output.contains("ffffffff", ignoreCase = false)
                Log.i(TAG, "RootServiceCall: [$name] error=$hasError, output=${output.take(150)}")
                if (!hasError) {
                    Log.i(TAG, "RootServiceCall: [$name] succeeded!")
                }
            }

            return createProjectionViaServiceManager(context)
        } catch (e: Exception) {
            Log.e(TAG, "RootServiceCall: Failed: ${e.javaClass.simpleName} - ${e.message}")
        }

        return null
    }

    private fun wrapResultAsProjection(context: Context, result: Any): MediaProjection? {
        return when (result) {
            is MediaProjection -> {
                cachedProjection = result
                registerProjectionCallback(result)
                markGranted(context)
                result
            }
            is IBinder -> wrapBinderAsProjection(context, result)
            else -> {
                Log.i(TAG, "wrapResult: type=${result.javaClass.name}")
                tryIInterfaceWrap(context, result)
            }
        }
    }

    private fun wrapBinderAsProjection(context: Context, binder: IBinder): MediaProjection? {
        return try {
            Log.i(TAG, "wrapBinder: Wrapping IBinder...")
            val stubClass = Class.forName("android.media.projection.IMediaProjection\$Stub")
            val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            val iMediaProjection = asInterface.invoke(null, binder) ?: return null
            constructMediaProjection(context, iMediaProjection, binder)
        } catch (e: Exception) {
            Log.e(TAG, "wrapBinder: Failed: ${e.javaClass.simpleName} - ${e.message}")
            null
        }
    }

    private fun tryIInterfaceWrap(context: Context, obj: Any): MediaProjection? {
        return try {
            if (obj.javaClass.name.contains("IMediaProjection") ||
                obj is android.os.IInterface) {
                constructMediaProjection(context, obj, null)
            } else {
                Log.w(TAG, "tryIInterfaceWrap: Cannot wrap ${obj.javaClass.name}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryIInterfaceWrap: Failed: ${e.javaClass.simpleName} - ${e.message}")
            null
        }
    }

    private fun constructMediaProjection(context: Context, iMediaProjection: Any, binder: IBinder?): MediaProjection? {
        val cls = MediaProjection::class.java
        val constructors = cls.declaredConstructors
        Log.i(TAG, "constructMediaProjection: ${constructors.size} constructors found")

        for (ctor in constructors) {
            val p = ctor.parameterTypes
            Log.i(TAG, "constructMediaProjection: Constructor(${p.size}): ${p.map { it.simpleName }}")
        }

        val sorted = constructors.sortedByDescending { it.parameterTypes.size }

        for (ctor in sorted) {
            val p = ctor.parameterTypes
            try {
                ctor.isAccessible = true
                val projection = when {
                    p.size == 3 && p[0] == Context::class.java ->
                        ctor.newInstance(context, iMediaProjection, null)
                    p.size == 3 && p[0] == IBinder::class.java && binder != null ->
                        ctor.newInstance(binder, iMediaProjection, null)
                    p.size == 2 && p[0] == Context::class.java ->
                        ctor.newInstance(context, iMediaProjection)
                    p.size == 2 && p[0] == IBinder::class.java && binder != null ->
                        ctor.newInstance(binder, iMediaProjection)
                    p.size == 1 ->
                        ctor.newInstance(iMediaProjection)
                    else -> continue
                }

                if (projection is MediaProjection) {
                    cachedProjection = projection
                    registerProjectionCallback(projection)
                    markGranted(context)
                    Log.i(TAG, "constructMediaProjection: Success via constructor(${p.size})!")
                    return projection
                }
            } catch (e: Exception) {
                Log.w(TAG, "constructMediaProjection: Constructor(${p.size}) failed: ${e.javaClass.simpleName} - ${e.message}")
            }
        }

        Log.e(TAG, "constructMediaProjection: No suitable constructor found")
        return null
    }

    fun tryAllMethods(context: Context): MediaProjection? {
        Log.i(TAG, "=== tryAllMethods() START ===")

        tryGetProjection(context)?.let {
            Log.i(TAG, "=== tryAllMethods() CACHED projection succeeded ===")
            return it
        }

        Log.i(TAG, "Step 1: Granting root permissions + setting hidden API policy...")
        grantAllRootPermissions(context)

        Log.i(TAG, "Step 2: Bypassing hidden API restrictions...")
        val apiBypassed = bypassHiddenApiRestrictions()
        Log.i(TAG, "Step 2 result: Hidden API bypassed = $apiBypassed")

        Log.i(TAG, "Step 3: Trying reflection via MediaProjectionManager fields...")
        createProjectionViaReflection(context)?.let {
            Log.i(TAG, "=== tryAllMethods() REFLECTION succeeded ===")
            return it
        }

        Log.i(TAG, "Step 4: Trying reflection via ServiceManager...")
        createProjectionViaServiceManager(context)?.let {
            Log.i(TAG, "=== tryAllMethods() SERVICE_MANAGER succeeded ===")
            return it
        }

        Log.i(TAG, "Step 5: Trying root service call...")
        createProjectionViaRootServiceCall(context)?.let {
            Log.i(TAG, "=== tryAllMethods() ROOT_SERVICE_CALL succeeded ===")
            return it
        }

        Log.w(TAG, "=== tryAllMethods() ALL METHODS FAILED ===")
        return null
    }
}

object MediaProjectionHolder {
    private const val TAG = "MediaProjectionHolder"
    private var projection: MediaProjection? = null

    fun set(projection: MediaProjection) {
        this.projection = projection
        Log.i(TAG, "MediaProjection stored")
    }

    fun get(): MediaProjection? = projection

    fun clear() {
        projection?.stop()
        projection = null
        Log.i(TAG, "MediaProjection cleared")
    }
}
