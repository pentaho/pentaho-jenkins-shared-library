/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.github

import com.cloudbees.groovy.cps.NonCPS

class GitHubBranchProtection implements Serializable {
  String id
  String fullName
  List<BranchProtectionRule> rules

  static GitHubBranchProtection get(String owner, String name) throws GitHubException {
    Map result = GitHubManager.execute('''\
      query getBranchProtectionRule($owner: String!, $name: String!) {
        repository(owner: $owner, name: $name) {
          id
          rules: branchProtectionRules(last: 100) {
            nodes {
              id
              pattern
              requiresStatusChecks
              requiredStatusCheckContexts
            }
          }
        }
      }''',
      ['owner': owner, 'name': name]
    )
    return new GitHubBranchProtection().with {
      it.id = result.data.repository.id
      it.fullName = "$owner/$name"
      it.rules = result.data.repository.rules.nodes?.collect { rule ->
        new BranchProtectionRule().with {
          it.id = rule.id
          it.pattern = rule.pattern
          it.requiresStatusChecks = rule.requiresStatusChecks
          it.requiredStatusCheckContexts = rule.requiredStatusCheckContexts
          return it
        }
      }
      return it
    }
  }

  BranchProtectionRule createProtectionRule(String pattern, Set<String> statusCheckContexts) {
    Map result = GitHubManager.execute('''\
      mutation createProtectBranch($repoID: ID!, $pattern: String!, $requiredStatusCheckContexts: [String!]) {
        branchProtection: createBranchProtectionRule(input: {
          repositoryId: $repoID,
          pattern: $pattern,
          requiresStatusChecks: true,
          requiredStatusCheckContexts: $requiredStatusCheckContexts,
        }) 
        {
          rule: branchProtectionRule {
            id
            pattern
            requiredStatusCheckContexts
          }
        }
      }''',
      ['repoID': id, 'pattern': pattern, 'requiredStatusCheckContexts': statusCheckContexts]
    )
    BranchProtectionRule rule = new BranchProtectionRule().with {
      it.id = result.data.branchProtection.rule.id
      it.pattern = result.data.branchProtection.rule.pattern
      it.requiredStatusCheckContexts = result.data.branchProtection.rule.requiredStatusCheckContexts
      return it
    }
    rules.add(rule)
    return rule
  }

  @NonCPS
  @Override
  String toString() {
    return "${getClass().getName()}(fullName: $fullName, rules: $rules)"
  }
}
