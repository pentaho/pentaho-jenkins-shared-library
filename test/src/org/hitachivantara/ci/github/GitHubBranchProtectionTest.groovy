/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.github

import org.hitachivantara.ci.BasePipelineSpecification
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.utils.ReplacePropertyRule
import org.hitachivantara.ci.utils.Rules
import org.junit.Rule
import org.junit.rules.RuleChain

class GitHubBranchProtectionTest extends BasePipelineSpecification {
  ReplacePropertyRule replacements = new ReplacePropertyRule(
    (GitHubManager): [
      'static.getSteps': { -> mockScript },
      'static.execute' : { String query, Map variables -> [:] },
    ]
  )

  @Rule
  public RuleChain ruleChain = Rules
    .getCommonRules(this)
    .around(replacements)

  def "test branch protection pattern matcher"() {
    given:
      BranchProtectionRule rule = new BranchProtectionRule(pattern: pattern)
    expect:
      rule.matches(branch) == expected
    where:
      pattern        | branch        || expected
      '*'            | 'master'      || true
      'master'       | 'master'      || true
      '**/*release*' | '1.0-release' || true
      '*release*'    | '1.0-release' || true
      '**/master'    | 'qa/master'   || true
      'master'       | 'qa/master'   || false
      '*master*'     | 'qa/master'   || false
  }

  def "test updating existing rules to add new managed rule"() {
    given:
      List<BranchProtectionRule> rules = [
        new BranchProtectionRule(pattern: 'master', requiredStatusCheckContexts: ['Other CI Validation']),
        new BranchProtectionRule(pattern: '*release*', requiredStatusCheckContexts: ['Other CI Validation']),
      ]
      GitHubBranchProtection branchProtection = new GitHubBranchProtection(rules: rules)
      JobItem jobItem = Mock(JobItem) {
        getScmBranch() >> ('master')
        getPrStatusLabel() >> ('CI Build')
        getScmInfo() >> (['organization': 'owner', 'repository': 'name'])
        isScmProtectBranch() >> (true)
      }
      replacements.addReplacement(GitHubBranchProtection, ['static.get': { String owner, String name -> branchProtection }])
    when:
      GitHubManager.registerBranchProtectionRule(jobItem)
    then:
      verifyAll {
        branchProtection.rules.size() == 2
        branchProtection.rules[0].pattern == 'master'
        branchProtection.rules[0].requiredStatusCheckContexts.size() == 2
        branchProtection.rules[0].requiredStatusCheckContexts.containsAll(['Other CI Validation', 'CI Build'])
        branchProtection.rules[1].pattern == '*release*'
        branchProtection.rules[1].requiredStatusCheckContexts.size() == 1
        branchProtection.rules[1].requiredStatusCheckContexts.contains('Other CI Validation')

      }
  }

  def "test no action if status check context already exists"() {
    given:
      BranchProtectionRule rule = Spy(new BranchProtectionRule(pattern: 'master', requiresStatusChecks: true, requiredStatusCheckContexts: ['CI Build']))
    when:
      rule.addStatusChecks(['CI Build'] as Set)
    then:
      0 * rule.update()
  }

  def "test status check re-enable"() {
    given:
      BranchProtectionRule rule = Spy(new BranchProtectionRule(pattern: 'master', requiresStatusChecks: false, requiredStatusCheckContexts: []))
    when:
      rule.addStatusChecks(['CI Build'] as Set)
    then:
      1 * rule.update()
      rule.requiresStatusChecks
      rule.requiredStatusCheckContexts.contains('CI Build')
  }

  def "test status check contexts removal"() {
    given:
      BranchProtectionRule rule = Spy(new BranchProtectionRule(pattern: 'master', requiresStatusChecks: true, requiredStatusCheckContexts: contexts))
    when:
      rule.removeStatusChecks(contextsToRemove as Set)
    then:
      1 * rule.update()
      rule.requiresStatusChecks == !expectedContexts.empty
      rule.requiredStatusCheckContexts == expectedContexts as Set
    where:
      contexts               | contextsToRemove       || expectedContexts
      ['Check A', 'Check B'] | ['Check B']            || ['Check A']
      ['Check A', 'Check B'] | ['Check A', 'Check B'] || []
  }

  def "test no action if removing a non existing status check context"() {
    given:
      BranchProtectionRule rule = Spy(new BranchProtectionRule(pattern: 'master', requiresStatusChecks: true, requiredStatusCheckContexts: ['CI Build']))
    when:
      rule.removeStatusChecks(contexts as Set)
    then:
      0 * rule.update()
    where:
      contexts << [['Check A'], []]
  }

  def "test branch rule creation"() {
    given:
      List<BranchProtectionRule> rules = [
        new BranchProtectionRule(pattern: '*release*', requiredStatusCheckContexts: ['Other CI Validation']),
      ]
      GitHubBranchProtection branchProtection = new GitHubBranchProtection(rules: rules)
      JobItem jobItem = Mock(JobItem) {
        getScmBranch() >> ('heads/qa/master')
        getPrStatusLabel() >> ('CI Build')
        getScmInfo() >> (['organization': 'owner', 'repository': 'name'])
        isScmProtectBranch() >> (true)
      }
      replacements.addReplacement(GitHubBranchProtection, ['static.get': { String owner, String name -> branchProtection }])
      replacements.addReplacement(GitHubManager, ['static.execute': { String query, Map variables ->
        [data: [branchProtection: [rule: [id: '1', pattern: 'qa/master', requiredStatusCheckContexts: ['CI Build']]]]]
      }])

    when:
      GitHubManager.registerBranchProtectionRule(jobItem)
    then:
      verifyAll {
        branchProtection.rules.size() == 2
        branchProtection.rules[1].pattern == 'qa/master'
        branchProtection.rules[1].requiredStatusCheckContexts.size() == 1
        branchProtection.rules[1].requiredStatusCheckContexts.contains('CI Build')
      }
  }

  def "test branch rule removal"() {
    given:
      List<BranchProtectionRule> rules = [
        new BranchProtectionRule(pattern: 'master', requiredStatusCheckContexts: ['Other CI Validation', 'CI Build']),
      ]
      GitHubBranchProtection branchProtection = new GitHubBranchProtection(rules: rules)
      JobItem jobItem = Mock(JobItem) {
        getScmBranch() >> ('master')
        getPrStatusLabel() >> ('CI Build')
        getScmInfo() >> (['organization': 'owner', 'repository': 'name'])
        isScmProtectBranch() >> (false)
      }
      replacements.addReplacement(GitHubBranchProtection, ['static.get': { String owner, String name -> branchProtection }])
    when:
      GitHubManager.registerBranchProtectionRule(jobItem)
    then:
      verifyAll {
        branchProtection.rules.size() == 1
        branchProtection.rules[0].pattern == 'master'
        branchProtection.rules[0].requiresStatusChecks
        branchProtection.rules[0].requiredStatusCheckContexts.size() == 1
        !branchProtection.rules[0].requiredStatusCheckContexts.contains('CI Build')
      }
  }

}
