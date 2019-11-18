# Stage properties
For each stage created using the generic stage generation classes (see [stage classes](../../src/org/hitachivantara/ci/stages) a couple properties are provided to control behaviour. The property keys are based on the stage upper case id. Replace **STAGEID** with the stage id you wish to configure.

### RUN_STAGE_STAGEID
Property to control if the stage is to be executed.

**Default:** false

### PARALLEL_SIZE_STAGEID
Property to control the max parallel items to be executed for the stage. A value of 0 or undefined means unbound.

**Default:** 0
