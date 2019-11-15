/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import hudson.model.Item
import hudson.model.Run
import jenkins.scm.api.SCMHead
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.scm.SCMData
import org.hitachivantara.ci.utils.GitRule
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.JenkinsLoggingRule
import org.hitachivantara.ci.utils.JenkinsShellRule
import org.hitachivantara.ci.utils.ReplacePropertyRule
import org.hitachivantara.ci.utils.Rules
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import org.junit.Rule
import org.junit.rules.RuleChain
import spock.lang.Unroll

import static org.hitachivantara.ci.config.LibraryProperties.CHANGE_ID
import static org.hitachivantara.ci.config.LibraryProperties.CHANGE_TARGET

class TestScmUtils extends BasePipelineSpecification {
  JenkinsLoggingRule loggingRule = new JenkinsLoggingRule(this)
  JenkinsShellRule shellRule = new JenkinsShellRule(this)
  GitRule gitRule = new GitRule(this)
  ConfigurationRule configRule = new ConfigurationRule(this)
  ReplacePropertyRule headByItemMetaClass = new ReplacePropertyRule()
  ReplacePropertyRule scmUtilsMetaClass = new ReplacePropertyRule()

  @Rule
  public RuleChain ruleChain = Rules
    .getCommonRules(this)
    .around(loggingRule)
    .around(shellRule)
    .around(gitRule)
    .around(configRule)
    .around(headByItemMetaClass)
    .around(scmUtilsMetaClass)

  def setup() {
    BuildData.instance.setLogLevel(LogLevel.DEBUG)
  }

  @Unroll
  def "test RefSpec Patterns for branch name: #branchName"() {
    expect:
      with(ScmUtils) {
        verifyAll {
          assert getRemoteRefSpecPattern(branchName) == expectedSrc: "pattern for references on the remote side doesn't match"
          assert getLocalRefSpecPattern(expectedSrc) == expectedDst: "pattern for local references doesn't match"
        }
      }
    where:
      branchName            || expectedSrc           | expectedDst
      'master'              || 'refs/heads/master'   | 'refs/remotes/origin/master'
      'heads/master'        || 'refs/heads/master'   | 'refs/remotes/origin/master'
      'refs/heads/feature1' || 'refs/heads/feature1' | 'refs/remotes/origin/feature1'
      'tags/tag1'           || 'refs/tags/tag1'      | 'refs/remotes/origin/tag1'
      '9f0f5d0'             || 'refs/heads/9f0f5d0'  | 'refs/remotes/origin/9f0f5d0'
      'heads/qa/test'       || 'refs/heads/qa/test'  | 'refs/remotes/origin/qa/test'
  }

  def "test git Refspec is marked for update even if it isn’t a fast-forward"() {
    given:
      JobItem jobItem = Mock(JobItem) {
        getScmBranch() >> (branchName)
      }
    when:
      String[] refSpec = ScmUtils.getRefSpec(jobItem).split()
    then:
      assert refSpec*.startsWith('+').every(): 'Refspec is not marked for update (+<src>:<dst>)'

    where:
      branchName << ['master', 'tags/tag1']
  }

  def "test Local branch name derivation"() {
    expect:
      ScmUtils.deriveLocalBranchName(branchName) == expected
    where:
      branchName          || expected
      'master'            || 'master'
      'refs/heads/branch' || 'branch'
      'heads/feature1'    || 'feature1'
      'tags/tag1'         || 'tag1'
      '9f0f5d0'           || '9f0f5d0'
      'heads/qa/test'     || 'qa/test'
  }

  def "test is inside working tree"() {
    setup:
      shellRule.setReturnValue(~/(git rev-parse --is-inside-work-tree.*?)/, 0)
    when:
      ScmUtils.isInsideWorkTree(mockScript)
    then:
      noExceptionThrown()
  }

  def "test not inside working tree"() {
    setup:
      shellRule.setReturnValue(~/(git rev-parse --is-inside-work-tree.*?)/, 1)
      registerAllowedMethod('pwd', [], { '/non_git_work_dir' })
    when:
      ScmUtils.isInsideWorkTree(mockScript)
    then:
      thrown(ScmException.class)
      assert loggingRule.errorMessages: "doesn't contain description of error"
  }

