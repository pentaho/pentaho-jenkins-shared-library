/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.build

import org.hitachivantara.ci.JobItem

abstract class BuilderFactory implements Serializable {

  /**
   * Use builderFor() instead
   * @param ji
   * @param builderData
   * @return
   * @deprecated use {@link #builderFor(org.hitachivantara.ci.JobItem)}
   */
  @Deprecated
  static IBuilder getBuildManager(final JobItem ji, Map ignored) {
    builderFor(ji) as IBuilder
  }

  /**
   * Provides the builder for the given Job Item and execution id
   * @param id
   * @param jobItem
   * @return
   */
  static Builder builderFor(String id = null, JobItem jobItem) {
    BuildFramework framework = jobItem.buildFramework

    if (!framework) throw new BuildFrameworkException("No build framework defined for item ${jobItem.jobID}")
    framework.builder.newInstance(id, jobItem)
  }

}
