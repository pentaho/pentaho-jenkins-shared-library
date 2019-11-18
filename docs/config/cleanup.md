## Cleanup Properties
These properties allow configuration of the default cleanup stage tasks that can be included in a pipeline. 

### CLEAN_CACHES_REGEX
Regex pattern to match against the cached items for deletion.

**Default:** .\*-SNAPSHOT.\*

### CLEAN_ALL_CACHES
If true signals the pipeline to wipe all caches.

**Default:** false

### CLEAN_SCM_WORKSPACES
If true the scm checked out files will be wiped.

**Default:** false

### CLEAN_BUILD_WORKSPACE
If true wipes the build workspace.

**Default:** false
