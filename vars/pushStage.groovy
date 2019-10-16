import groovy.time.TimeCategory
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.config.BuildData

import static org.hitachivantara.ci.build.helper.BuilderUtils.partition
import static org.hitachivantara.ci.GroovyUtils.unique
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_RETRIES
import static org.hitachivantara.ci.config.LibraryProperties.IGNORE_PIPELINE_FAILURE
import static org.hitachivantara.ci.config.LibraryProperties.PARALLEL_PUSH_CHUNKSIZE
import static org.hitachivantara.ci.config.LibraryProperties.SLAVE_NODE_LABEL
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_PUSH

def call() {
  BuildData buildData = BuildData.instance
  if (buildData.runPush) {
    utils.timer(
      {
        doPush(buildData)
      },
      { long duration ->
        buildData.time(STAGE_LABEL_PUSH, duration)
        log.info "${STAGE_LABEL_PUSH} completed in ${TimeCategory.minus(new Date(duration), new Date(0))}"
      }
    )
  } else {
    utils.createStageSkipped(STAGE_LABEL_PUSH)
    buildData.time(STAGE_LABEL_PUSH, 0)
  }
}

void doPush(BuildData buildData) {
  Boolean ignoreFailures = buildData.getBool(IGNORE_PIPELINE_FAILURE)

  // Collect all items to be worked
  List jobItems = buildData.buildMap.collectMany {
    String key, List value -> value.findAll { JobItem ji -> !ji.execNoop }
  }

  // making job items unique by repo so that only tries to push once for each
  jobItems = unique(jobItems, { it.scmID })

  // no jobItems to build, leave
  if (!jobItems) {
    utils.createStageEmpty(STAGE_LABEL_PUSH)
    return
  }

  // if no chunk value was specified don't split it
  int chunkSize = buildData.getInt(PARALLEL_PUSH_CHUNKSIZE) ?: jobItems.size()

  List jobItemPartitions = partition(jobItems, chunkSize)
  int totalChunks = jobItemPartitions.size()
  boolean singleChunk = totalChunks <= 1

  jobItemPartitions.eachWithIndex { List<JobItem> jobItemsChunk, int currentChunk ->
    String stageLabel = singleChunk ? STAGE_LABEL_PUSH : "${STAGE_LABEL_PUSH} (${++currentChunk}/${totalChunks})"

    Map entries = jobItemsChunk.collectEntries { JobItem jobItem ->
      [(jobItem.jobID): {
        utils.handleError(
          {
            node(buildData.get(SLAVE_NODE_LABEL)) {
              Map scmInfo = jobItem.scmInfo + [credentials: jobItem.scmCredentials]

              dir(jobItem.buildWorkDir) {
                retry(buildData.getInt(BUILD_RETRIES)) {
                  utils.withGit(scmInfo) { String gitUrl ->
                    sh("git push ${gitUrl} HEAD:${jobItem.scmBranch}")
                  }
                }
              }
            }
          },
          { Throwable e ->
            buildData.error(jobItem, e)
            throw e
          })
      }]
    }
    entries.failFast = !ignoreFailures

    stage(stageLabel) {
      utils.handleError(
        {
          parallel entries
        },
        { Throwable e ->
          if (ignoreFailures) {
            job.setBuildUnstable()
          } else {
            throw e
          }
        }
      )
    }
  }
}
