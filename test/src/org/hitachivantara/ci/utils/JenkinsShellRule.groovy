package org.hitachivantara.ci.utils

import hudson.AbortException
import org.hitachivantara.ci.BasePipelineSpecification
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

import java.util.regex.Pattern

class JenkinsShellRule implements TestRule {
  BasePipelineSpecification specification
  List<String> cmds = []
  Map returnValues = [:]
  List failingCommands = []

  JenkinsShellRule(BasePipelineSpecification specification) {
    this.specification = specification
  }

  void setReturnValue(cmd, value) {
    returnValues.put(cmd, value)
  }

  void failExecution(script) {
    failingCommands.add(script)
  }

  @Override
  Statement apply(Statement base, Description description) {
    return statement(base)
  }

  Statement statement(Statement base) {
    new Statement() {
      @Override
      void evaluate() throws Throwable {
        registerShellStringCommand()
        registerShellMapCommand()
        base.evaluate()
      }
    }
  }

  static private String unify(String s) {
    s.replaceAll(/\s+/, " ").trim()
  }

  private void registerShellStringCommand() {
    specification.helper.registerAllowedMethod("sh", [String.class], { String command ->
      String unifiedScript = unify(command)
      cmds.add(unifiedScript)
      validateScript(unifiedScript)
    })
  }

  private void registerShellMapCommand() {
    specification.helper.registerAllowedMethod("sh", [Map.class], { Map map ->
      String unifiedScript = unify(map.script)
      cmds.add(unifiedScript)

      validateScript(unifiedScript)

      if (map.returnStdout || map.returnStatus) {
        def result = returnValues.findResult { key, value ->
          if ((key instanceof Pattern && unifiedScript =~ key) || unifiedScript == key) return value
        }

        if (result instanceof Closure) result = result() // for dynamic tests
        if (!result && map.returnStatus) result = 0
        return result
      }
    })
  }

  private void validateScript(String script) throws AbortException {
    for (failingCommand in failingCommands) {
      if (failingCommand instanceof Pattern && script =~ failingCommand) {
        throw new AbortException("script returned exit code 1")
      } else if (script == failingCommand) {
        throw new AbortException("script returned exit code 1")
      }
    }
  }
}
