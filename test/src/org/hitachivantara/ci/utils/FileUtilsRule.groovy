package org.hitachivantara.ci.utils

import hudson.FilePath
import org.hitachivantara.ci.BasePipelineSpecification
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class FileUtilsRule implements TestRule {
  BasePipelineSpecification specification

  FileUtilsRule(BasePipelineSpecification specification) {
    this.specification = specification
  }

  @Override
  Statement apply(Statement statement, Description description) {
    specification.registerAllowedMethod('getContext', [Object], { Object c ->
      if (FilePath in c) {
        return new FilePath(null, '.')
      }
      return null
    })
    specification.registerAllowedMethod('fileExists', [String], { String path ->
      return new File(path).exists()
    })
    return statement
  }
}
