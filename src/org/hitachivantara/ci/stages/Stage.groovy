/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.stages

import groovy.time.TimeCategory
import org.hitachivantara.ci.StringUtils
import org.hitachivantara.ci.config.BuildData
import org.jenkinsci.plugins.workflow.cps.CpsScript
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

import static org.hitachivantara.ci.config.LibraryProperties.BUILD_NUMBER
import static org.hitachivantara.ci.config.LibraryProperties.IS_MINION
import static org.hitachivantara.ci.config.LibraryProperties.IS_MULTIBRANCH_MINION
import static org.hitachivantara.ci.config.LibraryProperties.NOOP

abstract class Stage {
  static final String RUN_STAGE_PROPERTY_PREFIX = 'RUN_STAGE_'
  static final String MINION_STAGE = 'MINION_STAGE'

  Script steps = {} as CpsScript
  BuildData buildData = BuildData.instance

  String id
  String label

  Closure<Boolean> isRun

  Closure onError = { Throwable e -> throw e }
  Closure onSkipped = { -> }
  Closure onFinished = { -> }

  /**
   * Run the stage(s) to work the items, to be implemented by the extending classes
   */
  abstract void run()

  /**
   * Common wrapping around stage execution
   * @param stageBody
   */
  protected void wrap(Closure stageBody) {
    if (buildData.getBool(NOOP)) {
      steps.utils.createStageSkipped(label)
      buildData.time(label, 0)
      return
    }

    if (isRun()) {
      updateBuildDisplayName()

      steps.utils.timer(
        {
          steps.utils.handleError(
            stageBody,
            onError,
            onFinished
          )
        },
        { long duration ->
          buildData.time(label, duration)
          steps.log.info "${label} completed in ${TimeCategory.minus(new Date(duration), new Date(0))}"
        }
      )
    } else {
      onSkipped.call()

      steps.utils.createStageSkipped(label)
      buildData.time(label, 0)
    }
  }

  /**
   * Check if the stage is to be run. Define isRun closure to override default property checking.
   * @param label
   * @return
   */
  Boolean isRun() {
    boolean runStage = isRun ? isRun.call() : buildData.getBool(getRunProperty())

    if (buildData.isMinion() && buildData.isSet(MINION_STAGE)) {
      runStage &= buildData.getString(MINION_STAGE) == id
    }

    return runStage
  }

  /**
   * Update the build display name on minions with the current stage
   */
  void updateBuildDisplayName() {
    if (buildData.getBool(IS_MINION) && !buildData.getBool(IS_MULTIBRANCH_MINION)) {
      RunWrapper run = steps.currentBuild
      if (run.displayName.endsWith(buildData.getString(BUILD_NUMBER))) {
        run.displayName += ": $label"
      } else {
        run.displayName += " / $label"
      }
    }
  }

  /**
   * Get the generated run stage property based on the stage id
   * @return
   */
  String getRunProperty() {
    "${RUN_STAGE_PROPERTY_PREFIX}${StringUtils.normalizeString(id).toUpperCase()}"
  }

}
