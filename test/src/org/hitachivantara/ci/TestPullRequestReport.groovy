package org.hitachivantara.ci

import hudson.model.AbstractBuild
import hudson.tasks.test.AbstractTestResultAction
import org.hitachivantara.ci.github.GitHubManager
import org.hitachivantara.ci.report.PullRequestReport
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.ReplacePropertyRule
import org.hitachivantara.ci.utils.Rules
import org.junit.Rule
import org.junit.rules.RuleChain
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

class TestPullRequestReport extends BasePipelineSpecification {
  ConfigurationRule configRule = new ConfigurationRule(this)

  @Rule
  RuleChain rules = Rules.getCommonRules(this)
    .around(configRule)
    .around(new ReplacePropertyRule((GitHubManager): ['static.getSteps': { -> mockScript }]))

  private def mockTestResultAction(Map binding = [:]) {
    GroovyMock(AbstractTestResultAction) {
      getFailCount() >> binding.get('failCount', 2)
      getTotalCount() >> binding.get('totalCount', 5)
      getSkipCount() >> binding.get('skipCount', 1)
      getFailedTests() >> binding.get('failedTests', [[:]])
      getUrlName() >> 'testReport'
      asBoolean() >> true
    }
  }

  def setup() {
    registerAllowedMethod('libraryResource', [Map], null)
  }

  def "test pullrequest template bindings"() {
    setup:
      registerAllowedMethod('resolveTemplate', [Map], { Map params ->
        assert params.parameters.command == 'mvn clean goal'
        assert params.parameters.duration
        assert params.parameters.hasTests == true
        assert params.parameters.totalCount == 5
        assert params.parameters.failCount == 2
        assert params.parameters.skipCount == 1
        assert params.parameters.absoluteUrl == 'jenkins.url/testReport'
        assert params.parameters.failedTests
      })
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url/'
        getRawBuild() >> Mock(AbstractBuild) {
          getAction(_) >> { Class clazz ->
            if (clazz.simpleName == 'AbstractTestResultAction')
              return mockTestResultAction()
          }
        }
        asBoolean() >> true
        getDuration() >> {
          return (1000 * 60 * 2) + (1000 * 30) // 2 minutes and 30 seconds
        }
      })
      configRule.buildData.executionCommand = 'mvn clean goal'
      PullRequestReport report = new PullRequestReport(mockScript)

    when:
      report.build(configRule.buildData).send()

    then:
      noExceptionThrown()
  }

  def "test no tests run still comments a pr"() {
    setup:
      registerAllowedMethod('resolveTemplate', [Map], { Map params ->
        assert params.parameters.command == 'mvn clean goal'
        assert params.parameters.duration
        assert params.parameters.hasTests == false
      })
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url/'
        getRawBuild() >> Mock(AbstractBuild) {
          getAction(_) >> { Class clazz ->
            if (clazz.simpleName == 'AbstractTestResultAction')
              return null
          }
        }
        asBoolean() >> true
        getDuration() >> {
          return (1000 * 60 * 2) + (1000 * 30) // 2 minutes and 30 seconds
        }
      })
      configRule.buildData.executionCommand = 'mvn clean goal'
      PullRequestReport report = new PullRequestReport(mockScript)

    when:
      report.build(configRule.buildData).send()

    then:
      noExceptionThrown()
  }
}