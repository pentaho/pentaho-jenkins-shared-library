package org.hitachivantara.ci.config

import groovy.transform.InheritConstructors
import org.hitachivantara.ci.PipelineIOException

@InheritConstructors
class ConfigurationFileNotFoundException extends PipelineIOException {
}
