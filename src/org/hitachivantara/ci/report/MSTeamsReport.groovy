package org.hitachivantara.ci.report

import groovy.json.JsonOutput
import hudson.model.TaskListener
import hudson.tasks.junit.TestResultAction
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.ScmUtils
import org.hitachivantara.ci.StringUtils
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.jenkins.JobUtils
import org.hitachivantara.ci.jenkins.MinionHandler
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

import static org.hitachivantara.ci.StringUtils.formatDuration
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_PLAN_ID
import static org.hitachivantara.ci.config.LibraryProperties.MS_TEAMS_CHANNEL
import static org.hitachivantara.ci.config.LibraryProperties.MS_TEAMS_INTEGRATION
import static org.hitachivantara.ci.config.LibraryProperties.RELEASE_BUILD_NUMBER
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_UNIT_TEST
import static org.hitachivantara.ci.config.LibraryProperties.TAG_NAME

class MSTeamsReport implements Report {

  static final Map<String, String> emojis = [
      BUILD_SUCCESS  : '😎',
      BUILD_UNSTABLE : '😨',
      BUILD_FAILURE  : '😡',
      BUILD_NOT_BUILT: '😨',
      BUILD_ABORTED  : '😨',
      TEST_FAIL      : '👎',
      TEST_PASS      : '👍',
      DURATION       : '⏱️'
  ]

  static final Map<String, String> colors = [
      BUILD_SUCCESS  : '#29AF5D',
      BUILD_UNSTABLE : '#28A9DD',
      BUILD_FAILURE  : '#F44336',
      BUILD_NOT_BUILT: '#838282',
      BUILD_ABORTED  : '#838282',
      REPORT_WARNINGS: '#28A9DD',
      REPORT_ERRORS  : '#F44336',
      REPORT_RELEASES: '#29AF5D'
  ]

  List sections = []
  BuildData buildData
  Script dsl

  MSTeamsReport(Script dsl) {
    this.dsl = dsl
  }

  MSTeamsReport build(BuildData buildData) {
    this.buildData = buildData

    // do nothing if ms teams integration is not enabled
    if (!buildData.getBool(MS_TEAMS_INTEGRATION)) {
      return this
    }

    BuildStatus buildStatus = buildData.buildStatus

    if (buildStatus.hasBranchStatus()) {
      sections = buildBranchStatusAttach(buildStatus)
    } else {
      sections << buildMainAttach()

      if (buildStatus.hasErrors()) {
        sections << buildStatusAttach('⛔ *Errors*', buildStatus.errors)
      }
      if (buildStatus.hasWarnings()) {
        sections << buildStatusAttach('⚠ *Warnings*', buildStatus.warnings)
      }
      if (buildStatus.hasReleases()) {
        sections << buildStatusReleasesAttach(buildStatus.releases)
      }
    }
    return this
  }

  void send() {

    if (buildData.getBool(MS_TEAMS_INTEGRATION)) {
      String webHookCredentialNames
      switch (buildData.get(MS_TEAMS_CHANNEL)) {
        case String:  // default list of channels configured
          webHookCredentialNames = buildData.getString(MS_TEAMS_CHANNEL)
          break

        case Map:     // channels configured per build result
          RunWrapper build = dsl.currentBuild
          Map webHookCredentialNamesConfig = buildData.get(MS_TEAMS_CHANNEL) as Map
          webHookCredentialNames = webHookCredentialNamesConfig['BUILD_' + build.currentResult]
          break

        default: // no channels
          webHookCredentialNames = null
      }

      webHookCredentialNames?.split(',')?.each { String webHookCredentialName ->
        msTeamsNotification(webHookCredentialName)
      }
    }

  }

  private List buildBranchStatusAttach(BuildStatus buildStatus) {
    RunWrapper build = dsl.currentBuild
    String hostUrl = build.rawBuild.getEnvironment(dsl.getContext(TaskListener.class) as TaskListener).get('JENKINS_URL')
    List branchesStatus = []
    Map branchesData = buildStatus.branchStatus.collectEntries { String stage, Map items -> items }.values().flatten().first() as Map
    branchesData.keySet().each { String branch ->
      Map branchData = branchesData[branch]

      String buildLabel = "${branch} branch health check"
      String buildTitle = "<a href='${hostUrl}'>${buildLabel}</a>"

      List facts = []

      String buildEmoji = emojis['BUILD_' + branchData['status']]
      String buildStatusValue = "${buildEmoji} ${branchData['status']}"
      String failingTestsValue = "${(branchData['failing-tests'] > 0 ? emojis.TEST_FAIL : emojis.TEST_PASS)} ${branchData['failing-tests']}"

      facts << [
          name: 'Status',
          value: buildStatusValue
      ]

      facts << [
          name: 'Failing tests',
          value: failingTestsValue
      ]

      facts << [
          name: 'Open pull requests 🔀',
          value: (branchData['pull-requests'] as Map).collect { "- ${it.key}: ${it.value}" }.join('\n')
      ]

      branchesStatus << [
          activityTitle: buildTitle,
          facts     : facts
      ]
    }
    return branchesStatus
  }

  private String getJobReference(JobItem jobItem) {
    String title = jobItem.jobID

    if (buildData.isUseMinions()) {
      RunWrapper minionBuild = JobUtils.getLastBuildJob(MinionHandler.getFullJobName(jobItem))
      title = "<a href='${minionBuild.absoluteUrl}'>${title}</a>"
    }
    return title
  }

