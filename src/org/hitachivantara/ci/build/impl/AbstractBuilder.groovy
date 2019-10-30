/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.build.impl

import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.build.Builder
import org.hitachivantara.ci.config.BuildData
import org.jenkinsci.plugins.workflow.cps.CpsScript

import static org.hitachivantara.ci.config.LibraryProperties.DEFAULT_COMMAND_OPTIONS
import static org.hitachivantara.ci.config.LibraryProperties.DEFAULT_DIRECTIVES
import static org.hitachivantara.ci.config.LibraryProperties.OPTS
import static org.hitachivantara.ci.config.LibraryProperties.PRIVATE_RELEASE_REPO_URL
import static org.hitachivantara.ci.config.LibraryProperties.PRIVATE_SNAPSHOT_REPO_URL
import static org.hitachivantara.ci.config.LibraryProperties.PUBLIC_RELEASE_REPO_URL
import static org.hitachivantara.ci.config.LibraryProperties.PUBLIC_SNAPSHOT_REPO_URL
import static org.hitachivantara.ci.config.LibraryProperties.RESOLVE_REPO_URL
import static org.hitachivantara.ci.config.LibraryProperties.SETTINGS

abstract class AbstractBuilder implements Builder {

  Script steps = {} as CpsScript
  BuildData buildData = BuildData.instance

  /**
   * Execution ID
   */
  String id

  /**
   * Item to be executed
   */
  JobItem item

  /**
   * Get the value of <BUILDER_NAME>_RESOLVE_REPO_URL
   *
   * @return URL to resolve dependency artifacts from
   */
  String getResolveRepo() {
    buildData.getString("${name.toUpperCase()}_${RESOLVE_REPO_URL}")
  }

  /**
   * Get the value of <BUILDER_NAME>_PUBLIC_RELEASE_REPO_URL
   *
   * @return URL to deploy public release artifacts
   */
  String getPublicReleaseRepo() {
    buildData.getString("${name.toUpperCase()}_${PUBLIC_RELEASE_REPO_URL}")
  }

  /**
   * Get the value of <BUILDER_NAME>_PUBLIC_SNAPSHOT_REPO_URL
   *
   * @return URL to deploy public snapshot artifacts
   */
  String getPublicSnapshotRepo() {
    buildData.getString("${name.toUpperCase()}_${PUBLIC_SNAPSHOT_REPO_URL}")
  }

  /**
   * Get the value of <BUILDER_NAME>_PRIVATE_RELEASE_REPO_URL
   *
   * @return URL to deploy private release artifacts
   */
  String getPrivateReleaseRepo() {
    buildData.getString("${name.toUpperCase()}_${PRIVATE_RELEASE_REPO_URL}")
  }

  /**
   * Get the value of <BUILDER_NAME>_PRIVATE_SNAPSHOT_REPO_URL
   *
   * @return URL to deploy private snapshot artifacts
   */
  String getPrivateSnapshotRepo() {
    buildData.getString("${name.toUpperCase()}_${PRIVATE_SNAPSHOT_REPO_URL}")
  }

  /**
   * Get the value of <BUILDER_NAME>_OPTS
   *
   * @return options for this builder
   */
  String getOpts() {
    buildData.getString("${name.toUpperCase()}_${OPTS}")
  }

  /**
   * Get the value of <BUILDER_NAME>_SETTINGS
   *
   * @return settings file for this builder
   */
  String getSettingsFile() {
    buildData.getString("${name.toUpperCase()}_${SETTINGS}")
  }

  /**
   * Get the value of <BUILDER_NAME>_DEFAULT_DIRECTIVES or <BUILDER_NAME>_PR_DEFAULT_DIRECTIVES
   *
   * @param id
   * @return default directives for the builder
   */
  String getDefaultDirectives(String id = null) {
    def directives

    if(buildData.isPullRequest() && buildData.isSet("${name.toUpperCase()}_PR_${DEFAULT_DIRECTIVES}")){
      directives = buildData.get("${name.toUpperCase()}_PR_${DEFAULT_DIRECTIVES}")
    } else {
      directives = buildData.get("${name.toUpperCase()}_${DEFAULT_DIRECTIVES}")
    }

    if (id && directives instanceof Map) {
      return directives[id] as String
    } else {
      return directives as String
    }
  }

  /**
   * Get the value of <BUILDER_NAME>_DEFAULT_COMMAND_OPTIONS or <BUILDER_NAME>_PR_DEFAULT_COMMAND_OPTIONS
   *
   * @return default command options for the builder
   */
  String getDefaultCommandOptions() {
    if(buildData.isPullRequest() && buildData.isSet("${name.toUpperCase()}_PR_${DEFAULT_COMMAND_OPTIONS}")) {
      buildData.getString("${name.toUpperCase()}_PR_${DEFAULT_COMMAND_OPTIONS}")
    } else {
      buildData.getString("${name.toUpperCase()}_${DEFAULT_COMMAND_OPTIONS}")
    }
  }

  /**
   * Get the value of JENKINS_<BUILDER_NAME>_FOR_BUILDS
   *
   * @return the jenkins build tool id for this builder
   */
  String getJenkinsTool(){
    buildData.getString("JENKINS_${name.toUpperCase()}_FOR_BUILDS")
  }

}