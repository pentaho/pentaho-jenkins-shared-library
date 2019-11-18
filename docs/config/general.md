# General Properties
Here are some general properties that can be set to configure the behaviour of pipelines using this shared library.

### NOOP
If true, a dry run of the pipeline will be performed with every task being skipped

**Default:** false

### BUILD_RETRIES
Sometimes builds fail because of network or file system glitches, but will complete on rebuild. Use this to set a maximum number of retries before giving up. 

**Default:** 1

### BUILD_TIMEOUT
Maximum amount of time in minutes a build can take before it will be forcefully terminated. If default pipeline is not used then this has to be applied around the pipeline definition manually.

**Default:** 360

### DEFAULT_BUILD_PROPERTIES
Default path to look for an external configuration that overrides the default one. 

### JOB_ITEM_DEFAULTS
Default values to used to use for non specified configurations on job items. Example:

```
JOB_ITEM_DEFAULTS:
  scmBranch: master
  buildFramework: MAVEN
  directives: clean install
  testable: true
```

### SCM_CREDENTIALS_ID
This is the Jenkins credentials ID that is used for interacting with git.

### JENKINS_JDK_FOR_BUILDS
Jenkins tool ID for Java's JDK.
