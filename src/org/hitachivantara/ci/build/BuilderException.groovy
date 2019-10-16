package org.hitachivantara.ci.build

import groovy.transform.InheritConstructors
import org.hitachivantara.ci.PipelineException

@InheritConstructors
class BuilderException extends PipelineException {
}
