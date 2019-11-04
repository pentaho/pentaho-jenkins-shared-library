/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.github

class IssueComment implements Serializable {
  String id
  Boolean isMinimized
  Boolean viewerDidAuthor
  GitHubMinimizeContentReason minimizedReason

  /** Minimizes a comment on an Issue, Commit, Pull Request, or Gist
   * @param reason The reason why to minimize
   * @throws GitHubException
   */
  void hide(GitHubMinimizeContentReason reason) throws GitHubException {
    GitHubManager.execute('''\
        mutation HideComment($commentId: ID!, $reason: ReportedContentClassifiers!) {
          minimizeComment(input: { subjectId: $commentId, classifier: $reason }) {
            minimizedComment {
              isMinimized
              minimizedReason
            }
          }
        }''',
      ['commentId': id, 'reason': reason.name()]
    )
    isMinimized = Boolean.TRUE
    minimizedReason = reason
  }
}
