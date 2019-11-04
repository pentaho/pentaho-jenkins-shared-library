/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.github

import com.cloudbees.groovy.cps.NonCPS

import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.nio.file.Paths

class BranchProtectionRule implements Serializable {
  String id
  String pattern
  Boolean requiresStatusChecks = Boolean.TRUE
  Set<String> requiredStatusCheckContexts

  @NonCPS boolean matches(String branch) {
    PathMatcher matcher = FileSystems.default.getPathMatcher('glob:' + pattern)
    return matcher.matches(Paths.get(branch)) || matcher.matches(Paths.get('/', branch))
  }

  /**
   * Updates the current branch protection rules
   * If the current status check contexts already exists, it does nothing
   * @param statusCheckContexts checks the be added
   */
  void addStatusChecks(Set<String> statusCheckContexts) {
    if (requiredStatusCheckContexts.addAll(statusCheckContexts)) {
      requiresStatusChecks = Boolean.TRUE
      update()
    }
  }

  void removeStatusChecks(Set<String> statusCheckContexts) {
    if (requiredStatusCheckContexts.removeAll(statusCheckContexts)) {
      requiresStatusChecks = !requiredStatusCheckContexts.empty
      update()
    }
  }

  protected void update() {
    GitHubManager.execute('''\
        mutation updateProtectionRule($ruleID: ID!, $pattern: String, $requiresStatusChecks: Boolean, $requiredStatusCheckContexts: [String!]) {
          updateBranchProtectionRule(input: {
            branchProtectionRuleId: $ruleID,
            pattern: $pattern,
            requiresStatusChecks: $requiresStatusChecks,
            requiredStatusCheckContexts: $requiredStatusCheckContexts
          })
          {
            branchProtectionRule {
              id
              pattern
              requiresStatusChecks
              requiredStatusCheckContexts
            }
          }
        }''',
      [
        'ruleID'                     : id,
        'pattern'                    : pattern,
        'requiresStatusChecks'       : requiresStatusChecks,
        'requiredStatusCheckContexts': requiredStatusCheckContexts,
      ]
    )
  }

  @NonCPS
  @Override
  String toString() {
    return "${getClass().getName()}(pattern: $pattern, requiresStatusChecks: $requiresStatusChecks, requiredStatusCheckContexts: $requiredStatusCheckContexts)"
  }
}
