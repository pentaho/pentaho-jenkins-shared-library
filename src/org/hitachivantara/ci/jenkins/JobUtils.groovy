package org.hitachivantara.ci.jenkins

import hudson.model.ItemGroup
import hudson.model.Item
import hudson.model.Job
import hudson.model.Run
import hudson.triggers.SCMTrigger
import jenkins.model.Jenkins
import org.hitachivantara.ci.config.BuildData
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import org.jenkinsci.plugins.workflow.libs.LibrariesAction
import org.jenkinsci.plugins.workflow.libs.LibraryRecord

import static org.hitachivantara.ci.config.LibraryProperties.IS_MINION
import static org.hitachivantara.ci.config.LibraryProperties.POLL_CRON_INTERVAL

class JobUtils {

  /**
   * Get the last build of the job with the given name
   * @param jobName
   * @return
   */
  static RunWrapper getLastBuildJob(String jobName) {
    Job job = Jenkins.get().getItemByFullName(jobName, Job.class)
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

    if(action){
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
  static Job findJobByName(String name){
    Item job = Jenkins.get().getItemByFullName(name)
    if(job && job instanceof Job){
      return job
    } else {
      return null
    }
  }
}
