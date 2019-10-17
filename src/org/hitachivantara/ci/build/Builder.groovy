/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.build

import org.hitachivantara.ci.JobItem

interface Builder {

  String getExecutionCommand()

  /**
   * Returns a closure to execute the job item build
   * @return
   */
  Closure getExecution()

  /**
   * Expand a jobItem to be built in parallel.
   *
   * The resulting list is sorted by inter module dependencies.
   * ie: result = [[a,b], [c], [d]]
   * 'a' and 'b' are upstreams of 'c', and 'c' is upstream of 'd'
   *
   * @return a dependency sorted list of projects.
   */
  List<List<JobItem>> expandItem()

  /**
   * Change the initial jobItem directives in accordance to the detected scm changes.
   */
  void applyScmChanges()

  /**
   * For sonar execution
   * @return a closure execution for sonar analysis
   */
  Closure getSonarExecution()
}