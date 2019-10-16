package org.hitachivantara.ci

import com.lesfurets.jenkins.unit.BasePipelineTest
import com.lesfurets.jenkins.unit.PipelineTestHelper
import org.hitachivantara.ci.utils.BasePipelineTestContext
import org.hitachivantara.ci.utils.LibraryTestListener
import spock.lang.Specification

abstract class BasePipelineSpecification extends Specification {
  static BasePipeline pipeline = new BasePipeline()

  @Delegate
  static PipelineTestHelper helper = pipeline.helper
  static Script mockScript

  void setupSpec() {
    pipeline.scriptRoots += "vars/"
    pipeline.setUp()

    LibraryTestListener.registerDefaultAllowedMethods(helper)
    LibraryTestListener.START_TRACKING = true
    mockScript = BasePipelineTestContext.testScript(pipeline.binding)
  }

  void setup() throws Exception {
    pipeline.binding.setVariable('currentBuild', [result: 'SUCCESS', currentResult: 'SUCCESS'])
  }

  void cleanup() {
    pipeline.cleanUp()
    helper.clearCallStack()
    helper.clearAllowedMethodCallbacks(LibraryTestListener.TRACKED_METHODS)
    LibraryTestListener.TRACKED_METHODS.clear()

    helper.putAllAllowedMethodCallbacks(LibraryTestListener.RESTORE_METHODS)
    LibraryTestListener.RESTORE_METHODS.clear()
  }

  void cleanupSpec() {
    LibraryTestListener.START_TRACKING = false
  }

  static class BasePipeline extends BasePipelineTest {
    BasePipeline() {
      helper = LibraryTestListener.helper
    }

    @Override
    void setUp() throws Exception {
      helper.with {
        it.scriptRoots = this.scriptRoots
        it.scriptExtension = this.scriptExtension
        it.baseClassloader = this.baseClassLoader
        it.scriptBaseClass = Script.class
        it.imports += this.imports
        it.baseScriptRoot = this.baseScriptRoot
        return it
      }.init()
    }

    void cleanUp() {
      binding = new Binding()
    }
  }

}
