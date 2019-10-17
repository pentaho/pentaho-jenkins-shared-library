/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.build

import hudson.model.Result

class PipelineSignalException extends InterruptedException {
  Result result

  PipelineSignalException(Result result, String message) {
    super(message)
    this.result = result
  }
}
