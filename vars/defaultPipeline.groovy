/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
import org.hitachivantara.ci.config.BuildData

def call() {
  node(params.SLAVE_NODE_LABEL ?: 'non-master') {
    timestamps {
      config.stage()

      catchError {
        timeout(BuildData.instance.timeout) {
          clean.preStage()
          building.preStage()
          checkoutStage()
          versionStage()
          buildStage()
          testStage()
          pushStage()
          tag.stage()
          archivingStage()
          building.postStage()
          clean.postStage()
        }
      }

      reportStage()
    }
  }
}
