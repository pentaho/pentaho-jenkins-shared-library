package org.hitachivantara.ci

import com.cloudbees.groovy.cps.AbstractGroovyCpsTest
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Path
import java.nio.file.Paths

abstract class CpsSpecification extends Specification {
  class CpsTest extends AbstractGroovyCpsTest {}

  @Shared CpsTest delegate
  @Shared String testSource

  void setCodeSource(Class aClass) {
    Path p = Paths.get('src',[aClass.package.name.split("\\."), aClass.simpleName + '.groovy'].flatten() as String[])
    testSource = p?.toFile()?.text
  }

  abstract Class classForTest()

  def setup() {
    delegate = new CpsTest()
    delegate.setUp()
    setCodeSource(classForTest())
  }

  def evalCPS(String script, Map<String, Object> bindings = [:]) {
    setBinding(bindings)
    String sourceAndScript = appendScript(script)

    def resultInCps = delegate.evalCPSonly(sourceAndScript)
    def resultInStandard = delegate.sh.evaluate(sourceAndScript)
    assert resultInCps == resultInStandard
    resultInCps
  }

  def evalCPSonly(String script, Map<String, Object> bindings = [:]) {
    setBinding(bindings)
    String sourceAndScript = appendScript(script)

    delegate.evalCPSonly(sourceAndScript)
  }

  private String appendScript(String script) {
    "${testSource}\n${script}"
  }

  private void setBinding(Map<String, Object> bindings) {
    Binding binding = (Binding) delegate.binding
    bindings.each { name, value ->
      binding.setVariable(name, value)
    }
  }
}
