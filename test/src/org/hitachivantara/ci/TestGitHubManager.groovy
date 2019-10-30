/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import hudson.plugins.git.GitObject
import hudson.plugins.git.GitSCM
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.github.GitHubManager
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.GitRule
import org.hitachivantara.ci.utils.ReplacePropertyRule
import org.hitachivantara.ci.utils.Rules
import org.jenkinsci.plugins.gitclient.ChangelogCommand
import org.jenkinsci.plugins.gitclient.GitClient
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import org.junit.Rule
import org.junit.rules.RuleChain

import static org.hitachivantara.ci.config.LibraryProperties.JOB_NAME
import static org.hitachivantara.ci.config.LibraryProperties.RELEASE_BUILD_NUMBER
import static org.hitachivantara.ci.config.LibraryProperties.SCM_CREDENTIALS_ID
import static org.hitachivantara.ci.config.LibraryProperties.TAG_NAME
import static org.hitachivantara.ci.config.LibraryProperties.TICKET_ID_PATTERN
import static org.hitachivantara.ci.config.LibraryProperties.TICKET_MANAGER_URL

class TestGitHubManager extends BasePipelineSpecification {

  ConfigurationRule configRule = new ConfigurationRule(this)
  ReplacePropertyRule scmUtilsMetaClass = new ReplacePropertyRule()
  ReplacePropertyRule gitHubMetaClass = new ReplacePropertyRule()
  GitRule gitRule = new GitRule(this)

  @Rule
  RuleChain rules = Rules.getCommonRules(this)
    .around(configRule)
    .around(new ReplacePropertyRule((GitHubManager): ['static.getSteps': { -> mockScript }]))
    .around(scmUtilsMetaClass)
    .around(gitHubMetaClass)

  def "test github's release creation"() {
    setup:
    configRule.buildProperties[SCM_CREDENTIALS_ID] = 'build-person'
    configRule.buildProperties[TICKET_MANAGER_URL] = 'JIRA.url/'
    configRule.buildProperties[JOB_NAME] = 'my-job'
    configRule.buildProperties[RELEASE_BUILD_NUMBER] = '22'
    configRule.buildProperties[TICKET_ID_PATTERN] = /\[(\w+-\d+)\]/
    configRule.buildProperties[TAG_NAME] = expectedTagName

    registerAllowedMethod('resolveTemplate', [Map], { Map params ->
      StringBuilder sb = new StringBuilder()
      params.parameters.each { String key, Object val ->
        sb << key
        sb << ': '
        sb << val
        sb << '\n'
      }
      sb.toString()
    })
    String releaseText
    registerAllowedMethod('createGithubRelease', [Map], { Map params ->
      releaseText = params.text
      return releaseText != null
    })

    tagsData.each { Map data ->
      gitRule.addCommitLog(data['sha'], data['message'])
    }

    mockScript.binding.setVariable('currentBuild',
      GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url/'
      })

    ChangelogCommand changeLogCommand = Mock(ChangelogCommand)
    changeLogCommand.to(_) >> changeLogCommand
    changeLogCommand.max(_) >> changeLogCommand

    scmUtilsMetaClass.addReplacement(ScmUtils, [
      'static.getGitClient'               : { Script steps, GitSCM scm, String checkoutDir ->
        Mock(GitClient) {
          changelog() >> changeLogCommand
          getTags() >> {
            Set<GitObject> tags = []
            tagsData.each { Map val ->
              tags.add(Mock(GitObject) {
                getName() >> val.name
                getSHA1String() >> val.sha
                getTagMessage(_) >> val.message
              })
            }
            tags
          }
        }
      },
      'static.getCommitsBetween'          : { Script steps, String sha1, String sha2 ->
        [sha1, sha2]
      },
      'static.filterChangelogRevisions'   : { GitClient gitClient, List<String> revList ->
        gitRule.commitLogInputStream
      }
    ])

    JobItem item = new JobItem(
      'test',
      [jobID: 'job1', scmUrl: 'git@git:org/repo-1.git', previousReleaseTag: previousReleaseTagName],
      configRule.buildProperties
    )

    when:
    GitHubManager.createRelease(item, expectedTagName)

    then:
    noExceptionThrown()

    and:
    BuildData.instance.buildStatus.hasReleases()

    and:
    releaseText == expectedBodyText

    where:
    tagsData | expectedTagName | expectedBodyText | previousReleaseTagName
    null     | null            | null             | null
    [
      ['name': 'tag-1', 'sha': '1521057686000', 'message': '[BACKLOG-1] - commit message for line #1']
    ]        | 'tag-0'         | '''previousTag: initial commit
messages: [[[BACKLOG-1]](JIRA.url//BACKLOG-1) - commit message for line #1]
build: {job=my-job, number=22, url=jenkins.url/}
'''           | null

    [
      ['name': 'tag-0', 'sha': '1519993987000', 'message': '[BACKLOG-0] - commit message for line #0'],
      ['name': 'tag-1', 'sha': '1521057686000', 'message': '[BACKLOG-1] - commit message for line #1'],
      ['name': 'tag-2', 'sha': '1538561396000', 'message': '[BACKLOG-2] - commit message for line #2']
    ]        | 'tag-2'         | '''previousTag: tag-1
messages: [[[BACKLOG-0]](JIRA.url//BACKLOG-0) - commit message for line #0, [[BACKLOG-1]](JIRA.url//BACKLOG-1) - commit message for line #1, [[BACKLOG-2]](JIRA.url//BACKLOG-2) - commit message for line #2]
build: {job=my-job, number=22, url=jenkins.url/}
'''           | null

    [
      ['name': 'tag-0', 'sha': '1519993987000', 'message': '[BACKLOG-0] - commit message for line #0'],
      ['name': 'tag-1', 'sha': '1521057686000', 'message': '[BACKLOG-1] - commit message for line #1'],
      ['name': 'tag-2', 'sha': '1538561396000', 'message': '[BACKLOG-2] - commit message for line #2'],
    ]        | 'tag-2'         | '''previousTag: tag-0
messages: [[[BACKLOG-0]](JIRA.url//BACKLOG-0) - commit message for line #0, [[BACKLOG-1]](JIRA.url//BACKLOG-1) - commit message for line #1, [[BACKLOG-2]](JIRA.url//BACKLOG-2) - commit message for line #2]
build: {job=my-job, number=22, url=jenkins.url/}
'''           | 'tag-0'
  }

}
