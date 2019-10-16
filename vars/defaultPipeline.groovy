import org.hitachivantara.ci.config.BuildData

def call() {
  node(params.SLAVE_NODE_LABEL ?: 'non-master') {
    timestamps {
      config.stage()

      catchError {
        timeout(BuildData.instance.timeout) {
          clean.preStage()
          building.preStage()
          checkoutStage()
          versionStage()
          buildStage()
          testStage()
          pushStage()
          tag.stage()
          archivingStage()
          building.postStage()
          clean.postStage()
        }
      }

      reportStage()
    }
  }
}
