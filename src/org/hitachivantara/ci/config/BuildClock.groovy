/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.config

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class BuildClock extends Clock implements Serializable {

  ZoneId zone = ZoneId.systemDefault()

  @Override
  ZoneId getZone() {
    zone
  }

  @Override
  Clock withZone(ZoneId zone) {
    this.zone == zone ? this : new BuildClock(zone: zone)
  }

  @Override
  long millis() {
    System.currentTimeMillis()
  }

  @Override
  Instant instant() {
    Instant.ofEpochMilli(millis())
  }

  /**
   * Format the current time instant using {@link java.text.SimpleDateFormat}
   * using the given pattern
   * @param pattern
   * @return
   */
  String format(String pattern) {
    new Date(instant().toEpochMilli()).format(pattern, TimeZone.getTimeZone(getZone()))
  }

}
