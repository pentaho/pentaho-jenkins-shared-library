/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.build.impl

import org.hitachivantara.ci.FileUtils
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.build.BuildFramework
import org.hitachivantara.ci.build.BuilderException
import org.hitachivantara.ci.build.IBuilder
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution
import org.jenkinsci.plugins.workflow.cps.CpsGroovyShellFactory
import org.jenkinsci.plugins.workflow.cps.CpsGroovyShell
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

class DSLScriptBuilder extends AbstractBuilder implements IBuilder, Serializable {

  String name = BuildFramework.DSL_SCRIPT.name()

  DSLScriptBuilder(String id, JobItem item) {
    this.item = item
    this.id = id
  }

  @Override
  String getExecutionCommand() {
    throw new BuilderException('Not yet implemented')
  }

  @Override
  Closure getExecution() {
    getBuildClosure(item)
  }

  @Override
  void setBuilderData(Map builderData) {
    this.buildData = builderData['buildData']
    this.steps = builderData['dsl']
  }

  @Override
  Closure getBuildClosure(JobItem jobItem) {

    return {
      String dslScript = jobItem.script

      if (FileUtils.exists(dslScript)) {
        dslScript = steps.readFile(file: dslScript, encoding: 'UTF-8') as String
      }

      dslScript = dslScript.replace('$\\{', '${')

      steps.log.debug 'Executing:', dslScript

      RunWrapper build = steps.currentBuild as RunWrapper
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
    return [[item]]
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
