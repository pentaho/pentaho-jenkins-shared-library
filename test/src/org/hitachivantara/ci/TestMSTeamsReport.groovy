/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import hudson.EnvVars
import hudson.model.AbstractBuild
import hudson.model.TaskListener
import hudson.tasks.junit.TestResultAction
import org.hitachivantara.ci.github.BranchProtectionRule
import org.hitachivantara.ci.jenkins.JobUtils
import org.hitachivantara.ci.report.BuildStatus
import org.hitachivantara.ci.report.MSTeamsReport
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.ReplacePropertyRule
import org.hitachivantara.ci.utils.Rules
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import org.junit.Rule
import org.junit.rules.RuleChain
import spock.lang.Unroll

import static org.hitachivantara.ci.config.LibraryProperties.*

class TestMSTeamsReport extends BasePipelineSpecification {
  ConfigurationRule configRule = new ConfigurationRule(this)
  ReplacePropertyRule scmUtilsMetaClass = new ReplacePropertyRule()
  ReplacePropertyRule jobUtilsMetaClass = new ReplacePropertyRule()

  @Rule
  RuleChain rules = Rules.getCommonRules(this)
      .around(configRule)
      .around(scmUtilsMetaClass)
      .around(jobUtilsMetaClass)

  def "test no report is generated if MS Tems integration is not enabled"() {
    setup:
    mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper))
    MSTeamsReport report = new MSTeamsReport(mockScript)

    when:
    report.build(configRule.buildData)

    then:
    noExceptionThrown()

    and:
    report.sections.isEmpty()
  }

  def "test success build report with no tests"() {
    setup:
    mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
      getAbsoluteUrl() >> 'jenkins.url'
      getCurrentResult() >> 'SUCCESS'
      getDuration() >> 10000l
      getRawBuild() >> GroovyMock(AbstractBuild) {
        getCauses() >> [[shortDescription: 'Build started by user']]
      }
    })

    MSTeamsReport report = new MSTeamsReport(mockScript)
    configRule.addProperties([
        BUILD_PLAN_ID       : 'Suite Build',
        RELEASE_BUILD_NUMBER: '123',
        MS_TEAMS_INTEGRATION: true
    ])

    when:
    report.build(configRule.buildData)

    then:
    noExceptionThrown()

    and:
    report.sections == [
        [
            activityTitle   : "<a href='jenkins.url'>Suite Build #123</a>",
            activitySubtitle: "Duration ⏱️ 10s",
            activityImage   : "https://avatars.githubusercontent.com/u/1022787?s=120&v=4",
            facts           : [
                [
                    name : "Status",
                    value: "😎 SUCCESS"
                ]
            ],
            markdown        : true
        ]
    ]

  }

  def "test the test information"() {
    setup:
    mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
      getAbsoluteUrl() >> 'jenkins.url'
      getRawBuild() >> GroovyMock(AbstractBuild) {
        getAction(TestResultAction.class) >> GroovyMock(TestResultAction) {
          getFailCount() >> failCount
          getTotalCount() >> totalCount
        }
      }
    })

    MSTeamsReport report = new MSTeamsReport(mockScript)
    configRule.addProperties([
        MS_TEAMS_INTEGRATION: true
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
    report.sections[0].facts[1..-1] == [
        [
            name : 'Tests',
            value: resultLabel
        ]
    ]

    where:
    failCount | totalCount | duration | resultLabel
    10        | 5          | 320000l  | "👎 <a href='jenkins.url/testReport'>10 failed</a> (⏱️ 5m 20s)"
    0         | 20         | 20000l   | "👍 <a href='jenkins.url/testReport'>all passed</a> (⏱️ 20s)"
  }

  @Unroll
  def "test #level logs(#logs)"() {
    setup:
    scmUtilsMetaClass.addReplacement(ScmUtils, ['static.getCommitLog': { Script s, JobItem j ->
      [[
           (ScmUtils.COMMIT_ID)    : '0' * 40,
           (ScmUtils.COMMIT_TITLE) : 'TITLE',
           (ScmUtils.COMMIT_AUTHOR): 'AUTHOR',
           (ScmUtils.COMMIT_URL)   : null,
       ]]
    }])
    mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
      getAbsoluteUrl() >> 'jenkins.url'
      getRawBuild() >> GroovyMock(AbstractBuild)
    })
    MSTeamsReport report = new MSTeamsReport(mockScript)

    configRule.addProperties([
        MS_TEAMS_INTEGRATION: true
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
    report.sections[1] == [
        activityTitle: title,
        markdown     : true,
        facts        : [
            [
                name : 'Versions',
                value: '''\
                - General error message 1
                - General error message 2
              '''.stripIndent()
            ],
            [
                name : 'Build',
                value: '''\
                - General error message 3
              
                - job1 (org/repo-1 @ master)
                     <a href='git@git:org/repo-1.git'>0000000</a> - TITLE
                - job2 (org/repo-2 @ master)
                     <a href='git@git:org/repo-2.git'>0000000</a> - TITLE
              '''.stripIndent()
            ],
            [
                name : 'Test',
                value: '''\
                - job3 (org/repo-3 @ master)
                     <a href='git@git:org/repo-3.git'>0000000</a> - TITLE
                - job4 (org/repo-4 @ master)
                     <a href='git@git:org/repo-4.git'>0000000</a> - TITLE
              '''.stripIndent()
            ]
        ]
    ]

    where:
    level     | title          | color
    'error'   | '⛔ *Errors*'   | 'danger'
    'warning' | '⚠ *Warnings*' | 'warning'
    'error'   | '⛔ *Errors*'   | 'danger'
    'warning' | '⚠ *Warnings*' | 'warning'
  }

  def "test unsuccessful build report with and without minions"() {
    setup:
    mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
      getAbsoluteUrl() >> 'jenkins.url'
      getCurrentResult() >> 'FAILURE'
      getDuration() >> 10000l
      getRawBuild() >> GroovyMock(AbstractBuild) {
        getCauses() >> [[shortDescription: 'Build started by user']]
      }
    })

    MSTeamsReport report = new MSTeamsReport(mockScript)
    configRule.addProperties([
        BUILD_PLAN_ID       : 'Suite Build',
        RELEASE_BUILD_NUMBER: '123',
        MS_TEAMS_INTEGRATION: true,
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
    report.sections[1]?.facts?.value == expected

    where:
    useMinions << [true, false, true]
    hasErrors << [true, true, false]

    expected << [
        ['''\
        - <a href='jenkins.url.minions-jobs-folder/job1'>job1</a> (org/repo-1 @ master)
             <a href='git@git:org/repo-1.git'>0000000</a> - TITLE
        - <a href='jenkins.url.minions-jobs-folder/job2'>job2</a> (org/repo-2 @ master)
             <a href='git@git:org/repo-2.git'>0000000</a> - TITLE
         '''.stripIndent()],
        ['''\
        - job1 (org/repo-1 @ master)
             <a href='git@git:org/repo-1.git'>0000000</a> - TITLE
        - job2 (org/repo-2 @ master)
             <a href='git@git:org/repo-2.git'>0000000</a> - TITLE
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
      getRawBuild() >> GroovyMock(AbstractBuild) {
        getCauses() >> [[shortDescription: 'Build started by user']]
      }
    })

    MSTeamsReport report = new MSTeamsReport(mockScript)
    configRule.buildData([
        BUILD_DATA_FILE     : 'buildDataSample.yaml',
        MS_TEAMS_INTEGRATION: true,
        TAG_NAME            : 'release-1',
        JOB_ITEM_DEFAULTS   : [
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
    report.sections[1]?.facts?.value == expected

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
            - <a href='https://github.com/user/repo-1/releases/release-1'>user/repo-1</a>
            - <a href='https://github.com/user/repo-2/releases/release-1'>user/repo-2</a>
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
      getRawBuild() >> GroovyMock(AbstractBuild) {
        getEnvironment(_) >> { TaskListener listener ->
          EnvVars envVars = new EnvVars(['JENKINS_URL': 'http://dummies.org'])
          return envVars
        }
      }
    })

    MSTeamsReport report = new MSTeamsReport(mockScript)
    configRule.buildData([
        BUILD_DATA_FILE     : 'buildDataSample.yaml',
        MS_TEAMS_INTEGRATION: true
    ])

    Map branchStatus = [
        (BuildStatus.Level.BRANCH_STATUS): [
            (STAGE_LABEL_COLLECT_JOB_DATA): [(BuildStatus.Category.GENERAL): branchStatusData as Map]]
    ]

    configRule.buildStatus = new BuildStatus(buildStatus: branchStatus)

    when:
    report.build(configRule.buildData)

    then:
    report.sections[0].facts == expectedFields

    and:
    report.sections[0].activityTitle == expectedPretext

    where:
    branchStatusData << [
        [
            'master':
                [
                    'status'                                         : 'ABORTED',
                    'pull-requests'                                  :
                        ['Success': 1, 'Failure': 1],
                    'jobs'                                           :
                        ['Success': 1, 'Aborted': 2], 'failing-tests': 30
                ]
        ]
    ]

    expectedFields <<
        [
            [
                [name: 'Status', value: '😨 ABORTED'],
                [name: 'Failing tests', value: '👎 30'],
                [name: 'Open pull requests 🔀', value: '- Success: 1\n- Failure: 1']
            ]
        ]

    expectedPretext = "<a href='http://dummies.org'>master branch health check</a>"
    expectedColor = '#838282'
  }

  def "test send method"() {
    setup:
    mockScript.env.TEAMS_WEBHOOK = 'webhook.url'
    mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
      getRawBuild() >> GroovyMock(AbstractBuild) {
        getEnvironment(_) >> { TaskListener listener ->
          EnvVars envVars = new EnvVars(['JENKINS_URL': 'http://dummies.org'])
          return envVars
        }
      }
      getCurrentResult() >> buildResult
      getAbsoluteUrl() >> 'jenkins.url'
    })

    Map calledArgs = null
    Map expectedArgs = [
        httpMode          : 'POST',
        acceptType        : 'APPLICATION_JSON',
        contentType       : 'APPLICATION_JSON',
        requestBody       : '{\n' +
            '      "@type": "MessageCard",\n' +
            '      "@context": "http://schema.org/extensions",\n' +
            '      "summary": " - ' + buildResult + '",\n' +
            '      "themeColor": "' + color + '",\n' +
            '      "sections": [{"activityTitle":"<a href=\'jenkins.url\'> #${BUILD_NUMBER}</a>","activitySubtitle":"Duration \\u23f1\\ufe0f 0s","activityImage":"https://avatars.githubusercontent.com/u/1022787?s=120&v=4","facts":[{"name":"Status","value":"' + emoji + ' ' + buildResult + '"}],"markdown":true}],\n' +
            '      "potentialAction": []\n' +
            '    }',
        url               : 'webhook.url',
        validResponseCodes: '200:404'
    ]
    registerAllowedMethod("withCredentials", [List, Closure], null)
    registerAllowedMethod("string", [Map], null)
    registerAllowedMethod("httpRequest", [Map], { Map args -> calledArgs = args })

    MSTeamsReport report = new MSTeamsReport(mockScript)
    configRule.buildData([
        MS_TEAMS_INTEGRATION: true,
        MS_TEAMS_CHANNEL    : 'webhook-creds-id'
    ])
    report.build(configRule.buildData)

    when:
    report.send()

    then:
    calledArgs == expectedArgs

    where:
    buildResult | color     | emoji
    'SUCCESS'   | '#29AF5D' | '\\ud83d\\ude0e'
    'UNSTABLE'  | '#FFFF00' | '\\ud83d\\ude28'
    'FAILURE'   | '#F44336' | '\\ud83d\\ude21'
    'NOT_BUILT' | '#838282' | '\\ud83d\\ude28'
    'ABORTED'   | '#838282' | '\\ud83d\\ude28'

  }

  def "test sending to multiple channels"() {
    setup: 'setting 2 channels webhooks'
    mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
      getRawBuild() >> GroovyMock(AbstractBuild) {
        getEnvironment(_) >> { TaskListener listener ->
          EnvVars envVars = new EnvVars(['JENKINS_URL': 'http://dummies.org'])
          return envVars
        }
      }
      getCurrentResult() >> 'SUCCESS'
      getAbsoluteUrl() >> 'jenkins.url'
    })
    registerAllowedMethod("withCredentials", [List, Closure], null)
    registerAllowedMethod("string", [Map], null)
    registerAllowedMethod("httpRequest", [Map], null)

    MSTeamsReport report = Spy(new MSTeamsReport(mockScript))
    configRule.buildData([
        MS_TEAMS_INTEGRATION: true,
        MS_TEAMS_CHANNEL    : [
            BUILD_FAILURE : 'qat-teams-webhook,buildteam-alerts-teams-webhook',
            BUILD_ABORTED : 'qat-teams-webhook,buildteam-alerts-teams-webhook',
            BUILD_UNSTABLE: 'qat-teams-webhook,buildteam-alerts-teams-webhook',
            BUILD_SUCCESS : 'qat-teams-webhook,buildteam-alerts-teams-webhook'
        ]

    ])
    report.build(configRule.buildData)

    when: 'run the send method'
    report.send()

    then: 'msTeamsNotification method must be called twice'
    2 * report.msTeamsNotification(_ as String)
  }

  def "test sending when channels are defined as something unxpected"() {
    setup:
    mockScript.binding.setVariable('currentBuild', GroovyMock(RunWrapper) {
      getRawBuild() >> GroovyMock(AbstractBuild) {
        getEnvironment(_) >> { TaskListener listener ->
          EnvVars envVars = new EnvVars(['JENKINS_URL': 'http://dummies.org'])
          return envVars
        }
      }
      getCurrentResult() >> 'SUCCESS'
      getAbsoluteUrl() >> 'jenkins.url'
    })
    registerAllowedMethod("withCredentials", [List, Closure], null)
    registerAllowedMethod("string", [Map], null)
    registerAllowedMethod("httpRequest", [Map], null)

    MSTeamsReport report = Spy(new MSTeamsReport(mockScript))
    configRule.buildData([
        MS_TEAMS_INTEGRATION: true,
        MS_TEAMS_CHANNEL    : ['qat-teams-webhook,buildteam-alerts-teams-webhook','qat-teams-webhook,buildteam-alerts-teams-webhook']

    ])
    report.build(configRule.buildData)

    when:
    report.send()

    then:
    0 * report.msTeamsNotification(_ as String)

  }
}