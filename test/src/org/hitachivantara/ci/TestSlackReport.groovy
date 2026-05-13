/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import hudson.EnvVars
import hudson.model.Run
import hudson.model.TaskListener
import hudson.tasks.junit.TestResultAction
import org.hitachivantara.ci.jenkins.JobUtils
import org.hitachivantara.ci.report.BuildStatus
import org.hitachivantara.ci.report.SlackReport
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.ReplacePropertyRule
import org.hitachivantara.ci.utils.Rules
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import org.junit.Rule
import org.junit.rules.RuleChain
import spock.lang.Unroll

import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_COLLECT_JOB_DATA
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_UNIT_TEST
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_NAME

class TestSlackReport extends BasePipelineSpecification {
  ConfigurationRule configRule = new ConfigurationRule(this)
  ReplacePropertyRule scmUtilsMetaClass = new ReplacePropertyRule()
  ReplacePropertyRule jobUtilsMetaClass = new ReplacePropertyRule()
  ReplacePropertyRule slackReportMetaClass = new ReplacePropertyRule()
  ReplacePropertyRule urlMetaClass = new ReplacePropertyRule()

  @Rule
  RuleChain rules = Rules.getCommonRules(this)
    .around(configRule)
    .around(scmUtilsMetaClass)
    .around(jobUtilsMetaClass)
    .around(slackReportMetaClass)
    .around(urlMetaClass)

  /**
   * Minimal concrete HttpURLConnection for tests that need to exercise
   * fetchCommitFromGitHub's real HTTP logic without making network calls.
   */
  static class MockHttpURLConnection extends HttpURLConnection {
    int status
    byte[] body
    Map<String, String> capturedHeaders = [:]

    MockHttpURLConnection(int status = 200, byte[] body = '{}'.bytes) {
      super(null)
      this.status = status
      this.body = body
    }

    @Override void connect() throws IOException {}
    @Override void disconnect() {}
    @Override boolean usingProxy() { false }
    @Override int getResponseCode() { status }
    @Override InputStream getInputStream() throws IOException { new ByteArrayInputStream(body) }
    @Override void setRequestProperty(String key, String value) { capturedHeaders[key] = value }
  }

