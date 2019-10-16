package org.hitachivantara.ci.report

import groovy.json.JsonOutput
import hudson.tasks.junit.TestResultAction
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.ScmUtils
import org.hitachivantara.ci.StringUtils
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.jenkins.JobUtils
import org.hitachivantara.ci.jenkins.MinionHandler
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import org.hitachivantara.ci.report.BuildStatus.Category

import static org.hitachivantara.ci.StringUtils.formatDuration
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_PLAN_ID
import static org.hitachivantara.ci.config.LibraryProperties.RELEASE_BUILD_NUMBER
import static org.hitachivantara.ci.config.LibraryProperties.SLACK_CHANNEL
import static org.hitachivantara.ci.config.LibraryProperties.SLACK_CREDENTIALS_ID
import static org.hitachivantara.ci.config.LibraryProperties.SLACK_INTEGRATION
import static org.hitachivantara.ci.config.LibraryProperties.SLACK_TEAM_DOMAIN
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_UNIT_TEST
import static org.hitachivantara.ci.config.LibraryProperties.TAG_NAME

class SlackReport implements Report {

  Script dsl
  BuildData buildData

  List attachments = []

  static final Map<String, String> emojis = [
      BUILD_SUCCESS  : ':sunglasses:',
      BUILD_UNSTABLE : ':fearful:',
      BUILD_FAILURE  : ':angry:',
      BUILD_NOT_BUILT: ':fearful:',
      BUILD_ABORTED  : ':fearful:',
      TEST_FAIL      : ':-1:',
      TEST_PASS      : ':ok_hand:',
      DURATION       : ':clock2:'
  ]

  static final Map<String, String> colors = [
      BUILD_SUCCESS  : 'good',
      BUILD_UNSTABLE : 'warning',
      BUILD_FAILURE  : 'danger',
      BUILD_NOT_BUILT: '#838282',
      BUILD_ABORTED  : '#838282',
      REPORT_WARNINGS: 'warning',
      REPORT_ERRORS  : 'danger',
      REPORT_RELEASES: 'good'
  ]

  SlackReport(Script dsl) {
    this.dsl = dsl
  }

  SlackReport build(BuildData buildData) {
    this.buildData = buildData

    // do nothing if slack integration is not enabled
    if (!buildData.getBool(SLACK_INTEGRATION)){
      return this
    }

    BuildStatus buildStatus = buildData.buildStatus
    attachments << buildMainAttach()

    if (buildStatus.hasErrors()) {
      attachments << buildStatusAttach(':no_entry: *Errors*', colors.REPORT_ERRORS, buildStatus.errors)
    }
    if (buildStatus.hasWarnings()) {
      attachments << buildStatusAttach(':warning: *Warnings*', colors.REPORT_WARNINGS, buildStatus.warnings)
    }
    if (buildStatus.hasReleases()) {
      attachments << buildStatusReleasesAttach(buildStatus.releases)
    }

    return this
  }

  void send() {
    if (buildData.getBool(SLACK_INTEGRATION)) {
      String channels

       switch (buildData.get(SLACK_CHANNEL)) {
        case String:  // default list of channels configured
          channels = buildData.getString(SLACK_CHANNEL)
          break

         case Map:     // channels configured per build result
          RunWrapper build = dsl.currentBuild
          Map channelConfig = buildData.get(SLACK_CHANNEL)
          channels = channelConfig['BUILD_' + build.currentResult]
          break

         default: // no channels
        channels = ''
      }

      dsl.slackSend(
          channel: channels,
          teamDomain: buildData.getString(SLACK_TEAM_DOMAIN),
          tokenCredentialId: buildData.getString(SLACK_CREDENTIALS_ID),
          failOnError: false,
          attachments: JsonOutput.toJson(attachments)
      )
    }
  }

