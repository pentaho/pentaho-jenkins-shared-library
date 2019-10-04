/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import groovy.transform.Field
import org.hitachivantara.ci.config.BuildData

@Field BuildData buildData = BuildData.instance

def call() {
  node(params.SLAVE_NODE_LABEL ?: 'non-master') {
    timestamps {
      stages.configure()

      catchError {
        timeout(buildData.timeout) {
          stages.preClean()
          stages.checkout()
          stages.version()
          stages.build()
          stages.test()
          stages.push()
          stages.tag()
          stages.archive()
          stages.postClean()
        }
      }

      stages.report()
    }
  }
}
