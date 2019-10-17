/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.build

import org.hitachivantara.ci.JobItem

/**
 * @deprecated use {@link Builder} instead
 */
@Deprecated
interface IBuilder {
  @Deprecated
  void setBuilderData(Map builderData)

  /**
   * @deprecated use {@link Builder#getExecution()}
   */
  @Deprecated
  Closure getBuildClosure(JobItem jobItem)

  /**
   * @deprecated use {@link Builder#getExecution()}
   */
  @Deprecated
  Closure getTestClosure(JobItem jobItem)

  /**
   * Expand a jobItem to be built in parallel.
   *
   * The resulting list is sorted by inter module dependencies.
   * ie: result = [[a,b], [c], [d]]
   * 'a' and 'b' are upstreams of 'c', and 'c' is upstream of 'd'
   *
   * @param jobItem
   * @return a dependency sorted list of projects.
   *
   * @deprected use {@link Builder#expandItem()}
   */
  @Deprecated
  List<List<JobItem>> expandWorkItem(JobItem jobItem)

  /**
   * Change the initial jobItem directives in accordance to the detected scm changes.
   * @param jobItem
   * @deprected use {@link Builder#applyScmChanges()}
   */
  @Deprecated
  void markChanges(JobItem jobItem)
}
