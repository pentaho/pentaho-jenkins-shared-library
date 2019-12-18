/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.report

class BuildStatus implements Serializable {

  enum Level {
    ERRORS, WARNINGS, TIMINGS, RELEASES, BRANCH_STATUS
  }

  enum Category {
    GENERAL, JOB
  }

  Map<Object, Map> buildStatus = [:]

  void time(String stage, item, data) {
    store(stage, Level.TIMINGS, item, data)
  }

  void time(String stage, data) {
    store(stage, Level.TIMINGS, data)
  }

  void error(String stage, item, data) {
    store(stage, Level.ERRORS, item, data)
  }

  void error(String stage, data) {
    store(stage, Level.ERRORS, data)
  }

  void warning(String stage, item, data) {
    store(stage, Level.WARNINGS, item, data)
  }

  void warning(String stage, data) {
    store(stage, Level.WARNINGS, data)
  }

  void release(String stage, data) {
    store(stage, Level.RELEASES, data)
  }

  void branchStatus(String stage, data) {
    store(stage, Level.BRANCH_STATUS, data)
  }
  //sonar claims, but synchronized blocks is unsupported for CPS transformation
  private synchronized void store(String stage, Level level, item, data) {
    buildStatus
      .get(level, [:])
      .get(stage, [:])
      .get(Category.JOB, [:])
      .put(item, data)
  }

  //sonar claims, synchronized blocks is unsupported for CPS transformation
  private synchronized void store(String stage, Level level, data) {
    buildStatus
      .get(level, [:])
      .get(stage, [:])
      .get(Category.GENERAL, [])
      .add(data)
  }

  Boolean isEmpty() {
    return !hasErrors() && !hasWarnings()
  }

  Boolean hasErrors() {
    buildStatus.get(Level.ERRORS) != null
  }

  Boolean hasWarnings() {
    buildStatus.get(Level.WARNINGS) != null
  }

  Boolean hasReleases() {
    buildStatus.get(Level.RELEASES) != null
  }

  Boolean hasBranchStatus() {
    buildStatus.get(Level.BRANCH_STATUS) != null
  }

  Map getErrors() {
    buildStatus.get(Level.ERRORS)
  }

  Map getWarnings() {
    buildStatus.get(Level.WARNINGS)
  }

  Map getTimings() {
    buildStatus.get(Level.TIMINGS)
  }

  Map getReleases() {
    buildStatus.get(Level.RELEASES)
  }

  Map getBranchStatus() {
    buildStatus.get(Level.BRANCH_STATUS)
  }

  Long getStageDuration(String stage) {
    timings.get(stage)?.get(Category.GENERAL)?.sum()
  }
}
