# Builder properties
For each implemented builder a few properties are available to define additional behaviour. The key for these properties is based on the builder defined name. Replace **BUILDER** with the builder name you wish to configure.

### BUILDER_RESOLVE_REPO_URL
URL to resolve dependency artifacts from for the builder.
  
### BUILDER_PUBLIC_RELEASE_REPO_URL
URL to deploy public release artifacts.

### BUILDER_PUBLIC_SNAPSHOT_REPO_URL
URL to deploy public snapshot artifacts.

### BUILDER_PRIVATE_RELEASE_REPO_URL
URL to deploy private release artifacts.

### BUILDER_PRIVATE_SNAPSHOT_REPO_URL
URL to deploy private snapshot artifacts.

### BUILDER_OPTS
The builder options.

### BUILDER_SETTINGS
The path to the builder settings file.

### BUILDER_DEFAULT_DIRECTIVES 
Default builder directives.

### BUILDER_PR_DEFAULT_DIRECTIVES
Default builder Pull Request directives.

### BUILDER_DEFAULT_COMMAND_OPTIONS
Default builder command options.

### BUILDER_PR_DEFAULT_COMMAND_OPTIONS
Default builder command options for Pull Requests.

### JENKINS_BUILDER_FOR_BUILDS
The jenkins build tool id for this builder.