  private Map buildMainAttach() {
    RunWrapper build = dsl.currentBuild

    String buildLabel = "${buildData.getString(BUILD_PLAN_ID)} #${buildData.getString(RELEASE_BUILD_NUMBER)}"
    String buildTitle = "<${build.absoluteUrl}|${buildLabel}>"
    String color = colors['BUILD_' + build.currentResult]

    List fields = []

    String buildEmoji = emojis['BUILD_' + build.currentResult]
    String buildStatus = "${buildEmoji} ${build.currentResult}"
    String buildDuration = "${emojis.DURATION} ${formatDuration(build.duration)}"

    fields << [
        title: 'Status',
        value: buildStatus,
        short: true
    ]
    fields << [
        title: 'Duration',
        value: buildDuration,
        short: true
    ]

    // try to grab test information
    TestResultAction testAction = build.rawBuild.getAction(TestResultAction.class)

    if (testAction != null) {
      String testLabel = "${testAction.failCount ? testAction.failCount + ' failed' : 'all passed'}"
      String testEmoji = emojis['TEST_' + (testAction.failCount ? 'FAIL' : 'PASS')]

      String testStatus = "${testEmoji} <${build.absoluteUrl}/testReport|${testLabel}>"

      Long testStageDuration = buildData.buildStatus.getStageDuration(STAGE_LABEL_UNIT_TEST) ?: 0l
      String testDuration = "${emojis.DURATION} ${formatDuration(testStageDuration)}"

      fields << [
          title: 'Tests',
          value: testStatus,
          short: true
      ]
      fields << [
          title: 'Duration',
          value: testDuration,
          short: true
      ]
    }

    return [
        pretext    : buildTitle,
        color      : color,
        fields     : fields
    ]
  }

  private Map buildStatusAttach(String title, String color, Map statusData) {
    Map attachment = [
        pretext  : title,
        color    : color,
        mrkdwn_in: ['pretext']
    ]

    attachment['fields'] = statusData.collect { String stage, Map items ->
      StringBuilder sb = new StringBuilder()

      items[Category.GENERAL].each { String message ->
        sb << '- '
        sb << message
        sb << '\n'
      }

      if (items[Category.GENERAL] && items[Category.JOB]) sb << '\n'

      List<Map<String,Object>> commitLogs
      items[Category.JOB].each { JobItem jobItem, Throwable e ->
        dsl.dir(jobItem.checkoutDir) {
          commitLogs = ScmUtils.getCommitLog(dsl, jobItem)
        }

        // print jobID and branch
        sb << '- ' << getJobReference(jobItem) << " (${jobItem.scmInfo.organization}/${jobItem.scmInfo.repository} @ ${jobItem.scmBranch})"

        commitLogs.each { Map<String,String> changelog ->
          // print commit log
          String commitUrl = changelog[ScmUtils.COMMIT_URL] ?: jobItem.scmUrl
          sb << '\n' << ' ' * 5
          sb << "<${commitUrl}|${changelog[ScmUtils.COMMIT_ID].take(7)}>"
          sb << ' - ' << StringUtils.truncate(changelog[ScmUtils.COMMIT_TITLE], 55)
        }
        sb << '\n'
      }

      return [
          title: stage,
          value: sb.toString(),
          short: false
      ]
    }

    return attachment
  }

  /**
   * sets a link to a minion job if running builds with minions
   * @param jobItem
   * @return
   */
  private String getJobReference(JobItem jobItem) {
    String title = jobItem.jobID

    if (buildData.isUseMinions()) {
      RunWrapper minionBuild = JobUtils.getLastBuildJob(MinionHandler.getFullJobName(jobItem))
      title = "<${minionBuild.absoluteUrl}|${title}>"
    }
    return title
  }
\
  private Map buildStatusReleasesAttach(Map releasesStatusData) {
    int limit = 5

    Map attachment = [
      pretext  : ':label: *Releases*',
      color    : colors.REPORT_RELEASES,
      mrkdwn_in: ['pretext']
    ]

    List releaseItems = releasesStatusData.collectEntries { String stage, Map items -> items }.values().flatten()
    if (releaseItems.size() > limit ){
      String releaseName = buildData.getString(TAG_NAME)
      StringBuilder releaseMsg = new StringBuilder("Release *${releaseName}*: ")

      List allReleasableJobItems = buildData.buildMap.collectMany {
        String key, List value -> value.findAll { JobItem ji -> ji.createRelease }
      }

      if ( allReleasableJobItems.size() != releaseItems.size() ) {
        releaseMsg << 'Not all repos were released! See the logs for further information.'
      } else {
        releaseMsg << ' created for all repos!'
      }

      attachment['fields'] = [
        title: buildData.getString(TAG_NAME),
        value: releaseMsg.toString(),
        short: false
      ]
    } else {
      attachment['fields'] = releasesStatusData.collect { String stage, Map items ->
        StringBuilder sb = new StringBuilder()

        items[Category.GENERAL].each { Map item ->
          String message = item.label

          if ( item.link ) {
            message = "<${item.link}|${item.label}>"
          }

          sb << '- '
          sb << message
          sb << '\n'
        }
        return [
          title: buildData.getString(TAG_NAME),
          value: sb.toString(),
          short: false
        ]
      }
    }

    return attachment
  }

}