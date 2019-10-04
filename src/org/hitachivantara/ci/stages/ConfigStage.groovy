/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.stages

import groovy.time.TimeCategory

class ConfigStage extends SimpleStage {

  String id = 'configure'
  String label = 'Configure'

  ConfigStage(Closure body) {
    this.body = body
  }

  @Override
  void run() {
    steps.stage(label) {
      steps.utils.timer(
        body,
        { long duration ->
          buildData.time(label, duration)
          steps.log.info "${label} completed in ${TimeCategory.minus(new Date(duration), new Date(0))}"
        }
      )
    }
  }

  @Override
  Boolean isRun() {
    true
  }

}
