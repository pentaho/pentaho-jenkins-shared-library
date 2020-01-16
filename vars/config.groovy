/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
import groovy.time.TimeCategory
import org.hitachivantara.ci.ScmUtils
import org.hitachivantara.ci.config.BuildDataBuilder
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.jenkins.JobUtils
import org.hitachivantara.ci.jenkins.MinionHandler
import org.jenkinsci.plugins.workflow.job.WorkflowJob

import static org.hitachivantara.ci.config.LibraryProperties.BUILD_RETRIES
import static org.hitachivantara.ci.config.LibraryProperties.ARTIFACTS_TO_KEEP
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_PLAN_ID
import static org.hitachivantara.ci.config.LibraryProperties.DISABLE_CONCURRENT_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.LOGS_TO_KEEP
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_CONFIGURE

def stage(Map defaultParams = [:]) {
  BuildData buildData
  stage(STAGE_LABEL_CONFIGURE) {
    utils.timer(
      {

        if (utils.hasScmConfig()) {
          // checkout the pipeline repo
          checkout(poll: false, changelog: false, scm: scm)
        }

        buildData = load(defaultParams)
        applyToJob(defaultParams)

        log.info "Configuration loaded for: ${buildData.get(BUILD_PLAN_ID)}", buildData

        retry(buildData.getInt(BUILD_RETRIES)) {
          if (!buildData.runCheckouts) {
            ScmUtils.rebuildCheckouts(currentBuild)
          }

          if (buildData.useMinions) {
            log.info "Using Minion jobs to perform the build"
            utils.timer(
              {
                MinionHandler.manageJobs()
              },
              { long duration ->
                log.info "Minion job management completed in ${TimeCategory.minus(new Date(duration), new Date(0))}"
              }

            )
          } else if (buildData.minion) {
            MinionHandler.setBuildDisplayName(currentBuild)
          }
        }
      },
      { long duration ->
        buildData?.time(duration)
        log.info "${STAGE_LABEL_CONFIGURE} completed in ${TimeCategory.minus(new Date(duration), new Date(0))}"
      }
    )
  }
}

BuildData load(Map defaultParams = [:]) {
  BuildDataBuilder builder = new BuildDataBuilder()
    .withEnvironment(env)
    .withParams(defaultParams + params)

  BuildData buildData = builder.build()
  builder.consumeMessages()

  log.info "Configuration loaded for: ${buildData.get(BUILD_PLAN_ID)}", buildData
  return buildData
}

BuildData get() {
  BuildData.instance
}

void applyToJob(Map defaultParams = [:]) {
  BuildData buildData = BuildData.instance
  JobUtils.managePollTrigger(getContext(WorkflowJob.class))

  List jobConfig = []

  if (buildData.getBool(DISABLE_CONCURRENT_BUILDS)) {
    jobConfig << disableConcurrentBuilds()
  }
  if (buildData.isSet(LOGS_TO_KEEP) || buildData.isSet(ARTIFACTS_TO_KEEP)) {
    jobConfig << buildDiscarder(
      logRotator(
        numToKeepStr: buildData.getString(LOGS_TO_KEEP),
        artifactNumToKeepStr: buildData.getString(ARTIFACTS_TO_KEEP)
      )
    )
  }
  if (defaultParams) {
    jobConfig << parameters(job.toParameters(defaultParams, true))
  }

  if (jobConfig) {
    properties(jobConfig)
  }
}
