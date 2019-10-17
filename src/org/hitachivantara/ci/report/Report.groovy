/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.report


import org.hitachivantara.ci.config.BuildData

interface Report {

  /**
   * Builds the report data leaving it ready to be sent.
   * @param buildData
   * @return
   */
  Report build(BuildData buildData)

  /**
   * Sends the prepared data.
   */
  void send()
}
