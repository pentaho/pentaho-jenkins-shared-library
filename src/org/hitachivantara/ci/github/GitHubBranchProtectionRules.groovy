/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.github

import com.cloudbees.groovy.cps.NonCPS

class GitHubBranchProtectionRules implements Serializable {
  String id
  String fullName
  List<BranchProtectionRule> rules

  static GitHubBranchProtectionRules get(String owner, String name) throws GitHubException {
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
    return new GitHubBranchProtectionRules().with {
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
    BranchProtectionRule rule = BranchProtectionRule.create(id, pattern, statusCheckContexts)
    rules.add(rule)
    return rule
  }

  /**
   * Find the Rules that best matches a given branch name.
   * Any full pattern match as higher precedence when resolving.
   * @param branch
   * @return
   */
  List<BranchProtectionRule> findRules(String branch) {
    List rules = rules.findAll { rule -> rule.matches(branch) }
    if (rules?.size() > 1) {
      // from the remaining list of rules, find the one that is a direct match
      // if none, return all matched rules
      for (BranchProtectionRule rule in rules) {
        if (rule.pattern == branch) {
          return [rule]
        }
      }
    }
    return rules
  }

  @NonCPS
  @Override
  String toString() {
    return "${getClass().getName()}(fullName: $fullName, rules: $rules)"
  }
}
