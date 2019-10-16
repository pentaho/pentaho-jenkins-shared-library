package org.hitachivantara.ci.utils

import org.hitachivantara.ci.BasePipelineSpecification
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class JenkinsVarRule implements TestRule {
  BasePipelineSpecification specification
  Script var
  String varName

  JenkinsVarRule(BasePipelineSpecification specification, String varName) {
    this.specification = specification
    this.varName = varName
  }

  @Override
  Statement apply(Statement base, Description description) {
    new Statement() {
      @Override
      void evaluate() throws Throwable {
        var = specification.pipeline.loadScript("${varName}.groovy")
        specification.pipeline.binding.setVariable(varName, var)
        base.evaluate()
      }
    }
  }

  void setVariable(String name, value) {
    var.binding.setVariable(name, value)
  }
}
