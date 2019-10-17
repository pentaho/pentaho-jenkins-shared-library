/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.report

import com.cloudbees.groovy.cps.NonCPS
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.ScmUtils
import org.hitachivantara.ci.config.BuildData

import static org.hitachivantara.ci.StringUtils.formatDuration
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_AUDIT
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_BUILD
import static org.hitachivantara.ci.config.LibraryProperties.TAG_NAME

class LogReport implements Report {

  Script steps

  int limit
  int indent
  String report
  List expandStageTimingsFor

  LogReport(Script steps, expandStageTimingsFor = [STAGE_LABEL_BUILD, STAGE_LABEL_AUDIT], int indent = 2, int limit = 0) {
    this.steps = steps
    this.limit = limit
    this.indent = indent
    this.expandStageTimingsFor = expandStageTimingsFor
  }

  Report build(BuildData buildData) {
    BuildStatus buildStatus = buildData.buildStatus

    StringBuilder sb = new StringBuilder()

    sb << buildStatusString('Errors', buildStatus.getErrors())
    sb << '\n'
    sb << buildStatusString('Warnings', buildStatus.getWarnings())
    sb << '\n'
    sb << buildTimingString('Timings', buildStatus.getTimings())
    sb << '\n'
    sb << buildStatusReleasesString(buildStatus.getReleases())

    report = sb.toString()
    this
  }

  void send() {
    steps.echo report
  }

  String buildStatusString(String title, Map statusData) {
    StringBuilder sb = new StringBuilder()
    sb << "${title}:"

    if (statusData) {
      statusData.each { String stage, Map<String, ?> allMessages ->
        sb << '\n' << ' ' * indent
        sb << "[${stage}]"

        Map stageJobItemMessages = allMessages.get(BuildStatus.Category.JOB) as Map
        printStageJobMessages(sb, stageJobItemMessages)

        List<String> generalMessages = allMessages.get(BuildStatus.Category.GENERAL) as List<String>
        if (generalMessages) {
          generalMessages.each {
            String generalMessage ->
              sb << '\n' << ' ' * indent * 2
              sb << generalMessage
          }
        }
      }
    } else {
      sb << '\n' << ' ' * indent
      sb << "No ${title.toLowerCase()}"
    }

    return sb.toString()
  }

  private void printStageJobMessages(StringBuilder sb, Map stageJobItemMessages) {
    if (stageJobItemMessages) {
      Map messagesToList = (limit ? stageJobItemMessages.take(limit) : stageJobItemMessages)
      Set<?> keys = messagesToList.keySet()

      keys.each { item ->
        // print jobID
        sb << '\n' << ' ' * indent * 2
        sb << '- ' << getId(item)

        if (item instanceof JobItem) {
          // print branch also
          sb << ' @ ' << item.scmBranch
          printCommitLogs(sb, item)
        }

      }
      if (limit && stageJobItemMessages.size() > limit) {
        sb << '\n' << ' ' * indent * 2
        sb << '(...)'
      }
    }
  }

  private void printCommitLogs(StringBuilder sb, JobItem jobItem) {
    List<Map<String, Object>> commitLogs
    steps.dir(jobItem.checkoutDir) {
      commitLogs = ScmUtils.getCommitLog(steps, jobItem)
    }

    commitLogs.each { Map changelog ->
      // print commit log
      sb << '\n' << ' ' * indent * 4
      sb << '- ' << (changelog[ScmUtils.COMMIT_URL] ?: jobItem.scmUrl)
      sb << '\n' << ' ' * indent * 4
      sb << '  ' << changelog[ScmUtils.COMMIT_TITLE]
    }
  }

  String buildTimingString(String title, Map timingData) {
    StringBuilder sb = new StringBuilder()
    sb << "${title}:"

    if (timingData) {
      int longestStageName = timingData.keySet().collect { String stage -> stage.size() }.max()

      timingData.each { String stage, Map<String, ?> allTimings ->
        List generalTimings = allTimings.get(BuildStatus.Category.GENERAL) as List<Long>
        Long stageTiming = generalTimings ? generalTimings.sum() : 0

        // stage has 0 time, it was skipped
        if (!stageTiming) return

        sb << '\n' << ' ' * indent
        sb << "[${stage}]"
        sb << spacer(stage.size(), longestStageName)
        sb << "(${formatDuration(stageTiming)})"

        final Map jobTimings = allTimings.get(BuildStatus.Category.JOB) as Map
        int topLimit = 5

        if (jobTimings && stage in expandStageTimingsFor) {
          List jobTimingEntries = jobTimings.entrySet() as List
          jobTimingEntries.sort(descending)

          List shownJobTimingEntries = jobTimingEntries.take(topLimit)
          int longestJobID = shownJobTimingEntries.collect { Map.Entry<?, Long> timing ->
            getId(timing.key).size()
          }.max()

          shownJobTimingEntries.each { Map.Entry<?, Long> timing ->
            String id = getId(timing.key)
            Long duration = timing.value

            sb << '\n' << ' ' * indent * 2
            sb << id
            sb << spacer(id.size(), longestJobID) << ': '
            sb << "${formatDuration(duration)}"
          }
          if (jobTimingEntries.size() > topLimit) {
            sb << '\n' << ' ' * indent * 2
            sb << "(top ${topLimit})"
          }
        }
      }
    } else {
      sb << '\n' << ' ' * indent
      sb << "No ${title.toLowerCase()}"
    }

    return sb.toString()
  }

  static String getId(item) {
    if (item instanceof JobItem) return item.jobID
    return item.toString()
  }

  static Comparator<Map.Entry> descending = new Comparator<Map.Entry>() {
    @NonCPS
    int compare(Map.Entry o1, Map.Entry o2) {
      o2.value <=> o1.value
    }
  }

  static String spacer(int current, int longest) {
    ' ' * (longest - current + 1)
  }

  String buildStatusReleasesString(Map statusData) {
    String title = 'Releases'
    StringBuilder sb = new StringBuilder()
    sb << "${title}:"

    if (statusData) {
      statusData.each { String stage, Map<String, ?> allMessages ->
        sb << '\n' << ' ' * indent
        sb << "[${BuildData.instance.getString(TAG_NAME)}]"

        List<Map> generalMessages = allMessages.get(BuildStatus.Category.GENERAL) as List<Map>
        if (generalMessages) {
          generalMessages.each {Map item ->
              String generalMessage = item.label
              if( item.link ) {
                generalMessage = item.link
              }
              sb << '\n' << ' ' * indent * 2
              sb << generalMessage
          }
        }
      }
    } else {
      sb << '\n' << ' ' * indent
      sb << "No ${title.toLowerCase()}"
    }

    return sb.toString()
  }
}
