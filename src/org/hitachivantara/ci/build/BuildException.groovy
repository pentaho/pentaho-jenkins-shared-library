/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.build

import org.hitachivantara.ci.PipelineException

class BuildException extends PipelineException {

  final String command

  BuildException(final String command, final String msg) {
    super(msg)
    this.command = command
  }

  BuildException(final String command, final String msg, final Throwable cause) {
    super(msg, cause)
    this.command = command
  }

}
