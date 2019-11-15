/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.github

class GitHubRepository implements Serializable {
  String owner
  String name

  GitHubRepository(String owner, String name) {
    this.owner = owner
    this.name = name
  }

  GitHubPullRequest getPullRequest(Integer prNumber) {
    return GitHubPullRequest.get(owner, name, prNumber)
  }

  GitHubBranchProtectionRules getBranchProtectionRules() {
    return GitHubBranchProtectionRules.get(owner, name)
  }
}
