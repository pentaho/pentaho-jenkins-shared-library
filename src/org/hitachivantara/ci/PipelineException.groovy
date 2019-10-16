package org.hitachivantara.ci

import groovy.transform.InheritConstructors

@InheritConstructors
abstract class PipelineException extends RuntimeException {
}
