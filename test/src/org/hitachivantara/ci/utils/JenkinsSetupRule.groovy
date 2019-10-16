package org.hitachivantara.ci.utils

import com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration
import org.hitachivantara.ci.BasePipelineSpecification
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class JenkinsSetupRule implements TestRule {
  BasePipelineSpecification specification
  LibraryConfiguration library

  JenkinsSetupRule(BasePipelineSpecification specification) {
    this(specification, null)
  }


  JenkinsSetupRule(BasePipelineSpecification specification, LibraryConfiguration configuration) {
    this.specification = specification
    if (configuration) {
      this.library = configuration
    }
  }

  @Override
  Statement apply(Statement base, Description description) {
    return statement(base)
  }

  Statement statement(final Statement base) {
    return new Statement() {
      @Override
      void evaluate() throws Throwable {
        if (library) {
          specification.helper.registerSharedLibrary(library)
        }
        // set jenkins job mock variables
        specification.pipeline.binding.setVariable('env', [
            JOB_NAME    : 'pipeline',
            BUILD_NUMBER: '1',
            BUILD_URL   : 'http://build.url',
        ])

        base.evaluate()
      }
    }
  }
}
