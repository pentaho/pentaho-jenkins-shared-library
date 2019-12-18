/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.jenkins


import hudson.model.ItemGroup
import hudson.model.Item
import hudson.model.Job
import hudson.model.Run
import hudson.model.TaskListener
import hudson.tasks.junit.TestResultAction
import hudson.triggers.SCMTrigger
import jenkins.model.Jenkins
import jenkins.scm.api.SCMHead
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.PrettyPrinter
import org.hitachivantara.ci.config.BuildData
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead
import org.jenkinsci.plugins.workflow.cps.CpsScript
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import org.jenkinsci.plugins.workflow.libs.LibrariesAction
import org.jenkinsci.plugins.workflow.libs.LibraryRecord

import static org.hitachivantara.ci.config.LibraryProperties.CHANGE_ID
import static org.hitachivantara.ci.config.LibraryProperties.IS_MINION
import static org.hitachivantara.ci.config.LibraryProperties.POLL_CRON_INTERVAL

class JobUtils {

  static Script getSteps() {
    ({} as CpsScript)
  }

  /**
   * Get the last build of the job with the given name
   * @param jobName
   * @return
   */
  static RunWrapper getLastBuildJob(String jobName) {
    return getLastBuildJob(Jenkins.get().getItemByFullName(jobName, Job.class))
  }

  /**
   * Get the last build of the given job
   * @param job
   * @return
   */
  static RunWrapper getLastBuildJob(Job job) {
    Run run = job?.getLastBuild()
    if (run) {
      return new RunWrapper(run, false)
    }
    return null
  }

  /**
   * Find a Jenkins Folder
   * @param path
   * @return
   */
  static ItemGroup getFolder(String path) {
    if (path) {
      return Jenkins.get().getItemByFullName(path)
    } else {
      return Jenkins.get()
    }
  }

  /**
   * Returns a list of all the shared libraries loaded in the build pipeline in the form of 'name@version'
   * @param build
   * @return
   */
  static List getLoadedLibraries(RunWrapper build) {
    LibrariesAction action = build.rawBuild.getAction(LibrariesAction.class)

    if (action) {
      return action.libraries.collect { LibraryRecord library ->
        "${library.name}@${library.version}"
      }
    }

    return []
  }

  /**
   * Sets the poll SCM build trigger schedule expression if defined by the POLL_CRON_INTERVAL property or
   * removes it, if it's a minion and if existed previously and was cleared from POLL_CRON_INTERVAL
   *
   * @param job
   */
  static WorkflowJob managePollTrigger(WorkflowJob job) {
    BuildData buildData = BuildData.instance
    SCMTrigger pollTrigger = job.getSCMTrigger()
    String spec = buildData.getString(POLL_CRON_INTERVAL)

    if ((pollTrigger?.spec ?: '') != spec) {
      if (spec) {
        pollTrigger = new SCMTrigger(spec)
        job.addTrigger(pollTrigger)
      } else if (buildData.getBool(IS_MINION) && pollTrigger) {
        pollTrigger.stop()
        job.getTriggersJobProperty().removeTrigger(pollTrigger)
      }
    }
  }

  /**
   * Find the job with the given name
   * @param name
   * @return
   */
  static Job findJobByName(String name) {
    Item job = Jenkins.get().getItemByFullName(name)
    if (job && job instanceof Job) {
      return job
    } else {
      return null
    }
  }

  /**
   * Entry point to collect data from the multibranch jobs
   */
  static void collectJobData() {
    BuildData buildData = BuildData.instance
    List<JobItem> allJobItems = buildData.buildMap.collectMany { String key, List<JobItem> value -> value }
    Map<String, Map> data = [:]
    TaskListener taskListener = steps.getContext(TaskListener.class) as TaskListener

    allJobItems.each { JobItem jobItem ->
      ItemGroup folder = getFolder("${MinionHandler.getRootFolderPath()}/${jobItem.jobID}")

      folder.allItems().each { Item item ->
        RunWrapper latestBuild = getLastBuildJob(item as Job)
        String branch = item.name

        String branchStatus
        if ( latestBuild.result ){
          branchStatus = latestBuild.result as String
        }

        if (latestBuild.rawBuild.getEnvironment(taskListener).get(CHANGE_ID)) {
          // when pull requests
          PullRequestSCMHead head = (PullRequestSCMHead) SCMHead.HeadByItem.findHead(item)
          if (head) {
            addBranchResult(data, head.target.name, branchStatus, 'pull-requests')
          }
        } else { // base branch
          Map branchData = data.get(branch, [:]) as Map
          branchStatus = branchData.get('status', branchStatus)

          if (branchStatus != null && latestBuild != null && latestBuild.resultIsWorseOrEqualTo(branchStatus)) {
            branchStatus = latestBuild.result as String
          }

          addBranchResult(data, branch, branchStatus, 'jobs')
          if ( branchStatus ) {
            branchData.put('status', branchStatus)
          }

          TestResultAction testAction = latestBuild?.rawBuild?.getAction(TestResultAction.class)
          if (testAction != null) {
            branchData['failing-tests'] = testAction.failCount +
              (branchData['failing-tests'] ? Integer.valueOf(branchData['failing-tests']) : 0)
          }
        }
      }
    }
    steps.log.debug new PrettyPrinter(data).toPrettyPrint()
    buildData.branchStatus(data)
  }

  /**
   * Auxiliary method to manage counters by build status
   * @param data
   * @param branch
   * @param branchStatus
   * @param key
   */
  private static void addBranchResult(Map data, String branch, String branchStatus, String key) {
    Map branchData = data.get(branch, [:])
    Map results = branchData.get(key, [:])
    if ( !branchStatus ) {
      branchStatus = 'Not Built / Building'
    }
    branchStatus = branchStatus.toLowerCase().capitalize()
    results.put(branchStatus, results.get(branchStatus, 0) + 1)
    branchData.put(key, results)
  }

}