  private Map buildStatusAttach(String title, Map statusData) {
    Map sections = [
        activityTitle: title,
        "markdown"   : true
    ]

    sections['facts'] = statusData.collect { String stage, Map items ->
      StringBuilder sb = new StringBuilder()

      items[BuildStatus.Category.GENERAL].each { String message ->
        sb << '- '
        sb << message
        sb << '\n'
      }

      if (items[BuildStatus.Category.GENERAL] && items[BuildStatus.Category.JOB]) sb << '\n'

      List<Map<String, Object>> commitLogs
      items[BuildStatus.Category.JOB].each { JobItem jobItem, Throwable e ->
        dsl.dir(jobItem.checkoutDir) {
          commitLogs = ScmUtils.getCommitLog(dsl, jobItem)
        }

        // print jobID and branch
        sb << '- ' << getJobReference(jobItem) << " (${jobItem.scmInfo.organization}/${jobItem.scmInfo.repository} @ ${jobItem.scmBranch})"

        commitLogs.each { Map<String, String> changelog ->
          // print commit log
          String commitUrl = changelog[ScmUtils.COMMIT_URL] ?: jobItem.scmUrl
          sb << '\n' << ' ' * 5
          sb << "<a href='${commitUrl}'>${changelog[ScmUtils.COMMIT_ID].take(7)}</a>"
          sb << ' - ' << StringUtils.truncate(changelog[ScmUtils.COMMIT_TITLE], 55)
        }
        sb << '\n'
      }

      return [
          name : stage,
          value: sb.toString()
      ]
    }

    return sections
  }

  private Map buildStatusReleasesAttach(Map releasesStatusData) {
    int limit = 5

    Map section = [
        activityTitle: '🏷 *Releases*',
        "markdown"   : true
    ]

    List releaseItems = releasesStatusData.collectEntries { String stage, Map items -> items }.values().flatten()
    if (releaseItems.size() > limit) {
      String releaseName = buildData.getString(TAG_NAME)
      StringBuilder releaseMsg = new StringBuilder("Release *${releaseName}*: ")

      List allReleasableJobItems = buildData.buildMap.collectMany {
        String key, List value -> value.findAll { JobItem ji -> ji.createRelease }
      }

      if (allReleasableJobItems.size() != releaseItems.size()) {
        releaseMsg << 'Not all repos were released! See the logs for further information.'
      } else {
        releaseMsg << ' created for all repos!'
      }

      section['facts'] = [
          name : buildData.getString(TAG_NAME),
          value: releaseMsg.toString()
      ]
    } else {
      section['facts'] = releasesStatusData.collect { String stage, Map items ->
        StringBuilder sb = new StringBuilder()

        items[BuildStatus.Category.GENERAL].each { Map item ->
          String message = item.label

          if (item.link) {
            message = "<a href='${item.link}'>${item.label}</a>"
          }

          sb << '- '
          sb << message
          sb << '\n'
        }
        return [
            name : buildData.getString(TAG_NAME),
            value: sb.toString()
        ]
      }
    }

    return section
  }

  private Map buildMainAttach() {
    RunWrapper build = dsl.currentBuild

    String buildLabel = "${buildData.getString(BUILD_PLAN_ID)} #${buildData.getString(RELEASE_BUILD_NUMBER)}"
    String buildTitle = "<a href='${build.absoluteUrl}'>${buildLabel}</a>"

    List facts = []

    String buildEmoji = emojis['BUILD_' + build.currentResult]
    String buildStatus = "${buildEmoji} ${build.currentResult}"
    String buildDuration = "${emojis.DURATION} ${formatDuration(build.duration)}"

    facts << [
        name : 'Status',
        value: buildStatus
    ]
//    facts << [
//        name : 'Duration',
//        value: buildDuration
//    ]

    // try to grab test information
    TestResultAction testAction = build.rawBuild.getAction(TestResultAction.class)

    if (testAction != null) {
      String testLabel = "${testAction.failCount ? testAction.failCount + ' failed' : 'all passed'}"
      String testEmoji = emojis['TEST_' + (testAction.failCount ? 'FAIL' : 'PASS')]

      String testStatus = "${testEmoji} <a href='${build.absoluteUrl}/testReport'>${testLabel}</a>"

      Long testStageDuration = buildData.buildStatus.getStageDuration(STAGE_LABEL_UNIT_TEST) ?: 0l
      String testDuration = "${emojis.DURATION} ${formatDuration(testStageDuration)}"

      facts << [
          name : 'Tests',
          value: "$testStatus ($testDuration)"
      ]
//      facts << [
//          name : 'Duration',
//          value: testDuration
//      ]
    }

    return [
        activityTitle: buildTitle,
        activitySubtitle: "Duration $buildDuration",
        activityImage: "https://avatars.githubusercontent.com/u/1022787?s=120&v=4",
        facts        : facts,
        markdown     : true
    ]
  }

  /**
   * Send a notification to MS Teams
   * @param webHookCredentialName the name of the credential to use
   */
  void msTeamsNotification(String webHookCredentialName) {
    String summary = buildData.getString('BUILD_PLAN_ID')
    String buildStatus = dsl.currentBuild.currentResult ?: "N/A"
    String bldStatusColor = colors['BUILD_' + buildStatus]

    String sections = JsonOutput.toJson(sections).toString()
    def json_payload = """{
      "@type": "MessageCard",
      "@context": "http://schema.org/extensions",
      "summary": "$summary - $buildStatus",
      "themeColor": "${bldStatusColor}",
      "sections": $sections,
      "potentialAction": []
    }""".stripIndent()

    dsl.withCredentials([
        dsl.string(credentialsId: webHookCredentialName, variable: 'TEAMS_WEBHOOK')]) {
      dsl.httpRequest(
          httpMode: 'POST',
          acceptType: 'APPLICATION_JSON',
          contentType: 'APPLICATION_JSON',
          url: "${dsl.env.TEAMS_WEBHOOK}",
          requestBody: json_payload,
          validResponseCodes: '200:404'
      )
    }
  }


}
