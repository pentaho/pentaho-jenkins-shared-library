/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
import groovy.time.TimeCategory
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.config.BuildData

import static org.hitachivantara.ci.config.LibraryProperties.BUILDS_ROOT_PATH
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_RETRIES
import static org.hitachivantara.ci.config.LibraryProperties.CLEAN_ALL_CACHES
import static org.hitachivantara.ci.config.LibraryProperties.CLEAN_BUILD_WORKSPACE
import static org.hitachivantara.ci.config.LibraryProperties.CLEAN_CACHES_REGEX
import static org.hitachivantara.ci.config.LibraryProperties.CLEAN_SCM_WORKSPACES
import static org.hitachivantara.ci.config.LibraryProperties.LIB_CACHE_ROOT_PATH
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_POSTCLEAN
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_PRECLEAN
import static org.hitachivantara.ci.config.LibraryProperties.WORKSPACE


/**
 * Clear build caches, full or by regex
 */
void caches(String regex = '') {
  BuildData buildData = BuildData.instance
  String rootPath = buildData.getString(LIB_CACHE_ROOT_PATH)

  if (regex) {
    dir(rootPath) {
      log.info "Deleting files matching pattern ${regex}..."
      delete(regex: regex)
    }
  } else {
    log.info "Deleting all files under ${rootPath}"
    delete(path: rootPath, async: true)
  }
}

/**
 * Clear checked out job items
 */
void checkouts(boolean fullWipe = true) {
  BuildData buildData = BuildData.instance

  if (fullWipe) {
    log.info "Deleting ${buildData.getString(BUILDS_ROOT_PATH)}"
    delete(path: buildData.getString(BUILDS_ROOT_PATH), async: true)
  } else {
    buildData.allItems.each { JobItem jobItem ->
      log.info "Deleting ${jobItem.checkoutDir}"
      delete(path: jobItem.checkoutDir, async: true)
      delete(path: jobItem.checkoutDir + '@tmp')
    }
  }
}

/**
 * Clear the workspace
 */
void workspace() {
  BuildData buildData = BuildData.instance

  log.info "Deleting ${buildData.getString(WORKSPACE)}"
  delete(path: buildData.getString(WORKSPACE), async: true)
}

/**
 * Pre build cleanup stage for ease of use
 * @param label
 * @return
 */
def preStage(String label = STAGE_LABEL_PRECLEAN) {
  BuildData buildData = BuildData.instance
  stage(label) {
    if (buildData.runPreClean) {
      utils.timer(
        {
          if (buildData.getBool(CLEAN_ALL_CACHES)) {
            retry(buildData.getInt(BUILD_RETRIES)) {
              caches()
            }
          } else if (buildData.isSet(CLEAN_CACHES_REGEX)) {
            retry(buildData.getInt(BUILD_RETRIES)) {
              caches(buildData.getString(CLEAN_CACHES_REGEX))
            }
          }
          if (buildData.getBool(CLEAN_SCM_WORKSPACES)) {
            retry(buildData.getInt(BUILD_RETRIES)) {
              checkouts(true)
            }
          }
        },
        { long duration ->
          buildData.time(label, duration)
          log.info "${label} completed in ${TimeCategory.minus(new Date(duration), new Date(0))}"
        }
      )
    } else {
      utils.markStageSkipped()
      buildData.time(STAGE_LABEL_PRECLEAN, 0)
    }
  }
}

/**
 * Post build cleanup stage for ease of use
 * @param label
 * @return
 */
def postStage(String label = STAGE_LABEL_POSTCLEAN) {
  BuildData buildData = BuildData.instance
  stage(label) {
    if (buildData.runPostClean) {
      utils.timer(
        {
          if (buildData.getBool(CLEAN_BUILD_WORKSPACE)) {
            retry(buildData.getInt(BUILD_RETRIES)) {
              workspace()
            }
          }
        },
        { long duration ->
          buildData.time(label, duration)
          log.info "${label} completed in ${TimeCategory.minus(new Date(duration), new Date(0))}"
        }
      )
    } else {
      utils.markStageSkipped()
      buildData.time(label, 0)
    }
  }
}