  def "test no report is generated if slack integration is not enabled"() {
    setup:
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper))
      SlackReport report = new SlackReport(mockScript)

    when:
      report.build(configRule.buildData)

    then:
      noExceptionThrown()

    and:
      report.attachments.isEmpty()
  }

  def "test success build report with no tests"() {
    setup:
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getCurrentResult() >> 'SUCCESS'
        getDuration() >> 10000l
        getChangeSets() >> []
        getRawBuild() >> GroovyMock(Run) {
          getCauses() >> [[shortDescription: 'Build started by user']]
        }
      })

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([
        BUILD_PLAN_ID       : 'Suite Build',
        RELEASE_BUILD_NUMBER: '123',
        SLACK_INTEGRATION   : true
      ])

    when:
      report.build(configRule.buildData)

    then:
      noExceptionThrown()

    and:
      report.attachments == [
        [
          pretext: '<jenkins.url|Suite Build #123>',
          color  : 'good',
          fields : [
            [
              title: 'Status',
              value: ':sunglasses: SUCCESS',
              short: true
            ],
            [
              title: 'Duration',
              value: ':clock2: 10s',
              short: true
            ]
          ]
        ]
      ]

  }

  def "test the test information"() {
    setup:
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getChangeSets() >> []
        getRawBuild() >> GroovyMock(Run) {
          getAction(TestResultAction.class) >> GroovyMock(TestResultAction) {
            getFailCount() >> failCount
            getTotalCount() >> totalCount
          }
        }
      })

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([
        SLACK_INTEGRATION: true
      ])
      configRule.buildStatus = new BuildStatus(buildStatus: [
        (BuildStatus.Level.TIMINGS): [
          (STAGE_LABEL_UNIT_TEST): [
            (BuildStatus.Category.GENERAL): [duration]
          ]
        ]
      ])

    when:
      report.build(configRule.buildData)

    then:
      noExceptionThrown()

    and:
      report.attachments[0].fields[2..-1] == [
        [
          title: 'Tests',
          value: resultLabel,
          short: true
        ],
        [
          title: 'Duration',
          value: durationLabel,
          short: true
        ]
      ]

    where:
      failCount | totalCount | duration | resultLabel                                     | durationLabel
      10        | 5          | 320000l  | ':-1: <jenkins.url/testReport|10 failed>'       | ':clock2: 5m 20s'
      0         | 20         | 20000l   | ':ok_hand: <jenkins.url/testReport|all passed>' | ':clock2: 20s'
  }

  @Unroll
  def "test #level attachment logs(#logs)"() {
    setup:
      scmUtilsMetaClass.addReplacement(ScmUtils, ['static.getCommitLog': { Script s, JobItem j ->
        [[
           (ScmUtils.COMMIT_ID)    : '0' * 40,
           (ScmUtils.COMMIT_TITLE) : 'TITLE',
           (ScmUtils.COMMIT_AUTHOR): 'AUTHOR',
           (ScmUtils.COMMIT_URL)   : null,
         ]]
      }])
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper){
        getAbsoluteUrl() >> 'jenkins.url'
        getChangeSets() >> []
        getRawBuild() >> GroovyMock(Run)
      })
      SlackReport report = new SlackReport(mockScript)

      configRule.addProperties([
        SLACK_INTEGRATION: true
      ])

      configRule.buildProperties[STAGE_NAME] = 'Versions'
      configRule."$level"('General error message 1')
      configRule."$level"('General error message 2')

      configRule.buildProperties[STAGE_NAME] = 'Build'
      configRule."$level"('General error message 3')
      configRule."$level"(new JobItem(jobID: 'job1', scmUrl: 'git@git:org/repo-1.git', scmBranch: 'master'), null)
      configRule."$level"(new JobItem(jobID: 'job2', scmUrl: 'git@git:org/repo-2.git', scmBranch: 'master'), null)

      configRule.buildProperties[STAGE_NAME] = 'Test'
      configRule."$level"(new JobItem(jobID: 'job3', scmUrl: 'git@git:org/repo-3.git', scmBranch: 'master'), null)
      configRule."$level"(new JobItem(jobID: 'job4', scmUrl: 'git@git:org/repo-4.git', scmBranch: 'master'), null)

    when:
      report.build(configRule.buildData)

    then:
      noExceptionThrown()
    and:
      report.attachments[1] == [
        pretext  : title,
        color    : color,
        mrkdwn_in: ['pretext'],
        fields   : [
          [
            title: 'Versions',
            value: '''\
                - General error message 1
                - General error message 2
              '''.stripIndent(),
            short: false
          ],
          [
            title: 'Build',
            value: '''\
                - General error message 3
              
                - job1 (org/repo-1 @ master)
                     <git@git:org/repo-1.git|0000000> - TITLE
                - job2 (org/repo-2 @ master)
                     <git@git:org/repo-2.git|0000000> - TITLE
              '''.stripIndent(),
            short: false
          ],
          [
            title: 'Test',
            value: '''\
                - job3 (org/repo-3 @ master)
                     <git@git:org/repo-3.git|0000000> - TITLE
                - job4 (org/repo-4 @ master)
                     <git@git:org/repo-4.git|0000000> - TITLE
              '''.stripIndent(),
            short: false
          ]
        ]
      ]

    where:
      level     | title                  | color
      'error'   | ':no_entry: *Errors*'  | 'danger'
      'warning' | ':warning: *Warnings*' | 'warning'
      'error'   | ':no_entry: *Errors*'  | 'danger'
      'warning' | ':warning: *Warnings*' | 'warning'
  }

  def "test unsuccessful build report with and without minions"() {
    setup:
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getCurrentResult() >> 'FAILURE'
        getDuration() >> 10000l
        getChangeSets() >> []
        getRawBuild() >> GroovyMock(Run) {
          getCauses() >> [[shortDescription: 'Build started by user']]
        }
      })

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([
        BUILD_PLAN_ID       : 'Suite Build',
        RELEASE_BUILD_NUMBER: '123',
        SLACK_INTEGRATION   : true,
        USE_MINION_JOBS     : useMinions,
        MINIONS_FOLDER      : 'minions-jobs-folder'
      ])

    Map errors = [
        (BuildStatus.Level.ERRORS): [
            (STAGE_LABEL_UNIT_TEST): [
                (BuildStatus.Category.JOB): [
                    (new JobItem([jobID: 'job1', scmUrl: 'git@git:org/repo-1.git', scmBranch: 'master'])): null,
                    (new JobItem([jobID: 'job2', scmUrl: 'git@git:org/repo-2.git', scmBranch: 'master'])): null
                ]
            ]
        ]
    ]

      configRule.buildStatus = new BuildStatus(buildStatus: hasErrors ? errors : [:])

      jobUtilsMetaClass.addReplacement(JobUtils, ['static.getLastBuildJob': { String name ->
        return GroovyMock(RunWrapper) {
          getAbsoluteUrl() >> "jenkins.url.${name}"
        }
      }])
      scmUtilsMetaClass.addReplacement(ScmUtils, ['static.getCommitLog': { Script s, JobItem j ->
        [[
           (ScmUtils.COMMIT_ID)    : '0' * 40,
           (ScmUtils.COMMIT_TITLE) : 'TITLE',
           (ScmUtils.COMMIT_AUTHOR): 'AUTHOR',
           (ScmUtils.COMMIT_URL)   : null,
         ]]
      }])

    when:
      report.build(configRule.buildData)

    then:
      report.attachments[1]?.fields?.value == expected

    where:
      useMinions << [true, false, true]
      hasErrors << [true, true, false]

      expected << [
        ['''\
        - <jenkins.url.minions-jobs-folder/job1|job1> (org/repo-1 @ master)
             <git@git:org/repo-1.git|0000000> - TITLE
        - <jenkins.url.minions-jobs-folder/job2|job2> (org/repo-2 @ master)
             <git@git:org/repo-2.git|0000000> - TITLE
         '''.stripIndent()],
        ['''\
        - job1 (org/repo-1 @ master)
             <git@git:org/repo-1.git|0000000> - TITLE
        - job2 (org/repo-2 @ master)
             <git@git:org/repo-2.git|0000000> - TITLE
        '''.stripIndent()],
        null
      ]

  }

  def "test GH release info"() {
    setup:
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getCurrentResult() >> 'SUCCESS'
        getDuration() >> 10000l
        getChangeSets() >> []
        getRawBuild() >> GroovyMock(Run) {
          getCauses() >> [[shortDescription: 'Build started by user']]
        }
      })

      scmUtilsMetaClass.addReplacement(ScmUtils, ['static.getCommitLog': { Script s, JobItem j -> [] }])

      SlackReport report = new SlackReport(mockScript)
      configRule.buildData([
        BUILD_DATA_FILE  : 'buildDataSample.yaml',
        SLACK_INTEGRATION: true,
        TAG_NAME         : 'release-1',
        JOB_ITEM_DEFAULTS: [
          createRelease: releasable
        ]
      ])

    Map releases = [
        (BuildStatus.Level.RELEASES): [
            (STAGE_LABEL_UNIT_TEST): [
                (BuildStatus.Category.GENERAL): releaseItems
            ]
        ]
    ]

      configRule.buildStatus = new BuildStatus(buildStatus: releases)

    when:
      report.build(configRule.buildData)

    then:
      report.attachments[1]?.fields?.value == expected

    where:
      releasable << [true, false, true, true]
      releaseItems << [
        [
          [('link'): 'https://github.com/user/repo-1/releases/release-1', ('label'): 'user/repo-1'],
          [('link'): 'https://github.com/user/repo-2/releases/release-1', ('label'): 'user/repo-2']
        ],
        [
          [('link'): 'https://github.com/user/repo-1/releases/release-1', ('label'): 'user/repo-1'],
          [('link'): 'https://github.com/user/repo-2/releases/release-1', ('label'): 'user/repo-2'],
          [('link'): 'https://github.com/user/repo-3/releases/release-1', ('label'): 'user/repo-3'],
          [('link'): 'https://github.com/user/repo-4/releases/release-1', ('label'): 'user/repo-4'],
          [('link'): 'https://github.com/user/repo-5/releases/release-1', ('label'): 'user/repo-5'],
          [('link'): 'https://github.com/user/repo-6/releases/release-1', ('label'): 'user/repo-6']
        ],
        [
          [('link'): 'https://github.com/user/repo-1/releases/release-1', ('label'): 'user/repo-1'],
          [('link'): 'https://github.com/user/repo-2/releases/release-1', ('label'): 'user/repo-2'],
          [('link'): 'https://github.com/user/repo-3/releases/release-1', ('label'): 'user/repo-3'],
          [('link'): 'https://github.com/user/repo-4/releases/release-1', ('label'): 'user/repo-4'],
          [('link'): 'https://github.com/user/repo-5/releases/release-1', ('label'): 'user/repo-5'],
          [('link'): 'https://github.com/user/repo-6/releases/release-1', ('label'): 'user/repo-6']
        ],
        []
      ]

      expected << [
        [
          '''\
            - <https://github.com/user/repo-1/releases/release-1|user/repo-1>
            - <https://github.com/user/repo-2/releases/release-1|user/repo-2>
          '''.stripIndent()
        ],
        'Release *release-1*: Not all repos were released! See the logs for further information.',
        'Release *release-1*:  created for all repos!',
        ['']
      ]
  }

  def "test job data status info"() {
    setup:
    mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
      getRawBuild() >> GroovyMock(Run) {
        getEnvironment(_) >> { TaskListener listener ->
          EnvVars envVars = new EnvVars(['JENKINS_URL': 'http://dummies.org'])
          return envVars
        }
      }
    })

    SlackReport report = new SlackReport(mockScript)
    configRule.buildData([
      BUILD_DATA_FILE  : 'buildDataSample.yaml',
      SLACK_INTEGRATION: true
    ])

    Map branchStatus = [
        (BuildStatus.Level.BRANCH_STATUS): [
            (STAGE_LABEL_COLLECT_JOB_DATA): [(BuildStatus.Category.GENERAL): branchStatusData as Map]]
    ]

    configRule.buildStatus = new BuildStatus(buildStatus: branchStatus)

    when:
    report.build(configRule.buildData)

    then:
    report.attachments[0].fields == expectedFields

    and:
    report.attachments[0].pretext == expectedPretext

    and:
    report.attachments[0].color == expectedColor

    where:
    branchStatusData << [
      [
        'master':
          [
            'status'       : 'ABORTED',
            'pull-requests':
              ['Success': 1, 'Failure': 1],
            'jobs'         :
              ['Success': 1, 'Aborted': 2], 'failing-tests': 30
          ]
      ]
    ]

    expectedFields <<
      [
        [
          [title: 'Status', value: ':fearful: ABORTED', short: true],
          [title: 'Failing tests', value: ':-1: 30', short: true],
          [title: 'Open pull requests :open_pr:', value: '- Success: 1\n- Failure: 1', short: true]
        ]
      ]

    expectedPretext = '<http://dummies.org|master branch health check>'
    expectedColor = '#838282'
  }

  def "test build changes attach formats commit links from changeSets via GitHub API"() {
    setup:
      String commitId1 = 'aaaa1111bbbb2222cccc3333dddd4444eeee5555'
      String commitId2 = '1111aaaa2222bbbb3333cccc4444dddd5555eeee'

      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getCurrentResult() >> 'SUCCESS'
        getDuration() >> 1000l
        getChangeSets() >> [
          [items: [
            [commitId: commitId1],
            [commitId: commitId2]
          ]]
        ]
      })

      slackReportMetaClass.addReplacement(SlackReport, ['fetchCommitFromGitHub': { String commitId, List repos, String token ->
        [html_url: "http://github.com/org/repo/commit/${commitId}", commit: [message: "Fix for ${commitId}"]]
      }])

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([SLACK_INTEGRATION: true])

    when:
      report.build(configRule.buildData)

    then:
      noExceptionThrown()

    and: "a changes attachment is present"
      Map changesAttach = report.attachments.find { it.pretext == ':twisted_rightwards_arrows: *Changes*' }
      changesAttach != null
      changesAttach['fields'] != null

    and: "both changeSet commits appear formatted with short hash and message"
      String allValues = changesAttach['fields'].collect { it.value }.join('')
      allValues.contains(commitId1.take(6))
      allValues.contains(commitId2.take(6))
      allValues.contains("Fix for ${commitId1}")
      allValues.contains("Fix for ${commitId2}")
  }

  def "test build changes attach deduplicates commits appearing in multiple changeSets"() {
    setup:
      String sharedCommitId = 'aaaa1111bbbb2222cccc3333dddd4444eeee5555'

      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getCurrentResult() >> 'SUCCESS'
        getDuration() >> 1000l
        getChangeSets() >> [
          [items: [[commitId: sharedCommitId]]],
          [items: [[commitId: sharedCommitId]]]   // same commit in two separate changeSets
        ]
      })

      int fetchCallCount = 0
      slackReportMetaClass.addReplacement(SlackReport, ['fetchCommitFromGitHub': { String commitId, List repos, String token ->
        fetchCallCount++
        [html_url: "http://github.com/org/repo/commit/${commitId}", commit: [message: 'Shared commit']]
      }])

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([SLACK_INTEGRATION: true])

    when:
      report.build(configRule.buildData)

    then:
      noExceptionThrown()

    and: "the GitHub API was queried only once despite the same commit appearing in two changeSets"
      fetchCallCount == 1

    and: "the changes attachment is present and the commit appears exactly once"
      Map changesAttach = report.attachments.find { it.pretext == ':twisted_rightwards_arrows: *Changes*' }
      changesAttach != null
      String allValues = changesAttach['fields'].collect { it.value }.join('')
      // The display text "|commitId.take(6)>" appears only once (the URL part contains the full hash)
      allValues.count("|${sharedCommitId.take(6)}>") == 1
  }

  def "test build changes attach is empty when no commits match changeSets"() {
    setup:
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getCurrentResult() >> 'SUCCESS'
        getDuration() >> 1000l
        getChangeSets() >> []
      })

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([SLACK_INTEGRATION: true])

    when:
      report.build(configRule.buildData)

    then:
      noExceptionThrown()

    and: "no changes attachment is added when there are no commits in the changeSets"
      !report.attachments.any { it.pretext == ':twisted_rightwards_arrows: *Changes*' }
  }

  def "test build changes attach never calls ScmUtils getCommitLog"() {
    setup:
      // Verify that buildChangesAttach no longer depends on ScmUtils at all
      boolean commitLogCalled = false
      scmUtilsMetaClass.addReplacement(ScmUtils, ['static.getCommitLog': { Script s, JobItem j ->
        commitLogCalled = true
        [[(ScmUtils.COMMIT_ID): 'abc123', (ScmUtils.COMMIT_TITLE): 'Should not appear', (ScmUtils.COMMIT_URL): 'http://example.com']]
      }])

      String commitId = 'abc1234567890abc1234567890abc1234567890ab'
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getCurrentResult() >> 'SUCCESS'
        getDuration() >> 1000l
        getChangeSets() >> [
          [items: [[commitId: commitId]]]
        ]
      })

      slackReportMetaClass.addReplacement(SlackReport, ['fetchCommitFromGitHub': { String id, List repos, String token -> null }])

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([SLACK_INTEGRATION: true])

    when:
      report.build(configRule.buildData)

    then:
      noExceptionThrown()

    and: "ScmUtils.getCommitLog is never called by buildChangesAttach"
      !commitLogCalled

    and: "changes attachment is still present with fallback plain-text commit (no link)"
      Map changesAttach = report.attachments.find { it.pretext == ':twisted_rightwards_arrows: *Changes*' }
      changesAttach != null
      String allValues = changesAttach['fields'].collect { it.value }.join('')
      allValues.contains(commitId.take(6))
      !allValues.contains('http')  // no hyperlink since GitHub lookup failed
  }

  def "test build changes attach warns and skips commits not found in GitHub"() {
    setup:
      List<String> echoMessages = []
      registerAllowedMethod('echo', [String.class], { String msg -> echoMessages << msg })

      String commitId = 'abc1234567890abc1234567890abc1234567890ab'
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getCurrentResult() >> 'SUCCESS'
        getDuration() >> 1000l
        getChangeSets() >> [
          [items: [[commitId: commitId]]]
        ]
      })

      // Simulate GitHub API returning no results for the commit
      slackReportMetaClass.addReplacement(SlackReport, ['fetchCommitFromGitHub': { String id, List repos, String token -> null }])

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([SLACK_INTEGRATION: true])

    when:
      report.build(configRule.buildData)

    then:
      noExceptionThrown()

    and: "a warning is echoed for the unresolved commit"
      echoMessages.any { it.contains("Warning: could not find commit '${commitId}'") }

    and: "changes attachment is present with fallback plain-text commit (no link)"
      Map changesAttach = report.attachments.find { it.pretext == ':twisted_rightwards_arrows: *Changes*' }
      changesAttach != null
      String allValues = changesAttach['fields'].collect { it.value }.join('')
      allValues.contains(commitId.take(6))
      !allValues.contains('<http')  // no hyperlink since GitHub lookup failed
  }

  def "test build changes attach passes GitHub token to API when SCM_API_TOKEN_CREDENTIALS_ID is configured"() {
    setup:
      String capturedToken = null
      slackReportMetaClass.addReplacement(SlackReport, ['fetchCommitFromGitHub': { String commitId, List repos, String token ->
        capturedToken = token
        [html_url: "http://github.com/org/repo/commit/${commitId}", commit: [message: 'Fix']]
      }])

      registerAllowedMethod('string', [Map], { it })
      registerAllowedMethod('withCredentials', [List, Closure], { List creds, Closure cl ->
        // Save and restore env to avoid polluting subsequent tests (mockScript is static)
        def savedEnv = mockScript.binding.variables.containsKey('env') ? mockScript.binding.variables.env : null
        mockScript.binding.setVariable('env', [GITHUB_TOKEN: 'my-github-token'])
        try {
          cl.call()
        } finally {
          if (savedEnv != null) {
            mockScript.binding.setVariable('env', savedEnv)
          } else {
            mockScript.binding.variables.remove('env')
          }
        }
      })

      String commitId = 'abc1234567890abc1234567890abc1234567890ab'
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getCurrentResult() >> 'SUCCESS'
        getDuration() >> 1000l
        getChangeSets() >> [[items: [[commitId: commitId]]]]
      })

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([
        SLACK_INTEGRATION             : true,
        SCM_API_TOKEN_CREDENTIALS_ID  : 'my-credentials-id'
      ])

    when:
      report.build(configRule.buildData)

    then:
      noExceptionThrown()

    and: "the token from credentials is passed to fetchCommitFromGitHub"
      capturedToken == 'my-github-token'

    and: "the changes attachment is present"
      report.attachments.any { it.pretext == ':twisted_rightwards_arrows: *Changes*' }
  }

  def "test build changes attach extracts repos from build map job items"() {
    setup:
      String commitId = 'abc1234567890abc1234567890abc1234567890ab'

      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getCurrentResult() >> 'SUCCESS'
        getDuration() >> 1000l
        getChangeSets() >> [[items: [[commitId: commitId, msg: 'Some commit message']]]]
      })

      List capturedRepos = null
      slackReportMetaClass.addReplacement(SlackReport, ['fetchCommitFromGitHub': { String id, List repos, String token ->
        capturedRepos = repos
        [html_url: "http://github.com/myorg/myrepo/commit/${id}", commit: [message: 'Some commit message']]
      }])

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([SLACK_INTEGRATION: true])

      // Inject job items with known org/repo directly into the build map
      configRule.buildData.buildMap = [
        'group1': [
          new JobItem(jobID: 'job1', scmUrl: 'https://github.com/myorg/myrepo.git', scmBranch: 'main'),
          new JobItem(jobID: 'job2', scmUrl: 'https://github.com/myorg/other-repo.git', scmBranch: 'main')
        ]
      ]

    when:
      report.build(configRule.buildData)

    then:
      noExceptionThrown()

    and: "fetchCommitFromGitHub received the repos extracted from the build map"
      capturedRepos != null
      capturedRepos.any { it.owner == 'myorg' && it.repo == 'myrepo' }
      capturedRepos.any { it.owner == 'myorg' && it.repo == 'other-repo' }

    and: "the changes attachment is present with the commit link"
      report.attachments.any { it.pretext == ':twisted_rightwards_arrows: *Changes*' }
  }

  def "test build changes attach deduplicates repos from build map job items"() {
    setup:
      String commitId = 'abc1234567890abc1234567890abc1234567890ab'

      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getCurrentResult() >> 'SUCCESS'
        getDuration() >> 1000l
        getChangeSets() >> [[items: [[commitId: commitId, msg: 'Some commit']]]]
      })

      List capturedRepos = null
      slackReportMetaClass.addReplacement(SlackReport, ['fetchCommitFromGitHub': { String id, List repos, String token ->
        capturedRepos = repos
        [html_url: "http://github.com/myorg/myrepo/commit/${id}", commit: [message: 'Some commit']]
      }])

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([SLACK_INTEGRATION: true])

      // Two job items pointing to the same repo — should be deduplicated
      configRule.buildData.buildMap = [
        'group1': [
          new JobItem(jobID: 'job1', scmUrl: 'https://github.com/myorg/myrepo.git', scmBranch: 'main'),
          new JobItem(jobID: 'job2', scmUrl: 'https://github.com/myorg/myrepo.git', scmBranch: 'release')
        ]
      ]

    when:
      report.build(configRule.buildData)

    then:
      noExceptionThrown()

    and: "duplicate repos are collapsed to a single entry"
      capturedRepos != null
      capturedRepos.count { it.owner == 'myorg' && it.repo == 'myrepo' } == 1
  }

  def "test send echoes timestamp when slack responds"() {
    setup:
      List<String> echoMessages = []
      registerAllowedMethod('echo', [String.class], { String msg -> echoMessages << msg })
      registerAllowedMethod('slackSend', [Map.class], { Map args ->
        return [ts: '1618300000.123456']
      })

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([
        SLACK_INTEGRATION: true,
        SLACK_CHANNEL    : '#test-channel'
      ])
      report.buildData = configRule.buildData

    when:
      report.send()

    then:
      noExceptionThrown()

    and:
      echoMessages.any { it.contains("1618300000.123456") }
      echoMessages.any { it.contains("Slack notification timestamp") }
  }

  def "test send echoes no-response message when slack returns null"() {
    setup:
      List<String> echoMessages = []
      registerAllowedMethod('echo', [String.class], { String msg -> echoMessages << msg })
      registerAllowedMethod('slackSend', [Map.class], { Map args -> return null })

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([
        SLACK_INTEGRATION: true,
        SLACK_CHANNEL    : '#test-channel'
      ])
      report.buildData = configRule.buildData

    when:
      report.send()

    then:
      noExceptionThrown()

    and:
      echoMessages.contains('No response from Slack plugin')
  }

  def "test send does nothing when slack integration is disabled"() {
    setup:
      List<String> echoMessages = []
      registerAllowedMethod('echo', [String.class], { String msg -> echoMessages << msg })

      boolean slackSendCalled = false
      registerAllowedMethod('slackSend', [Map.class], { Map args -> slackSendCalled = true })

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([SLACK_INTEGRATION: false])
      report.buildData = configRule.buildData

    when:
      report.send()

    then:
      noExceptionThrown()

    and:
      !slackSendCalled
      echoMessages.isEmpty()
  }

  @Unroll
  def "test send resolves channel from build result when SLACK_CHANNEL is a map (#result)"() {
    setup:
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getCurrentResult() >> result
      })

      Map capturedArgs = [:]
      List<String> echoMessages = []
      registerAllowedMethod('echo', [String.class], { String msg -> echoMessages << msg })
      registerAllowedMethod('slackSend', [Map.class], { Map args ->
        capturedArgs.putAll(args)
        return null
      })

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([
        SLACK_INTEGRATION: true,
        SLACK_CHANNEL    : ['BUILD_SUCCESS': '#success-channel', 'BUILD_FAILURE': '#failure-channel']
      ])
      report.buildData = configRule.buildData

    when:
      report.send()

    then:
      noExceptionThrown()

    and:
      capturedArgs.channel == expectedChannel

    where:
      result    | expectedChannel
      'SUCCESS' | '#success-channel'
      'FAILURE' | '#failure-channel'
  }

  // ─── fetchCommitFromGitHub unit tests ────────────────────────────────────────

  def "test fetchCommitFromGitHub returns null immediately when repos list is empty"() {
    setup:
      SlackReport report = new SlackReport(mockScript)
      report.buildData = configRule.buildData

    when:
      Map result = report.fetchCommitFromGitHub('abc123def456', [], null)

    then:
      result == null
      noExceptionThrown()
  }

  def "test fetchCommitFromGitHub returns null and echoes warning when connection throws exception"() {
    setup:
      List<String> echoMessages = []
      registerAllowedMethod('echo', [String.class], { String msg -> echoMessages << msg })

      urlMetaClass.addReplacement(URL, ['openConnection': { -> throw new IOException('Connection refused') }])

      SlackReport report = new SlackReport(mockScript)
      report.buildData = configRule.buildData

    when:
      Map result = report.fetchCommitFromGitHub('abc1234567890abc', [[owner: 'myorg', repo: 'myrepo']], null)

    then:
      result == null
      echoMessages.any { it.contains("Warning: could not fetch commit") }
  }

  def "test fetchCommitFromGitHub returns null when response code is not 200"() {
    setup:
      def mockConn = new MockHttpURLConnection(404)
      urlMetaClass.addReplacement(URL, ['openConnection': { -> mockConn }])

      SlackReport report = new SlackReport(mockScript)
      report.buildData = configRule.buildData

    when:
      Map result = report.fetchCommitFromGitHub('abc1234567890abc', [[owner: 'myorg', repo: 'myrepo']], null)

    then:
      result == null
      mockConn.capturedHeaders['Accept'] == 'application/vnd.github+json'
      mockConn.capturedHeaders['User-Agent'] == 'Jenkins-CI'
      !mockConn.capturedHeaders.containsKey('Authorization')
  }

  def "test fetchCommitFromGitHub returns parsed commit data on 200 response"() {
    setup:
      String responseJson = '{"html_url":"https://github.com/myorg/myrepo/commit/abc1234","commit":{"message":"Fix bug\\nSecond line"}}'
      def mockConn = new MockHttpURLConnection(200, responseJson.bytes)
      urlMetaClass.addReplacement(URL, ['openConnection': { -> mockConn }])

      SlackReport report = new SlackReport(mockScript)
      report.buildData = configRule.buildData

    when:
      Map result = report.fetchCommitFromGitHub('abc1234567890abc', [[owner: 'myorg', repo: 'myrepo']], null)

    then:
      result != null
      result.html_url == 'https://github.com/myorg/myrepo/commit/abc1234'
      (result.commit as Map).message == 'Fix bug\nSecond line'
  }

  def "test fetchCommitFromGitHub sets Authorization header when GitHub token is provided"() {
    setup:
      String responseJson = '{"html_url":"https://github.com/myorg/myrepo/commit/abc","commit":{"message":"msg"}}'
      def mockConn = new MockHttpURLConnection(200, responseJson.bytes)
      urlMetaClass.addReplacement(URL, ['openConnection': { -> mockConn }])

      SlackReport report = new SlackReport(mockScript)
      report.buildData = configRule.buildData

    when:
      report.fetchCommitFromGitHub('abc1234567890abc', [[owner: 'myorg', repo: 'myrepo']], 'my-secret-token')

    then:
      mockConn.capturedHeaders['Authorization'] == 'Bearer my-secret-token'
  }

  def "test fetchCommitFromGitHub tries next repo after non-200 and returns data from second"() {
    setup:
      String responseJson = '{"html_url":"https://github.com/org2/repo2/commit/abc","commit":{"message":"Fix"}}'
      int callCount = 0
      urlMetaClass.addReplacement(URL, ['openConnection': { ->
        callCount++ == 0
          ? new MockHttpURLConnection(404)
          : new MockHttpURLConnection(200, responseJson.bytes)
      }])

      SlackReport report = new SlackReport(mockScript)
      report.buildData = configRule.buildData

    when:
      Map result = report.fetchCommitFromGitHub('abc1234567890abc', [
        [owner: 'org1', repo: 'repo1'],   // 404
        [owner: 'org2', repo: 'repo2']    // 200
      ], null)

    then:
      result != null
      result.html_url == 'https://github.com/org2/repo2/commit/abc'
      callCount == 2
  }

  // ─── send() default channel ───────────────────────────────────────────────────

  def "test send uses empty channel string when SLACK_CHANNEL is not configured"() {
    setup:
      Map capturedArgs = [:]
      registerAllowedMethod('slackSend', [Map.class], { Map args -> capturedArgs.putAll(args); return null })
      registerAllowedMethod('echo', [String.class], { String msg -> })

      SlackReport report = new SlackReport(mockScript)
      // SLACK_CHANNEL defaults to null in default-properties.yaml -> hits the `default:` branch
      configRule.addProperties([SLACK_INTEGRATION: true])
      report.buildData = configRule.buildData

    when:
      report.send()

    then:
      noExceptionThrown()
      capturedArgs.channel == ''
  }

  // ─── buildChangesAttach message truncation ────────────────────────────────────

  def "test build changes attach uses only the first line of a multiline GitHub commit message"() {
    setup:
      String commitId = 'aabb1234567890aabb1234567890aabb1234567890'
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getCurrentResult() >> 'SUCCESS'
        getDuration() >> 1000l
        getChangeSets() >> [[items: [[commitId: commitId]]]]
      })

      slackReportMetaClass.addReplacement(SlackReport, ['fetchCommitFromGitHub': { String id, List repos, String token ->
        [html_url: "http://github.com/org/repo/commit/${id}",
         commit  : [message: 'Subject line\nDetailed body line 1\nDetailed body line 2']]
      }])

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([SLACK_INTEGRATION: true])

    when:
      report.build(configRule.buildData)

    then:
      Map changesAttach = report.attachments.find { it.pretext == ':twisted_rightwards_arrows: *Changes*' }
      changesAttach != null
      String allValues = changesAttach['fields'].collect { it.value }.join('')
      allValues.contains('Subject line')
      !allValues.contains('Detailed body line 1')
  }

  def "test build changes attach uses only the first line of a multiline local fallback commit message"() {
    setup:
      String commitId = 'aabb1234567890aabb1234567890aabb1234567890'
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getCurrentResult() >> 'SUCCESS'
        getDuration() >> 1000l
        getChangeSets() >> [[items: [[commitId: commitId, msg: 'Local subject\nLocal body line']]]]
      })

      slackReportMetaClass.addReplacement(SlackReport, ['fetchCommitFromGitHub': { String id, List repos, String token -> null }])

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([SLACK_INTEGRATION: true])

    when:
      report.build(configRule.buildData)

    then:
      Map changesAttach = report.attachments.find { it.pretext == ':twisted_rightwards_arrows: *Changes*' }
      changesAttach != null
      String allValues = changesAttach['fields'].collect { it.value }.join('')
      allValues.contains('Local subject')
      !allValues.contains('Local body line')
  }

  def "test build changes attach handles null commit object in GitHub response safely"() {
    setup:
      String commitId = 'aabb1234567890aabb1234567890aabb1234567890'
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getCurrentResult() >> 'SUCCESS'
        getDuration() >> 1000l
        getChangeSets() >> [[items: [[commitId: commitId]]]]
      })

      slackReportMetaClass.addReplacement(SlackReport, ['fetchCommitFromGitHub': { String id, List repos, String token ->
        [html_url: "http://github.com/org/repo/commit/${id}", commit: null]   // null commit
      }])

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([SLACK_INTEGRATION: true])

    when:
      report.build(configRule.buildData)

    then:
      noExceptionThrown()
      Map changesAttach = report.attachments.find { it.pretext == ':twisted_rightwards_arrows: *Changes*' }
      changesAttach != null
      changesAttach['fields'].collect { it.value }.join('').contains(commitId.take(6))
  }

  // ─── buildStatusAttach COMMIT_URL branch ─────────────────────────────────────

  def "test status attach uses commit URL from changelog when non-null instead of scmUrl"() {
    setup:
      String customCommitUrl = 'https://custom-git.company.com/commit/abc1234567'
      scmUtilsMetaClass.addReplacement(ScmUtils, ['static.getCommitLog': { Script s, JobItem j ->
        [[
          (ScmUtils.COMMIT_ID)    : 'abc' + '0' * 37,
          (ScmUtils.COMMIT_TITLE) : 'Fix critical bug',
          (ScmUtils.COMMIT_AUTHOR): 'dev',
          (ScmUtils.COMMIT_URL)   : customCommitUrl   // non-null → should be used over scmUrl
        ]]
      }])

      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getChangeSets() >> []
      })

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([SLACK_INTEGRATION: true])
      configRule.buildProperties[STAGE_NAME] = 'Build'
      configRule.error(new JobItem(jobID: 'job1', scmUrl: 'https://github.com/org/repo.git', scmBranch: 'main'), null)

    when:
      report.build(configRule.buildData)

    then:
      Map errorsAttach = report.attachments.find { it.pretext == ':no_entry: *Errors*' }
      errorsAttach != null
      String allValues = errorsAttach['fields'].collect { it.value }.join('')
      allValues.contains(customCommitUrl)               // custom URL is used
      !allValues.contains('github.com/org/repo.git')    // scmUrl NOT used as hyperlink target
  }

  // ─── build() combined statuses ───────────────────────────────────────────────

  def "test build includes both error and warning attachments when both are present"() {
    setup:
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getCurrentResult() >> 'FAILURE'
        getDuration() >> 5000l
        getChangeSets() >> []
      })

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([SLACK_INTEGRATION: true])
      configRule.buildProperties[STAGE_NAME] = 'Deploy'
      configRule.error('Deployment step failed')
      configRule.warning('Deprecated API in use')

    when:
      report.build(configRule.buildData)

    then:
      noExceptionThrown()
      report.attachments.size() == 3   // main + errors + warnings
      report.attachments[1].pretext == ':no_entry: *Errors*'
      report.attachments[1].color == 'danger'
      report.attachments[2].pretext == ':warning: *Warnings*'
      report.attachments[2].color == 'warning'
  }

  // ─── buildStatusReleasesAttach: item without link ────────────────────────────

  def "test releases attach shows plain text label when release item has no link"() {
    setup:
      mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
        getAbsoluteUrl() >> 'jenkins.url'
        getCurrentResult() >> 'SUCCESS'
        getDuration() >> 1000l
        getChangeSets() >> []
      })

      SlackReport report = new SlackReport(mockScript)
      configRule.addProperties([SLACK_INTEGRATION: true, TAG_NAME: 'v1.0'])

      Map releases = [
        (BuildStatus.Level.RELEASES): [
          ('Release stage'): [
            (BuildStatus.Category.GENERAL): [
              [label: 'org/no-link-repo', link: null],       // no link → plain text
              [label: 'org/linked-repo',  link: 'https://github.com/org/linked-repo/releases/v1.0']
            ]
          ]
        ]
      ]
      configRule.buildStatus = new BuildStatus(buildStatus: releases)

    when:
      report.build(configRule.buildData)

    then:
      Map releasesAttach = report.attachments.find { it.pretext == ':label: *Releases*' }
      releasesAttach != null
      String allValues = releasesAttach['fields'].collect { it?.value }.join('')
      allValues.contains('- org/no-link-repo\n')                        // plain text, no hyperlink
      !allValues.contains('<org/no-link-repo|')                          // not in link format
      allValues.contains('<https://github.com/org/linked-repo/releases/v1.0|org/linked-repo>')
  }

}