  def "test checkout"() {
    given:
      configRule.buildProperties = env
      JobItem jobItem = Mock(JobItem) {
        getScmBranch() >> ('master')
        getScmInfo() >> ([:])
        getScmUrl() >> ('test-repo.git')
      }
      List actions = []
      scmUtilsMetaClass.addReplacement(ScmUtils, ['static.prSourceExists': { Script steps -> true }])
    and:
      configRule.buildProperties
      shellRule.setReturnValue('git rev-parse origin/master^{commit}', commit)
      shellRule.setReturnValue("git rev-list $commit ^origin/master", revList.join('\n'))
      mockScript.scm = null
      mockScript.currentBuild = GroovyMock(RunWrapper) {
        getRawBuild() >> GroovyMock(Run) {
          addAction(_) >> ({ a -> actions += a })
        }
      }
    when:
      Map<String, Object> result = ScmUtils.doCheckout(mockScript, jobItem, false)
    then:
      verifyAll {
        result[ScmUtils.GIT_BRANCH] == 'origin/master'
        result[ScmUtils.GIT_COMMIT] == commit
        result[ScmUtils.GIT_PREVIOUS_COMMIT] == previousCommit
        result[ScmUtils.GIT_URL] == 'test-repo.git'
        result[ScmUtils.GIT_REV_LIST] == revList
      }
    and: "an SCMData is present in the build actions"
      actions.findResult { it instanceof SCMData && it.scmUrl == 'test-repo.git' }
    where:
      commit    | revList                | previousCommit  | env
      '0000003' | ['0000003']            | null            | [:]
      '0000002' | ['0000002', '0000001'] | 'origin/master' | [(CHANGE_ID): '1', (CHANGE_TARGET): 'master']
  }

  def "test checkout to previous revision"() {
    setup:
      List<Map> branches
      List<Map> userRemoteConfigs
      JobItem jobItem = Mock(JobItem) {
        getScmBranch() >> ('master')
        getScmRevision() >> ('12ea567')
        getScmInfo() >> ([:])
        getScmUrl() >> ('test-repo.git')
      }
      mockScript.currentBuild = GroovyMock(RunWrapper) {
        getRawBuild() >> GroovyMock(Run)
      }
      registerAllowedMethod('checkout', [Map], { Map m ->
        assert m.scm, 'must contain scm config'
        branches = m.scm.branches
        userRemoteConfigs = m.scm.userRemoteConfigs
      })
    when:
      ScmUtils.doCheckout(mockScript, jobItem, false)
    then:
      branches[0].name == '12ea567'
      userRemoteConfigs[0].refspec == '+refs/heads/master:refs/remotes/origin/master'
  }

  def "test getCommitLog"() {
    setup:
      GroovySpy(ScmUtils, global: true)
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getRawBuild() >> GroovyMock(Run)
      })

      JobItem jobItem = new JobItem('scmUrl': 'https://github.com/example/project.git', scmBranch: 'master')
      gitRule.addCommitLog('0000001', null,'pom.xml')
      gitRule.addCommitLog('0000002', null,'src/myproject/Foo.groovy')
      gitRule.addCommitLog('0000003', null,'src/myproject/Bar.groovy', 'src/myproject/Foo.groovy')

    when:
      List<Map<String, Object>> results = ScmUtils.getCommitLog(mockScript, jobItem)
    then:
      verifyAll {
        results.each { m ->
          assert m.get(ScmUtils.COMMIT_ID)
          assert m.get(ScmUtils.COMMIT_TITLE)
          assert m.get(ScmUtils.COMMIT_URL)
          assert m.get(ScmUtils.COMMIT_AUTHOR)
          assert m.get(ScmUtils.COMMIT_COMMENT)
          assert m.get(ScmUtils.COMMIT_DATE)
          assert m.get(ScmUtils.COMMIT_PATHS)
        }
      }
    and:
      1 * ScmUtils.getGitClient(_, _, _) >> null
      1 * ScmUtils.filterChangelogRevisions(_, _) >> gitRule.commitLogInputStream
  }

  def "test PR checkout exception"() {
    given:
      configRule.buildProperties = [(CHANGE_ID): '1', (CHANGE_TARGET): 'master']
      JobItem jobItem = Mock(JobItem) {
        getScmBranch() >> ('master')
        getScmInfo() >> ([:])
        getScmUrl() >> ('test-repo.git')
      }
      headByItemMetaClass.addReplacement(SCMHead.HeadByItem, ['static.findHead': { Item item ->
        if (!returnsInstance) {
          return null
        }
        return new PullRequestSCMHead('PR-1', owner, repo, branch, 1, null, null, null)
      }])
      mockScript.scm = null
      mockScript.currentBuild = GroovyMock(RunWrapper) {
        getRawBuild() >> GroovyMock(Run)
      }
    when:
      ScmUtils.doCheckout(mockScript, jobItem, false)
    then:
      thrown(ScmException)
    where:
      owner      | repo       | branch     | returnsInstance
      null       | null       | null       | false
      null       | null       | null       | true
      null       | 'not-null' | null       | true
      'not-null' | null       | null       | true
      null       | null       | 'not-null' | true
  }

}
