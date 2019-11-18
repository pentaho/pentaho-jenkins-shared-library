# Job Items
A job item is what we call a build element that will be worked during a pipeline execution.

## Main Properties

### jobID
A String mandatory id for the item.

**Default:** job\<randomUUID\>

### buildFile
The build file path relative to the root directory defined on `root`

**Default:** defaults to the build framework default

### directives
Build directives. These can be defined as a Map to allow running the item through multiple Builders.

Simple directives:
```
directives: clean install
```

Multiple directives:
```
directives:
  build : clean package
  test  : test
  it    : integration-test
```

Each of the directives keys will have to be passed to the Builder so that it can identify the correct ones to use.

### testsArchivePattern
Pattern to use when archiving tests to the job.

**Default:** \*\*/target/\*\*/TEST\*.xml

### testable
If this item is to run tests.

**Default:** true

### root
Root directory to work on relative to the root of the repository.

### versionProperty
Version property to use when replacing version properties on the build file.

### buildFramework
The build framework for this job item

**Default:** MAVEN

**Available:** MAVEN, ANT, GRADLE, JENKINS_JOB, DSL_SCRIPT

### execType
How to execute this job item.

**Default:** FORCE

**Available:** AUTO, AUTO_DOWNSTREAMS, FORCE, NOOP             

### archivable
If artifact archiving is to be performed on this item

**Default:** true

### parallelize
Attempt to expand and parallelize this item.

**Default:** false

### atomicScmCheckout
Checkout this item to a unique directory in case more items share the same repo the checkout will not be shared.

**Default:** false

### dockerImage
Docker image to use when running this item.

### settingsFile
Build framework settings file.

### auditable
If this item is to run code audit.

**Default:** false


## SCM Properties

### scmUrl
Git Url to fetch the source code from. 

### scmCacheUrl
Git cache Url to fetch the source code from. 

### scmBranch
Branch to fetch the source code from. 

**Default:** master

### scmRevision
Commit to use when building.

**Default:** latest

### scmCredentials
Jenkins credentials id to use when authenticating with git.

### scmPoll
Enable pooling for changes on this item.

**Default:** true


## JENKINS_JOB Properties

### asynchronous
Run this item asynchronously.

**Default:** false

### properties
Properties to pass to this item.

### targetJobName
Job name to trigger through this item.

### passOnBuildParameters
Pass current build parameters to the job being triggered by this item.

**Default:** true


## DSL_SCRIPT Properties

### script
Script to execute for this item.


## Multibranch Properties

### scmScanInterval
Pooling for changes scan interval in minutes.

**Default:** 10

### prReportStatus
Report pull request build status to Github.

**Default:** true

### prDirectives
Build directives for pull requests.

### prScan
If pull requests are to be scanned for.

**Default:** true

### prMerge
If pull request are to be merged with the target branch before built.

**Default:** true

### prExecType
How to execute pull requests for this job item.

**Default:** FORCE

### prStatusLabel
Description label from the build to show on github when reporting status for pull requests.
