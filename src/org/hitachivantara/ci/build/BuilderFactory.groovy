/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.build

import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.config.BuildData
import org.jenkinsci.plugins.workflow.cps.CpsScript

abstract class BuilderFactory implements Serializable {

  /**
   * Use builderFor() instead
   * @param ji
   * @param builderData
   * @return
   * @deprecated use {@link #builderFor(org.hitachivantara.ci.JobItem)}
   */
  @Deprecated
  static IBuilder getBuildManager(final JobItem ji, Map builderData) {
    builderFor(builderData.dsl as Script, ji) as IBuilder
  }

  /**
   * Provides the builder for the given Job Item
   * @param dsl
   * @param jobItem
   * @return
   */
  static Builder builderFor(Script dsl = {} as CpsScript, JobItem jobItem) {
    BuildFramework framework = jobItem.buildFramework
    BuildData buildData = BuildData.instance

    if (!framework) throw new BuildFrameworkException("No build framework defined for item ${jobItem.jobID}")
    framework.builder.newInstance(dsl, buildData, jobItem)
  }
}
