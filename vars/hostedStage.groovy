/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
import groovy.time.TimeCategory
import org.hitachivantara.ci.artifacts.HostedArtifactsManager
import org.hitachivantara.ci.config.BuildData

import static org.hitachivantara.ci.config.LibraryProperties.IGNORE_PIPELINE_FAILURE
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_HOST_ARTIFACTS

def call() {
  BuildData buildData = BuildData.instance
  Boolean ignoreFailures = buildData.getBool(IGNORE_PIPELINE_FAILURE)
  log.info "00000000000 ${buildData.runHosted}"
  if (buildData.runHosted) {
    utils.timer(
        {
          utils.handleError(
              {
                log.info "111111111"
                new HostedArtifactsManager(this, buildData).hostArtifacts()
              },
              { Throwable e ->
                if (ignoreFailures) {
                  job.setBuildUnstable()
                } else {
                  throw e
                }
              }
          )
        },
        { long duration ->
          buildData.time(STAGE_HOST_ARTIFACTS, duration)
          log.info "${STAGE_HOST_ARTIFACTS} completed in ${TimeCategory.minus(new Date(duration), new Date(0))}"
        }
    )
  } else {
    utils.createStageSkipped(STAGE_HOST_ARTIFACTS)
    buildData.time(STAGE_HOST_ARTIFACTS, 0)
  }
}
