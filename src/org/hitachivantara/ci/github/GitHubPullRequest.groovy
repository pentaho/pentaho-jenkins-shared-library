/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.github

import com.cloudbees.groovy.cps.NonCPS
import org.hitachivantara.ci.StringUtils

class GitHubPullRequest implements Serializable {
  String id
  String fullName
  Integer number
  List<IssueComment> comments

  static GitHubPullRequest get(String owner, String name, Integer number) throws GitHubException {
    Map result = GitHubManager.execute('''\
      query GetPullRequestWithComments($owner: String!, $name: String!, $prNumber: Int!) {
        repository(owner: $owner, name: $name) {
          pullRequest: issueOrPullRequest(number: $prNumber) {
            ... on PullRequest {
              id
              comments: timelineItems(last: 10, itemTypes: [ISSUE_COMMENT]) {
                nodes {
                  ... on IssueComment {
                    id
                    isMinimized
                    viewerDidAuthor
                    minimizedReason
                    body
                  }
                }
              }
            }
          }
        }
      }''',
      ['owner': owner, 'name': name, 'prNumber': number]
    )
    return new GitHubPullRequest().with {
      it.id = result.data.repository.pullRequest.id
      it.number = number
      it.fullName = "$owner/$name"
      it.comments = result.data.repository.pullRequest.comments?.nodes?.collect { issueComment ->
        String reason = StringUtils.fixNull(issueComment.minimizedReason)
        new IssueComment().with {
          it.id = issueComment.id
          it.isMinimized = issueComment.isMinimized
          it.viewerDidAuthor = issueComment.viewerDidAuthor
          it.body = issueComment.body
          if (reason) {
            it.minimizedReason = GitHubMinimizeContentReason.valueOf(reason.toUpperCase())
          }
          return it
        }
      }
      return it
    }
  }

  /** Adds a comment to an Issue or Pull Request.
   * @param body The contents
   * @throws GitHubException
   */
  IssueComment comment(String body) throws GitHubException {
    Map result = GitHubManager.execute('''\
      mutation AddComment($pullRequest: ID!, $body: String!) {
        addComment(input: { subjectId: $pullRequest, body: $body }) {
          commentEdge {
            node { id }
          }
        }
      }''',
      ['pullRequest': id, 'body': body]
    )
    IssueComment comment = new IssueComment().with {
      it.id = result.data.addComment.commentEdge.node.id
      it.isMinimized = Boolean.FALSE
      it.viewerDidAuthor = Boolean.TRUE
      return it
    }
    comments.add(comment)
    return comment
  }

  @NonCPS
  @Override
  String toString() {
    return "${getClass().getName()}(fullName: $fullName, number: $number)"
  }

}
