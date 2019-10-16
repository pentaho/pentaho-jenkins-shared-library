package org.hitachivantara.ci.utils

import com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration
import com.lesfurets.jenkins.unit.global.lib.SourceRetriever
import org.junit.AssumptionViolatedException

//TODO create tests for Jenkisfile library loading
class SharedLibraryCreator {
  static LibraryConfiguration library = getLibraryConfiguration()

  static private LibraryConfiguration getLibraryConfiguration() {
    LibraryConfiguration.library()
      .name('jenkins-shared-libraries')
      .retriever(new SourceRetriever() {
        @Override
        List<URL> retrieve(String repository, String branch, String targetPath) throws IllegalStateException {
          File sourceDir = new File('.')
          if (sourceDir.exists()) {
            return [sourceDir.toURI().toURL()]
          }
          throw new AssumptionViolatedException("Directory ${sourceDir.path} doesn't exist")
        }
      })
      .defaultVersion("master")
      .allowOverride(true)
      .implicit(true)
      .build()
  }
}
