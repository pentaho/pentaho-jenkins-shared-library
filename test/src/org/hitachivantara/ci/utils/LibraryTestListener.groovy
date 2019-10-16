package org.hitachivantara.ci.utils

import com.lesfurets.jenkins.unit.MethodSignature
import com.lesfurets.jenkins.unit.PipelineTestHelper
import org.spockframework.util.NotThreadSafe

/**
 * Utility class that keeps track of mocked steps for test cleanups.
 *
 */
@NotThreadSafe
class LibraryTestListener {
  static PipelineTestHelper helper = createPipelineTestHelper(new PipelineTestHelperHook().helper)

  static boolean START_TRACKING = false
  static List<MethodSignature> TRACKED_METHODS = []
  static Map<MethodSignature, Closure> RESTORE_METHODS = [:]

  static private PipelineTestHelper createPipelineTestHelper(PipelineTestHelper helper) {
    if (helper) {
      helper.metaClass.invokeMethod = { String name, Object[] args ->
        if (START_TRACKING && name == "registerAllowedMethod") {
          String methodName = args[0]
          List<Class> parameters = args[1] as List<Class>

          MethodSignature key = MethodSignature.method(methodName, parameters.toArray(new Class[parameters.size()]) as Class[])

          TRACKED_METHODS.add(key)
          def existingValue = helper.removeAllowedMethodCallback(key)
          if (!RESTORE_METHODS.containsKey(key)) {
            RESTORE_METHODS.put(key, existingValue)
          }
        }
        def m = delegate.metaClass.getMetaMethod(name, *args)
        return m?.invoke(delegate, *args)
      }
    }
    return helper
  }

  static void registerDefaultAllowedMethods(helper) {
    helper.registerAllowedMethod("stage", [String.class, Closure.class], null)
    helper.registerAllowedMethod("node", [String.class, Closure.class], null)
    helper.registerAllowedMethod("node", [Closure.class], null)
    helper.registerAllowedMethod("sh", [Map.class], null)
    helper.registerAllowedMethod("sh", [String.class], null)
    helper.registerAllowedMethod("checkout", [Map.class], null)
    helper.registerAllowedMethod("echo", [String.class], null)
    helper.registerAllowedMethod("timeout", [Map.class, Closure.class], null)
    helper.registerAllowedMethod("step", [Map.class], null)
    helper.registerAllowedMethod("properties", [List.class], null)
    helper.registerAllowedMethod("dir", [String.class, Closure.class], null)
    helper.registerAllowedMethod("archiveArtifacts", [Map.class], null)
    helper.registerAllowedMethod("junit", [String.class], null)
    helper.registerAllowedMethod("readFile", [String.class], null)

    helper.registerAllowedMethod("unstash", [String.class], null)
    helper.registerAllowedMethod("unstash", [Object.class, String.class], null)
    helper.registerAllowedMethod("stash", [Map.class], null)
    helper.registerAllowedMethod("pwd", [], null)
    helper.registerAllowedMethod("getContext", [Object.class], null)
  }

  static void prepareObjectInterceptors(Object object) {
    object.metaClass.invokeMethod = LibraryTestListener.helper.getMethodInterceptor()
    object.metaClass.static.invokeMethod = LibraryTestListener.helper.getMethodInterceptor()
    object.metaClass.methodMissing = LibraryTestListener.helper.getMethodMissingInterceptor()
  }

  static class PipelineTestHelperHook {
    def helper = new PipelineTestHelper() {
      void clearAllowedMethodCallbacks(Collection<MethodSignature> c = []) {
        allowedMethodCallbacks.keySet().removeAll(c)
      }

      def removeAllowedMethodCallback(MethodSignature key) {
        return allowedMethodCallbacks.remove(key)
      }

      void putAllAllowedMethodCallbacks(Map<MethodSignature, Closure> m) {
        allowedMethodCallbacks.putAll(m)
      }
    }
  }

}
