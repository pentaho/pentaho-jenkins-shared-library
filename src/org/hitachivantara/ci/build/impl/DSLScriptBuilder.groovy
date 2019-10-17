/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.build.impl

import org.hitachivantara.ci.FileUtils
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.build.Builder
import org.hitachivantara.ci.build.BuilderException
import org.hitachivantara.ci.build.IBuilder
import org.hitachivantara.ci.config.BuildData
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution
import org.jenkinsci.plugins.workflow.cps.CpsGroovyShellFactory
import org.jenkinsci.plugins.workflow.cps.CpsGroovyShell
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper


class DSLScriptBuilder implements IBuilder, Builder, Serializable {

  private BuildData buildData
  private JobItem jobItem
  private Script dsl


  DSLScriptBuilder(Script dsl, BuildData buildData, JobItem jobItem) {
    this.dsl = dsl
    this.jobItem = jobItem
    this.buildData = buildData
  }

  Closure getExecution() {
    throw new BuilderException('Not yet implemented')
  }

  @Override
  void setBuilderData(Map builderData) {
    this.buildData = builderData.buildData
    this.dsl = builderData.dsl
  }

  @Override
  Closure getBuildClosure(JobItem jobItem) {

    return {
      String dslScript = jobItem.script

      if (FileUtils.exists(dslScript)) {
        dslScript = dsl.readFile(file: dslScript, encoding: 'UTF-8') as String
      }

      dslScript = dslScript.replace('$\\{', '${')

      dsl.log.debug 'Executing:', dslScript

      RunWrapper build = dsl.currentBuild as RunWrapper
      WorkflowRun raw = build.getRawBuild() as WorkflowRun
      CpsFlowExecution execution = raw.getExecution() as CpsFlowExecution

      CpsGroovyShell trusted = new CpsGroovyShellFactory(execution).forTrusted().build()
      CpsGroovyShell cpsGroovyShell = new CpsGroovyShellFactory(execution).withParent(trusted).withSandbox(true).build()

      def closure = cpsGroovyShell.evaluate(dslScript)

      if (closure && closure instanceof Closure) {
        closure()
      }
    }
  }

  @Override
  Closure getTestClosure(JobItem jobItem) {
    return {}
  }

  @Override
  List<List<JobItem>> expandWorkItem(JobItem jobItem) {
    expandItem()
  }

  @Override
  List<List<JobItem>> expandItem() {
    return [[jobItem]]
  }

  @Override
  void markChanges(JobItem jobItem) {
    applyScmChanges()
  }

  @Override
  void applyScmChanges() {
    // do nothing
  }

  @Override
  Closure getSonarExecution() {
    // not implemented
    return { -> }
  }
}
