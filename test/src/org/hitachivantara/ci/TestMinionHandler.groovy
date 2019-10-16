package org.hitachivantara.ci

import org.hitachivantara.ci.jenkins.MinionHandler
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.Rules
import org.junit.Rule
import org.junit.rules.RuleChain

import static org.hitachivantara.ci.config.LibraryProperties.BUILD_PLAN_ID
import static org.hitachivantara.ci.config.LibraryProperties.LIB_CACHE_ROOT_PATH
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

  def "Test get yaml data"() {
    JobItem jobItem = configRule.newJobItem([slackChannel: slackConfig, prSlackChannel: prSlackConfig])

    when: 'slack channel configuration gets passed through jobItem properties'
    Map data = MinionHandler.getYamlData(jobItem)

    then: 'there shouldn\'t be any errors'
    noExceptionThrown()

    and: 'the data should be passed along to the global properties SLACK_CHANNEL and PR_SLACK_CHANNEL'
    data['buildProperties']['SLACK_CHANNEL'] == slackConfig

    and:
    data['buildProperties']['PR_SLACK_CHANNEL'] == prSlackConfig

    where:
    slackConfig            | prSlackConfig
    'slack-branch-channel' | 'slack-pr-channel'
    [
      BUILD_FAILURE : 'failure-slack-channel',
      BUILD_ABORTED : 'aborted-slack-channel',
      BUILD_UNSTABLE: 'unstable-slack-channel',
      BUILD_SUCCESS : 'success-slack-channel'
    ]                      | [
      BUILD_FAILURE : 'pr-failure-slack-channel',
      BUILD_ABORTED : 'pr-aborted-slack-channel',
      BUILD_UNSTABLE: 'pr-unstable-slack-channel',
      BUILD_SUCCESS : 'pr-success-slack-channel'
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
      BRANCH_NAME          : branchName
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
    buildProperties[LIB_CACHE_ROOT_PATH] == libCacheRootPath

    and:
    buildProperties[BUILD_PLAN_ID] == buildPlanId

    and:
    buildProperties[RELEASE_BUILD_NUMBER] == releaseBuildNumber

    where:
    isMultibranchMinion | changeId | slackIntegration | slackChannel         | libCacheRootPath      | buildPlanId             | releaseBuildNumber | branchName
    true                | 1        | true             | 'a-pr-slack-channel' | '/some/path/master'   | 'a-build-plan@PR-1'     | 2                  | 'PR-1'
    true                | null     | true             | 'a-slack-channel'    | '/some/path/a-branch' | 'a-build-plan@a-branch' | 2                  | 'a-branch'
    false               | null     | null             | 'a-slack-channel'    | '/some/path/a-branch' | 'a-build-plan'          | 1                  | 'a-branch'
  }
}
