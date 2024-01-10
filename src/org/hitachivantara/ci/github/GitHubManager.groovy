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
      String name = src.getRepository()
      GitHubRepository repository = new GitHubRepository(owner, name)
      GitHubPullRequest pullRequest = repository.getPullRequest(prNumber)

      // minimize previous comments
      List<IssueComment> comments = pullRequest.comments.findAll { issueComment -> !issueComment.body.contains('frogbot') & issueComment.viewerDidAuthor & !issueComment.isMinimized }
      comments.each { IssueComment comment ->
        comment.hide(GitHubMinimizeContentReason.OUTDATED)
      }

      // add new comment
      pullRequest.comment(body)
    }
  }

  /** Create or update a Github branch protection rule
   * @param item  JobItem
   */
  static void registerBranchProtectionRule(JobItem item) {
    String owner = item.scmInfo.organization
    String name = item.scmInfo.repository
    String branch = ScmUtils.deriveLocalBranchName(item.scmBranch)
    Boolean protectBranch = item.scmProtectBranch
    Set<String> requiredStatusCheckContexts = [item.prStatusLabel]

    GitHubRepository repository = new GitHubRepository(owner, name)

    GitHubBranchProtectionRules branchProtectionRules = repository.getBranchProtectionRules()
    // search in the current protected branches for a match
    List rules = branchProtectionRules.findRules(branch)
    if (protectBranch) {
      if (rules) {
        rules.each { rule -> rule.addStatusChecks(requiredStatusCheckContexts) }
        steps.log.debug "Updated branch protection rules", rules
      } else {
        def rule = branchProtectionRules.createProtectionRule(branch, requiredStatusCheckContexts)
        steps.log.debug "Created new branch protection rule", rule
      }
    } else {
      // in case we want to revert any rule changes previously made
      if (rules) {
        rules.each { rule -> rule.removeStatusChecks(requiredStatusCheckContexts) }
        steps.log.debug "Reverted branch protection rules", rules
      }
    }
  }

  static Map execute(String query, Map variables) throws GitHubException {
    GitHubClient gh = new GitHubClient()
    GitHubRequest request = gh.buildRequest(query, variables)
    steps.log.debug "GraphQL query:", ['query': query.stripIndent(), 'variables': variables]
    Map data = request.execute()
    if (data.errors) {
      steps.log.error "Query did not succeed!", data
      throw new GitHubException("Something went wrong!")
    }
    steps.log.debug "GraphQL result:", data
    return data
  }
}