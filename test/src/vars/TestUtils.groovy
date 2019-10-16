package vars

import hudson.model.Result
import jenkins.model.CauseOfInterruption
import org.hitachivantara.ci.BasePipelineSpecification
import org.hitachivantara.ci.StageException
import org.hitachivantara.ci.build.PipelineSignalException
import org.hitachivantara.ci.utils.JenkinsLoggingRule
import org.hitachivantara.ci.utils.Rules
import org.hitachivantara.ci.utils.JenkinsVarRule
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.junit.Rule
import org.junit.rules.RuleChain

import static org.junit.Assert.fail

class TestUtils extends BasePipelineSpecification {
  JenkinsLoggingRule loggingRule = new JenkinsLoggingRule(this)
  JenkinsVarRule utilsRule = new JenkinsVarRule(this, 'utils')
  JenkinsVarRule jobRule = new JenkinsVarRule(this, 'job')
  JenkinsVarRule logRule = new JenkinsVarRule(this, 'log')

  @Rule
  public RuleChain ruleChain = Rules
    .getCommonRules(this)
    .around(loggingRule)
    .around(utilsRule)
    .around(jobRule)
    .around(logRule)

  def "test createStageEmpty"() {
    given:
      String stageName = ''
      registerAllowedMethod('stage', [String.class, Closure.class], { String name, Closure body ->
        stageName = name
        body()
      })
      utilsRule.var.createStageEmpty('Empty Stage')
    expect:
      stageName == 'Empty Stage'
      loggingRule.infoMessages
  }

  def "test create stage error"() {
    setup:
      String stageName = ''
      registerAllowedMethod('stage', [String.class, Closure.class], { String name, Closure body ->
        stageName = name
        body()
      })
    when:
      utilsRule.var.createStageError('Stage Error', 'Error message')
    then:
      def ex = thrown(StageException)
      ex.message == 'Error message'
      stageName == 'Stage Error'
  }

  def "test create stage skipped"() {
    setup:
      GroovySpy(Utils, global: true)
      String stageName = ''
      registerAllowedMethod('stage', [String.class, Closure.class], { String name, Closure body ->
        stageName = name
        body()
      })
    when:
      utilsRule.var.createStageSkipped('Build')
    then:
      stageName == 'Build'
      1 * Utils.markStageSkippedForConditional(_) >> null
  }

  def "test mark Stage Skipped"() {
    setup:
      utilsRule.var.binding.setVariable('STAGE_NAME', 'Build')
      GroovySpy(Utils, global: true)
    when:
      utilsRule.var.markStageSkipped()
    then:
      1 * Utils.markStageSkippedForConditional(_) >> null
  }

  def "test setNode wraps a step node around the callers"() {
    setup:
      int nodes = 0
      registerAllowedMethod('node', [String, Closure], { String name, Closure body ->
        nodes++
        body()
      })
    when:
      Map<String, Closure> entries = utilsRule.var.setNode(map)
    and:
      entries.values()*.call()

    then:
      entries.size() == map.size()
      nodes == expectedNodes

    where:
      map                                    || expectedNodes
      ['p1': { -> null }, 'p2': { -> null }] || 2
      ['p1': { -> null }]                    || 0

  }

  def "test error handling"() {
    setup:
      boolean finallyCalled = false
      def finallyHandler = { -> finallyCalled = true }
    when:
      utilsRule.var.handleError(body, handler, finallyHandler)
    then:
      noExceptionThrown()
      finallyCalled
    where:
      body                           | handler
      ({ -> true })                  | ({ ex -> fail("handler was triggered but should've not!") })
      ({ -> throw new Exception() }) | ({ ex -> pipeline.assertJobStatusSuccess() })
  }

  def "test error handling on run interruption"() {
    setup:
      def handler = { ex -> fail("handler was triggered but should've not!") }
    when:
      utilsRule.var.handleError(body, handler)
    then: "Interrupted exceptions should be thrown and not handled"
      thrown(expectedException)
      if (expectedException in PipelineSignalException) {
        pipeline.assertJobStatusFailure()
        assert loggingRule.errorMessages
      }
    where:
      body                                                                                    | expectedException
      ({ -> throw new FlowInterruptedException(Result.ABORTED, [:] as CauseOfInterruption) }) | FlowInterruptedException
      ({ -> throw new PipelineSignalException(Result.FAILURE, 'I failed!') })                 | PipelineSignalException

  }
}
