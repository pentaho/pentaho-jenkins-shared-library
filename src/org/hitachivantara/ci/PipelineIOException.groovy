/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import com.cloudbees.groovy.cps.NonCPS
import groovy.transform.InheritConstructors

@InheritConstructors
abstract class PipelineIOException extends IOException {

  @Override
  @NonCPS
  void printStackTrace(PrintWriter s) {
    StringUtils.printStackTrace(this, s)
  }
}
