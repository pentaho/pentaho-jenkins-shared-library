/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.report.LogReport
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.JenkinsLoggingRule
import org.hitachivantara.ci.utils.ReplacePropertyRule
import org.hitachivantara.ci.utils.Rules
import org.junit.Rule
import org.junit.rules.RuleChain

import static org.hitachivantara.ci.config.LibraryProperties.STAGE_NAME
import static org.hitachivantara.ci.config.LibraryProperties.TAG_NAME

class TestLogReport extends BasePipelineSpecification {
  JenkinsLoggingRule loggingRule = new JenkinsLoggingRule(this)
  ConfigurationRule configRule = new ConfigurationRule(this)
  ReplacePropertyRule scmUtilsMetaClass = new ReplacePropertyRule()

  @Rule RuleChain rules = Rules.getCommonRules(this)
    .around(loggingRule)
    .around(configRule)
    .around(scmUtilsMetaClass)

  def "test that report log does not throw errors"() {
    setup:
      scmUtilsMetaClass.addReplacement(ScmUtils, ['static.getCommitLog': { Script s, JobItem j ->
        [[
           (ScmUtils.COMMIT_TITLE) : 'TITLE',
           (ScmUtils.COMMIT_AUTHOR): 'AUTHOR',
         ]]
      }])
      LogReport report = new LogReport(mockScript)
      BuildData buildData = configRule.buildData

      buildData.error('Versions', 'General error message 1')
      buildData.warning('Versions', 'General error message 2')

      buildData.error('Build', 'General error message 3')
      buildData.error('Build', 'General error message 4')
      buildData.error('Build', configRule.newJobItem([jobID: 'job1', scmUrl: 'git@git:org/repo-1.git']), null)
      buildData.error('Build', configRule.newJobItem([jobID: 'job2', scmUrl: 'git@git:org/repo-2.git']), null)

      buildData.warning('Test', configRule.newJobItem([jobID: 'job3', scmUrl: 'git@git:org/repo-3.git']), null)
      buildData.error('Test', configRule.newJobItem([jobID: 'job4', scmUrl: 'git@git:org/repo-4.git']), null)

    when:
      report.build(buildData)
      report.send()

    then:
      noExceptionThrown()

    and:
      loggingRule.lines[0] == 'Errors:\n' +
        '  [Versions]\n' +
        '    General error message 1\n' +
        '  [Build]\n' +
        '    - job1 @ master\n' +
        '        - git@git:org/repo-1.git\n' +
        '          TITLE\n' +
        '    - job2 @ master\n' +
        '        - git@git:org/repo-2.git\n' +
        '          TITLE\n' +
        '    General error message 3\n' +
        '    General error message 4\n' +
        '  [Test]\n' +
        '    - job4 @ master\n' +
        '        - git@git:org/repo-4.git\n' +
        '          TITLE\n' +
        'Warnings:\n' +
        '  [Versions]\n' +
        '    General error message 2\n' +
        '  [Test]\n' +
        '    - job3 @ master\n' +
        '        - git@git:org/repo-3.git\n' +
        '          TITLE\n' +
        'Timings:\n' +
        '  No timings\n' +
        'Releases:\n' +
        '  No releases'

  }


  def "test that report log contains timings"() {
    setup:
      LogReport report = new LogReport(mockScript)

      BuildData buildData = configRule.buildData

      buildData.buildProperties[STAGE_NAME] = 'Versions'
      buildData.time(100000)

      buildData.buildProperties[STAGE_NAME] = 'Build'
      buildData.time(2000000 + 300000 + 350000)
      buildData.time(configRule.newJobItem([jobID: 'job1', scmUrl: 'git@git:org/repo-2.git']), 300000)
      buildData.time(configRule.newJobItem([jobID: 'job20', scmUrl: 'git@git:org/repo-2.git']), 350000)
      buildData.time(configRule.newJobItem([jobID: 'job300', scmUrl: 'git@git:org/repo-1.git']), 2000000)

    when:
      report.build(buildData)
      report.send()

    then:
      noExceptionThrown()

    and:
      loggingRule.lines[0] ==
        'Errors:' + '\n' +
        '  No errors' + '\n' +
        'Warnings:' + '\n' +
        '  No warnings' + '\n' +
        'Timings:' + '\n' +
        '  [Versions] (1m 40s)' + '\n' +
        '  [Build]    (44m 10s)' + '\n' +
        '    job300 : 33m 20s' + '\n' +
        '    job20  : 5m 50s' + '\n' +
        '    job1   : 5m\n' +
        'Releases:\n' +
        '  No releases'
  }

  def "test log contains custom items"() {
    setup:
      LogReport report = new LogReport(mockScript)

      configRule.time('Audit', 100000)
      configRule.time('Audit', 'my custom item ID', 100000)

    when:
      report.build(configRule.buildData)
      report.send()
    then:
      loggingRule.lines[0] ==
        'Errors:\n' +
        '  No errors\n' +
        'Warnings:\n' +
        '  No warnings\n' +
        'Timings:\n' +
        '  [Audit] (1m 40s)\n' +
        '    my custom item ID : 1m 40s\n' +
        'Releases:\n' +
        '  No releases'
  }

  def "test that report log should contain 'releases'"() {
    setup:
    LogReport report = new LogReport(mockScript)

    BuildData buildData = configRule.buildData

    buildData.buildProperties[STAGE_NAME] = 'Build'
    buildData.time(2000000 + 300000 + 350000)
    buildData.time(configRule.newJobItem([jobID: 'job1', scmUrl: 'git@git:org/repo-2.git']), 300000)

    buildData.buildProperties[STAGE_NAME] = 'Tag'
    buildData.release([('link'): 'https://github.com/user/repo-1/releases/release-1', ('label'): 'user/repo-1'])
    buildData.release([('link'): 'https://github.com/user/repo-2/releases/release-1', ('label'): 'user/repo-2'])
    buildData.release([('link'): null, ('label'): 'No release!'])

    buildData.buildProperties[TAG_NAME] = 'release-1'

    when:
    report.build(buildData)
    report.send()

    then:
    noExceptionThrown()

    and:
    loggingRule.lines[0] ==
      'Errors:' + '\n' +
      '  No errors' + '\n' +
      'Warnings:' + '\n' +
      '  No warnings' + '\n' +
      'Timings:' + '\n' +
      '  [Build] (44m 10s)' + '\n' +
      '    job1 : 5m\n' +
      'Releases:\n' +
      '  [release-1]\n' +
      '    https://github.com/user/repo-1/releases/release-1\n' +
      '    https://github.com/user/repo-2/releases/release-1\n' +
      '    No release!'
  }
}