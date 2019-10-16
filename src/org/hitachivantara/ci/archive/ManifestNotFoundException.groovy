package org.hitachivantara.ci.archive

import groovy.transform.InheritConstructors
import org.hitachivantara.ci.PipelineIOException

@InheritConstructors
class ManifestNotFoundException extends PipelineIOException {
}
