/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.github

import com.cloudbees.groovy.cps.NonCPS
import hudson.model.Job
import hudson.plugins.git.GitChangeLogParser
import hudson.plugins.git.GitChangeSet
import hudson.plugins.git.GitObject
import hudson.plugins.git.GitSCM
import jenkins.scm.api.SCMSource
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.ScmUtils
import org.hitachivantara.ci.config.BuildData
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource
import org.jenkinsci.plugins.gitclient.GitClient
import org.jenkinsci.plugins.workflow.cps.CpsScript
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

import static org.hitachivantara.ci.config.LibraryProperties.CHANGE_ID
import static org.hitachivantara.ci.config.LibraryProperties.JOB_NAME
import static org.hitachivantara.ci.config.LibraryProperties.RELEASE_BUILD_NUMBER
import static org.hitachivantara.ci.config.LibraryProperties.SCM_CREDENTIALS_ID
import static org.hitachivantara.ci.config.LibraryProperties.TAG_NAME
import static org.hitachivantara.ci.config.LibraryProperties.TICKET_ID_PATTERN
import static org.hitachivantara.ci.config.LibraryProperties.TICKET_MANAGER_URL

class GitHubManager implements Serializable {

  static Script getSteps() {
    ({} as CpsScript)
  }

  /**
   * Creates a GitHub release
   * @param jobItem
   * @return
   */
  static void createRelease(JobItem jobItem) {
    BuildData buildData = BuildData.instance

    Map<String, Object> changeSetListData = getChangeListData(jobItem, buildData.getString(TAG_NAME))
    List<GitChangeSet> changeSetList = changeSetListData['CHANGE_LIST']

    if (changeSetList) {
      final String bodyText = getReleaseBodyText(
        getCommitMessages(
          changeSetList,
          buildData.getString(TICKET_MANAGER_URL),
          buildData.getString(TICKET_ID_PATTERN)
        ),
        changeSetListData['LAST_COMMIT_LABEL'] as String
      )
      steps.log.debug 'Release text', bodyText

      Boolean releasedSaved = steps.createGithubRelease(
        credentials: buildData.getString(SCM_CREDENTIALS_ID),
        repository: "${jobItem.scmOrganization}/${jobItem.scmRepository}",
        name: "${buildData.getString(TAG_NAME)}",
        text: bodyText
      )

      if (releasedSaved) {
        buildData.release([('link') : "${(jobItem.scmUrl - '.git')}/releases/${buildData.getString(TAG_NAME)}",
                           ('label'): "${jobItem.scmOrganization}/${jobItem.scmRepository}"])
      }
    } else {
      buildData.release([('link') : null,
                         ('label'): "GH Release for ${jobItem.jobID} could not continue! We weren\'t able to retrieve a time interval to check for commits."])
    }
  }

  /**
   * Retrieves the changelog data to be considered in the release
   * @param jobItem
   * @param tagName
   * @return
   */
  private static Map<String, Object> getChangeListData(JobItem jobItem, String tagName) {
    GitSCM scm = ScmUtils.getGitSCM(jobItem)
    GitClient gitClient = ScmUtils.getGitClient(steps, scm, jobItem.checkoutDir)

    List<Map<String, String>> localTags = getLocalTags(gitClient)

    Map<String, String> endData = localTags.flatten().find { Map<String, String> tag -> tag['name'] == tagName }
    Map<String, String> startData

    String firstTagLabel
    if (localTags.size() >= 2) {
      if (jobItem.previousReleaseTag) {
        startData = localTags.flatten().find { Map<String, String> tag -> tag['name'] == jobItem.previousReleaseTag }
      } else {
        startData = localTags[-2]
      }
      firstTagLabel = startData['name']
    } else { // the repo has been tagged only once
      startData = null
    }

    List<String> commits = null
    if (startData) {
      commits = ScmUtils.getCommitsBetween(steps, endData['sha'], startData['sha'])
    }
    InputStream is = ScmUtils.filterChangelogRevisions(gitClient, commits)

    GitChangeLogParser parser = scm.createChangeLogParser() as GitChangeLogParser
    List<GitChangeSet> changeSetList = parser.parse(is)
    steps.log.debug 'Number of commits found:', changeSetList.size()
    return [
      ('CHANGE_LIST')      : changeSetList,
      ('LAST_COMMIT_LABEL'): firstTagLabel ?: 'initial commit'
    ]
  }

  /**
   * Retrieves a list of tags from local repo
   *
   * @param jobItem
   * @param client
   * @return
   */
  static List<Map<String, String>> getLocalTags(GitClient client) {
    List<Map<String, String>> tags = []
    client.getTags().each { GitObject tag ->
      tags.add(
        [name: tag.name, sha: tag.getSHA1String(), message: client.getTagMessage(tag.name)]
      )
    }
    return sortTags(tags)
  }

