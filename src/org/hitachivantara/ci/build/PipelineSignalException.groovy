package org.hitachivantara.ci.build

import hudson.model.Result

class PipelineSignalException extends InterruptedException {
  Result result

  PipelineSignalException(Result result, String message) {
    super(message)
    this.result = result
  }
}
