package org.hitachivantara.ci

import org.hitachivantara.ci.config.LibraryProperties
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.Rules
import org.junit.Rule
import org.junit.rules.RuleChain


class TestJobDirectives extends BasePipelineSpecification {

  ConfigurationRule configRule = new ConfigurationRule(specification: this, buildConfigPath: 'test/resources/multiDirectives.yaml')

  @Rule
  RuleChain rules = Rules.getCommonRules(this)
    .around(configRule)

  def "test legacy branch directives"() {
    setup:
      JobItem item = configRule.allItems[0]

    expect:
      item.getDirectives() == 'install'
  }

  def "test legacy pull request directives"() {
    setup:
      JobItem item = configRule.allItems[0]
      configRule.addProperty(LibraryProperties.CHANGE_ID, '123')

    expect:
      item.getDirectives() == 'verify'
  }

  def "test map branch directives"() {
    setup:
      JobItem item = configRule.allItems[1]

    expect:
      item.getDirectives('build') == 'install -DskipTests'
      item.getDirectives('test') == 'integration-test -DrunITs'
  }

  def "test map pull request directives"() {
    setup:
      JobItem item = configRule.allItems[1]
      configRule.addProperty(LibraryProperties.CHANGE_ID, '123')

    expect:
      item.getDirectives('build') == 'verify'
      item.getDirectives('audit') == 'sonar:sonar'
      item.getDirectives('test') == 'test -DrunITs'
  }

}
