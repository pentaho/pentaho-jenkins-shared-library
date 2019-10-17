/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.archive

import groovy.transform.InheritConstructors
import org.hitachivantara.ci.PipelineIOException

@InheritConstructors
class ManifestNotFoundException extends PipelineIOException {
}
