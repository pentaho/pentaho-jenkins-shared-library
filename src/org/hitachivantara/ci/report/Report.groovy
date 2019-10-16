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
