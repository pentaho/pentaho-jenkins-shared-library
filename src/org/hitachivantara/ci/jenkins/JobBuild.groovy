/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.jenkins


import org.jenkinsci.plugins.workflow.cps.CpsScript
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

class JobBuild {

  protected Script getSteps() {
    ({} as CpsScript)
  }

  String jobName
  List parameters = []

  JobBuild(String jobName) {
    this.jobName = jobName
  }

  /**
   * Returns the closure to call a Jenkins job
   * @param async whether the build should wait for the result of this
   * @return
   */
  Closure getExecution(boolean async = false) {
    steps.log.debug "Properties to pass on:", parameters

    if (!jobName) {
      throw new JobException("No target job name was specified for execution")
    }

    return { ->
      RunWrapper jobRun = steps.build(
        job: jobName,
        wait: !async,
        propagate: false,
        parameters: parameters
      )
      if (jobRun && !async) {
        if (jobRun.resultIsWorseOrEqualTo('FAILURE')) {
          throw new JobException("Job '${jobName}' execution failed")
        } else {
          steps.job.setBuildResult(jobRun.result)
        }

        return jobRun
      }
    }
  }

  /**
   * Converts a Map to a List with Jenkins specific properties types
   *
   * @param properties
   * @return
   */
  JobBuild withParameters(Map properties) {
    this.parameters = steps.job.toParameters(properties)
    return this
  }
}
