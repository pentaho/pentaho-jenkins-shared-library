# Minion Pipelines Configuration Properties

Properties used by pipelines to configure how the minion jobs perform and are configured.

### USE_MINION_JOBS
Boolean property that enables the use of minion jobs to execute the pipeline.

### USE_MINION_MULTIBRANCH_JOBS
Boolean property that enables the creation of multibranch jobs for each job item configured in the build.

### MINION_PIPELINE_TEMPLATE
This property allows a path to a Velocity template to be provided so that the minion pipelines can make use of it.
Currently the parameters passed to the template are the following.

##### libraries
A list of the shared libraries used by the orchestrating pipeline so they can be used when constructing the minion pipeline. 

##### properties
A Map containing the build properties of the orchestrating job with the values already adapted for the minion job.