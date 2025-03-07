package org.hitachivantara.ci

import org.hitachivantara.ci.jenkins.MinionHandler
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.Rules
import org.junit.Rule
import org.junit.rules.RuleChain

import static org.hitachivantara.ci.config.LibraryProperties.BUILD_PLAN_ID
import static org.hitachivantara.ci.config.LibraryProperties.LIB_CACHE_ROOT_PATH
import static org.hitachivantara.ci.config.LibraryProperties.MS_TEAMS_CHANNEL
import static org.hitachivantara.ci.config.LibraryProperties.MS_TEAMS_INTEGRATION
import static org.hitachivantara.ci.config.LibraryProperties.RELEASE_BUILD_NUMBER
import static org.hitachivantara.ci.config.LibraryProperties.SLACK_CHANNEL
import static org.hitachivantara.ci.config.LibraryProperties.SLACK_INTEGRATION

class TestMinionHandler extends BasePipelineSpecification {
  Map buildProperties = [
    LIB_CACHE_ROOT_PATH: '/some/path',
    CHANGE_TARGET      : 'master'
  ]

  ConfigurationRule configRule = new ConfigurationRule(this)

  @Rule
  RuleChain rules = Rules.getCommonRules(this)
    .around(configRule)

  def "Test minion slack/MS Teams configuration"() {
    setup:
      JobItem jobItem = configRule.newJobItem(
        slackChannel: slackConfig,
        prSlackChannel: prSlackConfig,
        msTeamsChannel: msTeamsConfig,
        prMsTeamsChannel: prMsTeamsConfig
      )

    when: 'slack/MS Teams channel configuration gets passed through jobItem properties'
      Map data = MinionHandler.getYamlData(jobItem)

    then: 'there shouldn\'t be any errors'
      noExceptionThrown()

    and: 'the data should be passed along to the global properties SLACK_CHANNEL and PR_SLACK_CHANNEL'
      data['buildProperties']['SLACK_CHANNEL'] == slackConfig

    and:
      data['buildProperties']['PR_SLACK_CHANNEL'] == prSlackConfig

    and:
      data['buildProperties']['MS_TEAMS_CHANNEL'] == msTeamsConfig

    and:
      data['buildProperties']['PR_MS_TEAMS_CHANNEL'] == prMsTeamsConfig

    and:
      data['buildProperties']['SLACK_CHANNEL_SUCCESS'] == 'success-slack-channel'

    and:
      data['buildProperties']['MS_TEAMS_CHANNEL_SUCCESS'] == 'success-ms-teams-channel-webhook'

    where:
      slackConfig = [
          BUILD_FAILURE : 'failure-slack-channel',
          BUILD_ABORTED : 'aborted-slack-channel',
          BUILD_UNSTABLE: 'unstable-slack-channel',
          BUILD_SUCCESS : 'success-slack-channel'
      ]
      prSlackConfig = [
          BUILD_FAILURE : 'pr-failure-slack-channel',
          BUILD_ABORTED : 'pr-aborted-slack-channel',
          BUILD_UNSTABLE: 'pr-unstable-slack-channel',
          BUILD_SUCCESS : 'pr-success-slack-channel'
      ]
      msTeamsConfig = [
          BUILD_FAILURE : 'failure-ms-teams-channel-webhook',
          BUILD_ABORTED : 'aborted-ms-teams-channel-webhook',
          BUILD_UNSTABLE: 'unstable-ms-teams-channel-webhook',
          BUILD_SUCCESS : 'success-ms-teams-channel-webhook'
      ]
      prMsTeamsConfig = [
          BUILD_FAILURE : 'pr-failure-ms-teams-channel-webhook',
          BUILD_ABORTED : 'pr-aborted-ms-teams-channel-webhook',
          BUILD_UNSTABLE: 'pr-unstable-ms-teams-channel-webhook',
          BUILD_SUCCESS : 'pr-success-ms-teams-channel-webhook'
      ]
  }

  def "Test minion sanitize data"() {
    setup:
      buildProperties << [
        IS_MULTIBRANCH_MINION: isMultibranchMinion,
        CHANGE_ID            : changeId,
        SLACK_CHANNEL        : 'a-slack-channel',
        PR_SLACK_CHANNEL     : 'a-pr-slack-channel',
        BUILD_PLAN_ID        : 'a-build-plan',
        BUILD_NUMBER         : 2,
        RELEASE_BUILD_NUMBER : 1,
        BRANCH_NAME          : branchName,
        MS_TEAMS_CHANNEL     : 'a-ms-teams-channel',
        PR_MS_TEAMS_CHANNEL  : 'a-pr-ms-teams-channel',
      ]

    when:
      MinionHandler.sanitize(buildProperties)

    then:
      noExceptionThrown()

    and:
      buildProperties[SLACK_INTEGRATION] == slackIntegration

    and:
      buildProperties[SLACK_CHANNEL] == slackChannel

    and:
      buildProperties[MS_TEAMS_INTEGRATION] == msTeamsIntegration

    and:
      buildProperties[MS_TEAMS_CHANNEL] == msTeamsChannel

    and:
      buildProperties[LIB_CACHE_ROOT_PATH] == libCacheRootPath

    and:
      buildProperties[BUILD_PLAN_ID] == buildPlanId

    and:
      buildProperties[RELEASE_BUILD_NUMBER] == releaseBuildNumber

    where:
      isMultibranchMinion | changeId | slackIntegration | msTeamsIntegration | slackChannel         | msTeamsChannel          | libCacheRootPath      | buildPlanId             | releaseBuildNumber | branchName
      true                | 1        | true             | true               | 'a-pr-slack-channel' | 'a-pr-ms-teams-channel' | '/some/path/master'   | 'a-build-plan@PR-1'     | 2                  | 'PR-1'
      true                | null     | true             | true               | 'a-slack-channel'    | 'a-ms-teams-channel'    | '/some/path/a-branch' | 'a-build-plan@a-branch' | 2                  | 'a-branch'
      false               | null     | null             | null               | 'a-slack-channel'    | 'a-ms-teams-channel'    | '/some/path/a-branch' | 'a-build-plan'          | 1                  | 'a-branch'
  }

  def "Test minion yaml is created with unfiltered properties"() {
    setup:
      configRule.pipelineProperties.put('FILTERED', 'value')
      configRule.pipelineProperties.put('GLOBAL_PROPERTY', 'To be ${FILTERED}')
      JobItem jobItem = configRule.newJobItem(directives: 'install -Dbranch=${scmBranch}')

    when:
      Map data = MinionHandler.getYamlData(jobItem)

    then:
      noExceptionThrown()

    and:
      data.jobGroups.jobs[0].directives == 'install -Dbranch=${scmBranch}'
      data.buildProperties.GLOBAL_PROPERTY == 'To be ${FILTERED}'
  }

}
