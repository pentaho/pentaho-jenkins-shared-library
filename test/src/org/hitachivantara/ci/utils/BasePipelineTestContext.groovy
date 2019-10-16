package org.hitachivantara.ci.utils

import org.codehaus.groovy.runtime.InvokerHelper

class BasePipelineTestContext {

  static Script testScript(Binding binding = new Binding() ) {
    Script script = InvokerHelper.createScript(null, binding)
    LibraryTestListener.prepareObjectInterceptors(script)
    return script
  }

}
