/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
import hudson.model.Job
import hudson.model.Result
import org.hitachivantara.ci.jenkins.JobException
import org.hitachivantara.ci.jenkins.JobBuild
import org.hitachivantara.ci.jenkins.JobUtils
import org.jenkinsci.plugins.pipeline.utility.steps.shaded.org.yaml.snakeyaml.Yaml

/**
 * Get the next build number for the job with the given name
 * @param jobName
 * @return
 */
Integer nextBuildNumber(String jobName) {
  Job job = JobUtils.findJobByName(jobName)
  if (!job) {
    // might be a relative path, attempt to find it based on the current job location
    Job currentJob = JobUtils.findJobByName(env.JOB_NAME)
    if (currentJob.parent) {
      job = JobUtils.findJobByName("${currentJob.parent.fullName}/${jobName}")
    }
  }

  if (!job) {
    throw new JobException("Unknown job: ${jobName}")
  }

  return job.nextBuildNumber
}

/**
 * Trigger the job with the given name
 * @param jobName
 * @param parameters
 * @param waitUntilDone
 */
void trigger(String jobName, Map parameters, waitUntilDone = true) {
  utils.handleError(
    new JobBuild(jobName)
      .withParameters(parameters)
      .getExecution(!waitUntilDone),
    { Throwable e ->
      throw e
    }
  )
}

/**
 * Converts a Map to a List with Jenkins specific properties types to be used in job configuration or to pass as
 * parameters to an external job call
 * @param properties
 * @param forConfig
 * @return
 */
List toParameters(Map properties, boolean forConfig = false) {
  String valueKey = forConfig ? 'defaultValue' : 'value'

  properties.collect { key, value ->
    switch (value) {
      case Boolean:
        return booleanParam(name: key, (valueKey): value)

      case String:
        if (value.contains("\n")) {
          return text(name: key, (valueKey): value)
        }
        return string(name: key, (valueKey): value)

      case Map:
        // TODO: this yaml management should probably be done with a step
        return text(name: key, (valueKey): value ? new Yaml().dump(value) : '')

      default:
        return string(name: key, (valueKey): value.toString())
    }
  }
}

/**
 * Sets the build result on the current build
 * @param run
 * @param result
 */
void setBuildResult(result) {
  currentBuild.result = String.valueOf(result)
}

/**
 * Set the current build as UNSTABLE
 * @param run
 */
void setBuildUnstable() {
  setBuildResult(Result.UNSTABLE)
}