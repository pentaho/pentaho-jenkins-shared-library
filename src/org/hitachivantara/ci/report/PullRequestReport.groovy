/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.report

import hudson.model.Result
import hudson.model.Run
import hudson.tasks.test.AbstractTestResultAction
import org.hitachivantara.ci.StringUtils
import org.hitachivantara.ci.build.BuilderFactory
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.github.GitHubManager
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

class PullRequestReport implements Report {

  String report
  Script steps

  static final Map<String, String> emoji = [
    SUCCESS: ':white_check_mark:',
    WARNING: ':warning:',
    FAILURE: ':x:',
  ]

  PullRequestReport(Script steps) {
    this.steps = steps
  }

  @Override
  Report build(BuildData buildData) {
    RunWrapper build = steps.currentBuild

    // Any pull request will have only one jobItem
    def builder = BuilderFactory.builderFor(buildData.allItems.first()) // TODO can still have more commands across multiple stages, adjust in the future
    Boolean failed = build.resultIsWorseOrEqualTo(Result.FAILURE.toString())
    String status = failed ? emoji.FAILURE : build.resultIsWorseOrEqualTo(Result.UNSTABLE.toString()) ? emoji.WARNING : emoji.SUCCESS

    Map bindings = [
      command : StringUtils.wordWrap(StringUtils.fixNull(builder.executionCommand)),
      duration: StringUtils.formatDuration(build.duration),
      failed  : failed,
      status  : status,
    ]

    String templateHeader = steps.libraryResource resource: "templates/pullRequest/pr-header.vm", encoding: 'UTF-8'
    String templateTests = steps.libraryResource resource: "templates/pullRequest/pr-tests.vm", encoding: 'UTF-8'
    String templateStatus = steps.libraryResource resource: "templates/pullRequest/pr-status.vm", encoding: 'UTF-8'
    String templateFooter = steps.libraryResource resource: "templates/pullRequest/pr-footer.vm", encoding: 'UTF-8'

    bindings += addTests()
    List<String> errors = []
    List<String> warnings = fetchMessages(buildData.buildStatus.getWarnings())

    if (failed) {
      // look for errors in the logs
      errors = fetchMessages(buildData.buildStatus.getErrors())
    }

    bindings += [
      hasErrors  : !errors.empty,
      errors     : errors,
      hasWarnings: !warnings.empty,
      warnings   : warnings,
    ]

    String template = [templateHeader, templateTests, templateStatus, templateFooter].join('\n')

    report = steps.resolveTemplate(
      text: template,
      parameters: bindings
    )

    this
  }

  private Map addTests() {
    RunWrapper build = steps.currentBuild
    Run raw = build.getRawBuild()
    AbstractTestResultAction testResultAction = raw.getAction(AbstractTestResultAction.class)

    Map bindings = [
      hasTests: testResultAction != null,
    ]

    if (testResultAction) {
      bindings += [
        testResultsUrl: "${build.absoluteUrl}${testResultAction.getUrlName()}",
        totalCount    : testResultAction.totalCount,
        failCount     : testResultAction.failCount,
        skipCount     : testResultAction.skipCount,
        failedTests   : testResultAction.failedTests,
      ]
    }
    return bindings
  }

  private List<String> fetchMessages(Map statusData) {
    List messages = statusData?.collectMany { String stage, Map<String, ?> allMessages ->
      Map stageJobItemMessages = allMessages.get(BuildStatus.Category.JOB) as Map
      return stageJobItemMessages.values().collect { object ->
        if (object instanceof Throwable) {
          // get the root cause
          def cause = object?.cause ?: object
          while (cause.cause) {
            cause = cause.cause
          }
          return cause.message.trim()
        }
        return StringUtils.fixNull(object).trim()
      }
    }

    return messages ?: []
  }

  @Override
  void send() {
    steps.log.debug 'Resolved report:', report
    GitHubManager.commentPullRequest(report)
  }
}