  /**
   * Sorts the tags. Isolated due to CPS
   * @param tags
   * @return
   */
  @NonCPS
  private static sortTags(List<Map<String, String>> tags) {
    return tags.sort {
      Map<String, String> item -> item['name']
    }
  }

  /**
   * Assemblies a list with the messages to be considered as the change log
   * @param repo
   * @param start
   * @param end
   * @param ticketManagerUrl
   * @return
   */
  @NonCPS
  private static List<String> getCommitMessages(List<GitChangeSet> commits,
                                                String ticketManagerUrl,
                                                String ticketIdPattern) {
    List<String> commitMessages = []
    commits.each { GitChangeSet commit ->
      String msg = commit.getMsg()
      msg = msg.replaceAll(ticketIdPattern) { String match, String id ->
        "[${match}](${ticketManagerUrl}/${id})"
      }
      commitMessages << msg
    }
    return commitMessages
  }

  /**
   * Assembles the main text for the release
   * @param commitMessages
   * @param startingCommit
   * @return
   */
  private static String getReleaseBodyText(List<String> commitMessages, String startingCommit) {
    RunWrapper build = steps.currentBuild
    BuildData buildData = BuildData.instance

    Map buildInfo = [
      job   : buildData.getString(JOB_NAME),
      number: buildData.getString(RELEASE_BUILD_NUMBER),
      url   : build.absoluteUrl
    ]

    String template = steps.libraryResource resource: "templates/github-release.vm", encoding: 'UTF-8'

    return steps.resolveTemplate(
      text: template,
      parameters: [
        previousTag: startingCommit,
        messages   : commitMessages,
        build      : buildInfo
      ]
    )
  }

  /** Create or update a Github PR comment
   * @param body
   */
  static void commentPullRequest(String body) {
    Job job = steps.getContext(Job.class)
    SCMSource src = SCMSource.SourceByItem.findSource(job)
    if (src instanceof GitHubSCMSource) {
      BuildData buildData = BuildData.instance
      Integer prNumber = buildData.getInt(CHANGE_ID)
      String owner = src.getRepoOwner()
      String repository = src.getRepository()

      Map data = getPullRequestWithComments(owner, repository, prNumber)
      String pullRequestId = data.data.repository.pullRequest.id

      // minimize previous comments
      List<Map> comments = data.data.repository.pullRequest.comments.nodes.findAll { Map node -> node.viewerDidAuthor & !node.isMinimized }
      comments.each { Map comment ->
        hideComment(comment.id as String, GitHubMinimizeContentReason.OUTDATED)
      }

      // add new comment
      addComment(pullRequestId, body)
    }
  }

  private static Map getPullRequestWithComments(String owner, String name, Integer number) throws GitHubException {
    execute('''\
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
                    }
                  }
                }
              }
            }
          }
        }''',
      ['owner': owner, 'name': name, 'prNumber': number]
    )
  }

  /** Adds a comment to an Issue or Pull Request.
   * @param pullRequest The Node ID of the subject to modify.
   * @param body The contents
   * @throws GitHubException
   */
  private static void addComment(String pullRequest, String body) throws GitHubException {
    execute('''\
      mutation AddComment($pullRequest: ID!, $body: String!) {
        addComment(input: { subjectId: $pullRequest, body: $body }) {
          commentEdge {
            node { id }
          }
        }
      }''',
      ['pullRequest': pullRequest, 'body': body]
    )
  }


  /** Minimizes a comment on an Issue, Commit, Pull Request, or Gist
   * @param commentId The Node ID of the subject to modify.
   * @param readon The reason why to minimize
   * @throws GitHubException
   */
  private static void hideComment(String commentId, GitHubMinimizeContentReason reason) throws GitHubException {
    execute('''\
      mutation HideComment($commentId: ID!, $reason: ReportedContentClassifiers!) {
        minimizeComment(input: { subjectId: $commentId, classifier: $reason }) {
          minimizedComment {
            isMinimized
            minimizedReason
          }
        }
      }''',
      ['commentId': commentId, 'reason': reason.name()]
    )
  }

  private static Map execute(String query, Map variables) throws GitHubException {
    GitHubClient gh = new GitHubClient()
    GitHubRequest request = gh.buildRequest(query, variables)
    steps.log.debug "GraphQL query:", ['query': query.stripIndent(), 'variables': variables]
    Map data = request.execute()
    if (data.errors) {
      steps.log.error "Query did not succeed!", data
      throw new GitHubException("Something went wrong!")
    }
    return data
  }
}