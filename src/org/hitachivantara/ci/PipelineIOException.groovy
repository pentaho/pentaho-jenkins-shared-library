package org.hitachivantara.ci

import groovy.transform.InheritConstructors

@InheritConstructors
abstract class PipelineIOException extends IOException {
}
