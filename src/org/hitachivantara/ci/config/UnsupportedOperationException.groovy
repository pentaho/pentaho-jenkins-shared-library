package org.hitachivantara.ci.config

import groovy.transform.InheritConstructors
import org.hitachivantara.ci.PipelineException

@InheritConstructors
class UnsupportedOperationException extends PipelineException {
}
