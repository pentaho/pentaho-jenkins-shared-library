package vars

import org.hitachivantara.ci.BasePipelineSpecification
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.JenkinsVarRule
import org.hitachivantara.ci.utils.Rules
import org.junit.Rule
import org.junit.rules.RuleChain


class TestStages extends BasePipelineSpecification {
  ConfigurationRule configRule = new ConfigurationRule(this)
  JenkinsVarRule logRule = new JenkinsVarRule(this, 'log')
  JenkinsVarRule utilsRule = new JenkinsVarRule(this, 'utils')
  JenkinsVarRule buildStageRule = new JenkinsVarRule(this, 'buildStage')
  JenkinsVarRule testStageRule = new JenkinsVarRule(this, 'testStage')

  @Rule
  public RuleChain ruleChain = Rules
    .getCommonRules(this)
    .around(logRule)
    .around(configRule)
    .around(utilsRule)
    .around(buildStageRule)
    .around(testStageRule)

  def "test parallel items defines timeouts"() {
    given:
      registerAllowedMethod('junit', [Map.class], {})
      registerAllowedMethod('retry', [Integer.class, Closure.class], {})
      registerAllowedMethod('timeout', [Integer.class, Closure.class], {})

      JobItem jobItem = Mock(JobItem) {
        getTimeout() >> 10
      }
    when:
      buildStageRule.var.getItemClosure(configRule.buildData, jobItem).call()
      testStageRule.var.getItemClosure(configRule.buildData, jobItem).call()
    then:
      methodCallCount('timeout') == 2
  }

}
