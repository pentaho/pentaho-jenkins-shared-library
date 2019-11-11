/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.github

import org.hitachivantara.ci.BasePipelineSpecification
import org.hitachivantara.ci.utils.ReplacePropertyRule
import org.hitachivantara.ci.utils.Rules
import org.junit.Rule
import org.junit.rules.RuleChain

class GitHubRepositoryTest extends BasePipelineSpecification {
  ReplacePropertyRule replacements = new ReplacePropertyRule((GitHubManager): ['static.getSteps': { -> mockScript }])

  @Rule
  public RuleChain ruleChain = Rules
    .getCommonRules(this)
    .around(replacements)

  GitHubRepository repository = new GitHubRepository('owner', 'name')

  def "test get PullRequest"() {
    given:
      replacements.addReplacement(GitHubManager, [
        'static.execute': { String query, Map variables ->
          [data: [
            repository: [
              pullRequest: [
                id: '1',
                comments: [
                  [nodes: [
                    id: '1',
                    isMinimized: false,
                    viewerDidAuthor: true,
                    minimizedReason: null
                  ]]
                ]
              ]
            ]
          ]]
        }
      ])
    when:
      def pr = repository.getPullRequest(3)
    then:
      verifyAll {
        pr.id == '1'
        pr.number == 3
        pr.fullName == 'owner/name'
        pr.comments.size() == 1
        pr.comments[0].id == '1'
        !pr.comments[0].isMinimized
        pr.comments[0].viewerDidAuthor
      }
  }

  def "test get BranchProtection rule"() {
    given:
      replacements.addReplacement(GitHubManager, [
        'static.execute': { String query, Map variables ->
          [data: [
            repository: [
              id: '1',
              rules: [
                nodes: [
                  [
                    id: '1',
                    pattern: 'master',
                    requiresStatusChecks: true,
                    requiredStatusCheckContexts: ['CI Build']
                  ]
                ]
              ]
            ]
          ]]
        }
      ])
    when:
      def branchProtectionRule = repository.getBranchProtectionRules()
    then:
      verifyAll {
        branchProtectionRule.id == '1'
        branchProtectionRule.fullName == 'owner/name'
        branchProtectionRule.rules.size() == 1
        branchProtectionRule.rules[0].id == '1'
        branchProtectionRule.rules[0].pattern == 'master'
        branchProtectionRule.rules[0].requiresStatusChecks
        branchProtectionRule.rules[0].requiredStatusCheckContexts.contains('CI Build')

      }
  }
}
