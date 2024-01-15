/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.github

import hudson.model.Item
import jenkins.scm.api.SCMSource
import org.hitachivantara.ci.BasePipelineSpecification
import org.hitachivantara.ci.utils.ReplacePropertyRule
import org.hitachivantara.ci.utils.Rules
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource
import org.junit.Rule
import org.junit.rules.RuleChain

class GitHubPullRequestTest extends BasePipelineSpecification {
  ReplacePropertyRule replacements = new ReplacePropertyRule((GitHubManager): ['static.getSteps': { -> mockScript }])

  @Rule
  public RuleChain ruleChain = Rules
    .getCommonRules(this)
    .around(replacements)

  def "test pull request comment creation"() {
    given:
      GitHubPullRequest pullRequest = new GitHubPullRequest(comments: [])
      replacements.addReplacement(GitHubManager, [
        'static.execute': { String query, Map variables ->
          [data: [addComment: [commentEdge: [node: [id: '1']]]]]
        }
      ])
    when:
      def comment = pullRequest.comment('New comment')
    then:
      verifyAll {
        pullRequest.comments.size() == 1
        !comment.isMinimized
        comment.viewerDidAuthor
      }
  }

  def "test pull request comment hiding"() {
    given:
      GitHubPullRequest pullRequest = new GitHubPullRequest(comments: [
        new IssueComment(id: 1, isMinimized: false, viewerDidAuthor: true),
      ])
      replacements.addReplacement(GitHubManager, [
        'static.execute': { String query, Map variables ->
          [data: [addComment: [commentEdge: [node: [id: '1']]]]]
        }
      ])
    when:
      pullRequest.comments[0].hide(GitHubMinimizeContentReason.RESOLVED)
    then:
      pullRequest.comments[0].isMinimized
      pullRequest.comments[0].minimizedReason == GitHubMinimizeContentReason.RESOLVED
  }

  def "test commentPullRequest"() {
    given:
      GitHubPullRequest pullRequest = new GitHubPullRequest(comments: [
        new IssueComment(id: '1', isMinimized: false, viewerDidAuthor: true, body: 'something'),
        new IssueComment(id: '2', isMinimized: false, viewerDidAuthor: false, body: 'also something'),
      ])
      replacements.addReplacement(GitHubPullRequest, ['static.get': { String owner, String name, Integer number -> pullRequest }])
      replacements.addReplacement(GitHubManager, [
        'static.execute': { String query, Map variables ->
          [data: [addComment: [commentEdge: [node: [id: '3']]]]]
        }
      ])
      replacements.addReplacement(SCMSource.SourceByItem, ['static.findSource': { Item item ->
        Mock(GitHubSCMSource) {
          getRepoOwner() >> ('owner')
          getRepository() >> ('name')
        }
      }])
    when:
      GitHubManager.commentPullRequest('New Comment')
    then:
      verifyAll {
        pullRequest.comments.size() == 3
        pullRequest.comments[0].isMinimized
        !pullRequest.comments[1].isMinimized
        pullRequest.comments[0].minimizedReason == GitHubMinimizeContentReason.OUTDATED
      }
  }
}
