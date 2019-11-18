# SCM Properties
Properties that configure checkout behaviour.

### SHALLOW_CLONE
Boolean property to define if the checkous are to be shallow instead of cloning the full project history.

**Default:** true

### CHECKOUT_DEPTH
When performing a shallow clone this property defines how many commits should be fetched.

**Default:** 20

### CHECKOUT_TIMEOUT_MINUTES
The max amount of time a checkout operation should take before being forcefully terminated.

**Default:** 10
