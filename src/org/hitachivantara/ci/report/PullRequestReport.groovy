/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.report

import hudson.model.Run
import hudson.tasks.test.AbstractTestResultAction
import org.hitachivantara.ci.StringUtils
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.github.GitHubManager
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

class PullRequestReport implements Report {

  String report
  Script steps

  PullRequestReport(Script steps) {
    this.steps = steps
  }

  @Override
  Report build(BuildData buildData) {
    RunWrapper build = steps.currentBuild
    Run raw = build.getRawBuild()
    AbstractTestResultAction testResultAction = raw.getAction(AbstractTestResultAction.class)

    String template = steps.libraryResource resource: "templates/pull-request-report.vm", encoding: 'UTF-8'
    Map bindings = [
      command : StringUtils.wordWrap(buildData.executionCommand),
      duration: StringUtils.formatDuration(build.duration),
      hasTests: testResultAction != null,
    ]

    if (testResultAction) {
      bindings += [
        absoluteUrl: "${build.absoluteUrl}${testResultAction.getUrlName()}",
        failCount  : testResultAction.failCount,
        failedTests: testResultAction.failedTests,
        totalCount : testResultAction.totalCount,
        skipCount  : testResultAction.skipCount,
      ]
    }

    report = steps.resolveTemplate(
      text: template,
      parameters: bindings
    )
    this
  }

  @Override
  void send() {
    steps.log.debug 'Resolved report:', report
    GitHubManager.commentPullRequest(report)
  }
}
