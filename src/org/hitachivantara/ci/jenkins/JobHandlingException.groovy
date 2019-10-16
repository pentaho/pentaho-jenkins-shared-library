package org.hitachivantara.ci.jenkins

import groovy.transform.InheritConstructors
import org.hitachivantara.ci.PipelineException

@InheritConstructors
class JobHandlingException extends PipelineException {
}
