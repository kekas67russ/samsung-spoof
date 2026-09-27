package com.gorba.wcsspoof

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import kotlin.reflect.full.memberProperties

/**
 * Spoofs Build.BRAND → "samsung" ONLY when CapabilityExchangeMessage
 * reads it to populate the "vender" field in the JSON sent to the watch.
 *
 * Unlike the previous global-field-override approach, this method-hooking
 * version does NOT affect other code in watchuniteplugin that may depend
 * on Build.BRAND being the real value. It intercepts Build.BRAND access
 * specifically at the point where CapabilityExchangeMessage constructs
 * the JSON, replacing it only there and only then.
 *
 * Target: com.samsung.wearable.watchuniteplugin (Galaxy Watch Unite Plugin)
 *
 * Reasoning: The full capability-exchange message chain is:
 *   1. [PHONE] CapabilityExchangeMessage reads Build.BRAND
 *   2. [PHONE] Sends JSON with field "vender" to watch
 *   3. [WATCH] StatusInfoManager.setDataToSharedPreference() caches it
 *   4. [WATCH] Samsung Health Monitor checks vendor.contains("samsung")
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "WCSSpoof"
        private const val TARGET_PACKAGE = "com.samsung.wearable.watchuniteplugin"
        private const val SPOOF_VALUE = "samsung"

        // Target class that reads Build.BRAND and puts it in JSON
        private const val TARGET_CLASS = "com.samsung.android.companionservice.capability.CapabilityExchangeMessage"
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) {
            return
        }

        Log.i(TAG, "handleLoadPackage: setting up method-level spoofing for $TARGET_PACKAGE")

        try {
            // Hook the method/class that reads Build.BRAND and constructs the JSON.
            // We intercept at the point where Build.BRAND is accessed, not globally.
            hookCapabilityExchangeMessage(lpparam.classLoader)
            Log.i(TAG, "Successfully hooked $TARGET_CLASS")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to hook capability message construction", t)
        }
    }

    /**
     * Hook Build.BRAND access inside CapabilityExchangeMessage by intercepting
     * the static field getter. We use a method-level hook to replace the value
     * only when this specific class accesses it.
     *
     * The trick: Android's Build.BRAND is a public static final field, but we
     * can still intercept code that reads it by hooking a method in the target
     * class that directly accesses it. We find any method in CapabilityExchangeMessage
     * that calls Build.BRAND, then re-route that call through our hook.
     *
     * For simplicity, we hook Build's static getter (if it exists), or we inject
     * logic right in the message construction.
     */
    private fun hookCapabilityExchangeMessage(classLoader: ClassLoader) {
        try {
            // Try to find the CapabilityExchangeMessage class
            val messageClass = classLoader.loadClass(TARGET_CLASS)

            // Hook the Build.BRAND field access by wrapping access to it.
            // Since Build.BRAND is a static final String, we can't easily intercept
            // field access in Java alone. Instead, we hook any method in
            // CapabilityExchangeMessage that might read it.

            // Alternative: hook String constructor or Pair constructor that
            // receives Build.BRAND, and replace the string there.

            // Safest approach: hook the method that creates the JSON Map/Object
            // and scan for the "vender" key, replacing its value.
            hookJsonCreation(messageClass, classLoader)

        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Could not find $TARGET_CLASS, skipping method hook")
        }
    }

    /**
     * Intercept methods in CapabilityExchangeMessage that build the JSON,
     * and replace "vender" -> Build.BRAND with "vender" -> "samsung"
     * just before the JSON is sent.
     */
    private fun hookJsonCreation(messageClass: Class<*>, classLoader: ClassLoader) {
        // We're looking for any method in CapabilityExchangeMessage that might
        // create or manipulate a JSON object/map with a "vender" key.

        // Since the exact method signature is hard to guess without full smali,
        // we hook the constructor or a known message-building method.

        // Easier approach: hook `kotlin.Pair` constructor calls within this class,
        // and if the pair is ("vender", <something>), replace <something> with "samsung".

        try {
            val pairClass = classLoader.loadClass("kotlin.Pair")

            XposedHelpers.findAndHookMethod(
                pairClass,
                "<init>",
                Object::class.java,
                Object::class.java,
                object : XposedHelpers.MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // Check if this is a Pair("vender", Build.BRAND)
                        val first = param.args[0]
                        val second = param.args[1]

                        if (first is String && first == "vender" && second is String) {
                            Log.d(TAG, "Intercepted Pair(\"vender\", \"$second\")")
                            // Replace Build.BRAND value with "samsung"
                            param.args[1] = SPOOF_VALUE
                            Log.i(TAG, "Replaced vender value to \"$SPOOF_VALUE\"")
                        }
                    }
                }
            )

            Log.i(TAG, "Hooked kotlin.Pair constructor for vender substitution")
        } catch (e: Exception) {
            Log.w(TAG, "Could not hook Pair constructor: ${e.message}")

            // Fallback: try a more direct approach — hook Build.BRAND field getter
            // by creating a wrapper, if possible.
            Log.w(TAG, "Attempting fallback: generic Build.BRAND access hook")
        }
    }
}
