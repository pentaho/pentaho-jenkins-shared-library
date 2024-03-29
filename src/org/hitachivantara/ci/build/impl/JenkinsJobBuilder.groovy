/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.build.impl

import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.build.BuildFramework
import org.hitachivantara.ci.build.BuilderException
import org.hitachivantara.ci.build.IBuilder
import org.hitachivantara.ci.jenkins.JobBuild
import org.hitachivantara.ci.config.ConfigurationMap

import static org.hitachivantara.ci.config.LibraryProperties.JOB_ITEM_DEFAULTS
import static org.hitachivantara.ci.config.LibraryProperties.VERSION_COMMIT_FILES

/**
 * This isn't really a builder but it is the best way to introduce
 * this new feature into the building system
 */
class JenkinsJobBuilder extends AbstractBuilder implements IBuilder, Serializable {

  String name = BuildFramework.JENKINS_JOB.name()

  private static final passOnExclusions = [
    JOB_ITEM_DEFAULTS,
    VERSION_COMMIT_FILES,
    'properties' // not sure what this one is about?
  ]

  JenkinsJobBuilder(String id, JobItem item) {
    this.item = item
    this.id = id
  }

  @Override
  String getExecutionCommand() {
    throw new BuilderException('Not yet implemented')
  }

  @Override
  Closure getExecution() {
    getBuildClosure(item)
  }

  @Override
  void setBuilderData(Map builderData) {
    this.buildData = builderData['buildData']
    this.steps = builderData['dsl']
  }

  @Override
  Closure getBuildClosure(JobItem jobItem) {
    Map parameters = [:]

    if (jobItem.passOnBuildParameters) {
      parameters << buildData.buildProperties.findAll { String key, value -> !passOnExclusions.contains(key) }
    }

    if (jobItem.jobProperties) {
      // to allow for filtering we need to take the properties through a filtered map
      Map filteredProperties = new ConfigurationMap(buildData.buildProperties)
      filteredProperties << jobItem.jobProperties
      parameters << filteredProperties
    }

    return new JobBuild(jobItem.targetJobName)
      .withParameters(parameters)
      .getExecution(jobItem.asynchronous)
  }

  @Override
  Closure getTestClosure(JobItem jobItem) {
    return {}
  }

  @Override
  List<List<JobItem>> expandWorkItem(JobItem jobItem) {
    return null
  }

  @Override
  List<List<JobItem>> expandItem() {
    [[item]]
  }

  @Override
  void markChanges(JobItem jobItem) {
    applyScmChanges()
  }

  @Override
  void applyScmChanges() {
    //no op
  }

  @Override
  Closure getSonarExecution() {
    // not implemented
    return { -> }
  }

  @Override
  Closure getFrogbotExecution() {
    // not implemented
    return { -> }
  }

}
