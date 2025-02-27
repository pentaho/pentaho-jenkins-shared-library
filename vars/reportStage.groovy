/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.report.LogReport
import org.hitachivantara.ci.report.MSTeamsReport
import org.hitachivantara.ci.report.PullRequestReport
import org.hitachivantara.ci.report.SlackReport
import org.hitachivantara.ci.report.BuildOrderReport

import static org.hitachivantara.ci.config.LibraryProperties.BUILD_ORDER_REPORT
import static org.hitachivantara.ci.config.LibraryProperties.PR_STATUS_REPORTS
import static org.hitachivantara.ci.config.LibraryProperties.SLACK_INTEGRATION
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_REPORT
import static org.hitachivantara.ci.config.LibraryProperties.MS_TEAMS_INTEGRATION

def call() {
  BuildData buildData = BuildData.instance
  stage(STAGE_LABEL_REPORT) {
    utils.handleError(
      {
        doReport(buildData)
      },
      { Throwable e ->
        log.warn "Could not generate report: ${e.message}", e
      }
    )
  }
}

void doReport(BuildData buildData) {
  Map tasks = [:]

  tasks << [
    'Log': {
      new LogReport(this)
        .build(buildData)
        .send()
    }
  ]

  if (buildData.getBool(BUILD_ORDER_REPORT)) {
    tasks << [
      'Build Order': {
        new BuildOrderReport(this)
          .build(buildData)
          .send()
      }
    ]
  }

  if (buildData.getBool(SLACK_INTEGRATION)) {
    tasks << [
      'Slack': {
        new SlackReport(this)
          .build(buildData)
          .send()
      }
    ]
  }

  if (buildData.getBool(MS_TEAMS_INTEGRATION)) {
    tasks << [
        'MS Teams': {
          new MSTeamsReport(this)
              .build(buildData)
              .send()
        }
    ]
  }

  if (buildData.isPullRequest() && buildData.getBool(PR_STATUS_REPORTS)) {
    tasks << [
      'Pull Request': {
        new PullRequestReport(this)
          .build(buildData)
          .send()
      }
    ]
  }

  parallel tasks
}

