package org.hitachivantara.ci.utils

import org.hitachivantara.ci.BasePipelineSpecification
import org.hitachivantara.ci.LogLevel
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class JenkinsLoggingRule implements TestRule {
  BasePipelineSpecification specification
  Map<LogLevel, List<String>> logs = [:].withDefault {[]}

  JenkinsLoggingRule(BasePipelineSpecification specification) {
    this.specification = specification
  }

  @Override
  Statement apply(Statement base, Description description) {
    return statement(base)
  }

  List<String> getErrorMessages() {
    logs.containsKey(LogLevel.ERROR) ? logs[LogLevel.ERROR] : null
  }

  List<String> getInfoMessages() {
    logs.containsKey(LogLevel.INFO) ? logs[LogLevel.INFO] : null
  }

  List<String> getWarnMessages() {
    logs.containsKey(LogLevel.WARNING) ? logs[LogLevel.WARNING] : null
  }

  List<String> getLines() {
    logs.containsKey(null) ? logs[null] : null
  }

  Statement statement(Statement base) {
    new Statement() {
      @Override
      void evaluate() throws Throwable {
        specification.registerAllowedMethod("echo", [String.class], { String echoInput ->
          def matcher = (echoInput =~/(?s)\[(\w+)](.*?)/)
          if (matcher.matches()) {
            LogLevel level = LogLevel.lookup(matcher.group(1).trim())
            String message = matcher.group(2).trim()
            logs[(level)] << message
          } else {
            logs[null] << echoInput
          }
        })

        base.evaluate()
      }
    }
  }

  @Override
  String toString() {
    def sb = ''<<''
    def printMsg = { List msg ->
      msg.each { sb << "- $it\n" }
    }
    if (errorMessages) {
      sb << "Errors:\n"
      printMsg errorMessages
    }
    if (warnMessages) {
      sb << "Warnings:\n"
      printMsg warnMessages
    }
    if (infoMessages) {
      sb << "Infos:\n"
      printMsg infoMessages
    }
    sb.toString()
  }
}
