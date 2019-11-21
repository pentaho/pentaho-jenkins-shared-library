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

  @Rule
  RuleChain rules = Rules.getCommonRules(this)
    .around(configRule)
    .around(scmUtilsMetaClass)
    .around(jobUtilsMetaClass)

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
        getRawBuild() >> GroovyMock(AbstractBuild) {
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
        getRawBuild() >> GroovyMock(AbstractBuild) {
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
        getRawBuild() >> GroovyMock(AbstractBuild)
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
        getRawBuild() >> GroovyMock(AbstractBuild) {
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
        (BuildStatus.Level.ERRORS):
          [(STAGE_LABEL_UNIT_TEST): [
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
        getRawBuild() >> GroovyMock(AbstractBuild) {
          getCauses() >> [[shortDescription: 'Build started by user']]
        }
      })

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
        (BuildStatus.Level.RELEASES):
          [(STAGE_LABEL_UNIT_TEST): [
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
      getRawBuild() >> GroovyMock(AbstractBuild) {
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
      (BuildStatus.Level.BRANCH_STATUS):
        [(STAGE_LABEL_COLLECT_JOB_DATA): [(BuildStatus.Category.GENERAL): branchStatusData as Map]]
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

}