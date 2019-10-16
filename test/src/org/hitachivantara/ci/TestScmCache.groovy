package org.hitachivantara.ci

import hudson.model.Run
import org.hitachivantara.ci.utils.JenkinsShellRule
import org.hitachivantara.ci.utils.Rules
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import org.junit.Rule
import org.junit.rules.RuleChain


class TestScmCache extends BasePipelineSpecification {
  JenkinsShellRule shellRule = new JenkinsShellRule(this)

  @Rule
  public RuleChain ruleChain = Rules
    .getCommonRules(this)
    .around(shellRule)

  def "test checkout using cache"() {
    given:
      List<Map> userRemoteConfigs
      JobItem jobItem = Mock(JobItem) {
        getScmBranch() >> ('master')
        getScmInfo() >> ([:])
        getScmUrl() >> ('https://get-remote/test/test-repo.git')
        getScmCacheUrl() >> ('git://git-cache/test/test_repo.git')
      }
    and:
      shellRule.setReturnValue(~/^git ls-remote (.*)/, { -> existsOnCache ? 0 : 2 })
      shellRule.setReturnValue('git rev-parse origin/master^{commit}', '0000000')
      mockScript.currentBuild = GroovyMock(RunWrapper) {
        getRawBuild() >> GroovyMock(Run)
      }
      registerAllowedMethod('checkout', [Map], { Map m ->
        assert m.scm, 'must contain scm config'
        userRemoteConfigs = m.scm.userRemoteConfigs
      })
    when:
      Map<String, Object> result = ScmUtils.doCheckout(mockScript, jobItem, false)
    then:
      verifyAll {
        result[ScmUtils.GIT_BRANCH] == 'origin/master'
        result[ScmUtils.GIT_COMMIT] == '0000000'
        result[ScmUtils.GIT_URL] == 'https://get-remote/test/test-repo.git'
      }
      userRemoteConfigs[0].url == expectedUrl

    where:
      existsOnCache || expectedUrl
      true          || 'git://git-cache/test/test_repo.git'
      false         || 'https://get-remote/test/test-repo.git'
  }


}
