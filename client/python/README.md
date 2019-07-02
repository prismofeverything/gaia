# gaia python client

## running

To connect to a running Gaia instance, find the host and do the following:

```
import gaia
config = {
    'gaia_host': '10.138.0.21:24442',
    'kafka_host': '10.138.0.2:9092'}
flow = gaia.Gaia(config)
```

Now that we have a reference to the client, there are several methods we can call.

* command - see what commands are available and add new commands
* merge - update or add new processes into the given namespace
* trigger - recompute dependencies and launch outstanding processes in a namespace
* halt - stop a running namespace
* status - find out all information about a given namespace
* expire - recompute a given key (process or data) and all of its dependent processes

All of these methods are relative to a given namespace (root) except for `command`, which operates globally to all namespaces.

To just get something going, run the workflow in WCM:

```
wcm = gaia.load_yaml('../../resources/test/wcm/wcm.processes.yaml')
flow.merge('wcm', wcm)
```

You will also need to launch some sisyphus workers. To do that:

```
flow.launch('worker-a')
```

Launch more if you want : ) Give each a unique key. They will deallocate after 5 minutes of inactivity.

Once your workflow is running, you can listen to the logs as they appear:

```
flow.listen()
```

### command

Commands are the base level operations that can be run, and generally map on to command line programs invoked from a given docker container. Once defined, a command can be invoked any number of times with a new set of vars, inputs and outputs.

If you call this method with an empty array, it will return all commands currently registered in the system.

```
flow.command([])
# [{'key': 'ls', 'image': 'ubuntu', ...}, ...]
```

All commands are in the Gaia command format and contain the following keys:

* key - name of command
* image - docker image containing command
* command - array containing elements of command to be run
* inputs - map of keys to local paths in the docker image where input files will be placed
* outputs - map of keys to local paths where output files are expected to appear once the command has been run
* vars - map of keys to string variables that will be provided on invocation

They may also have an optional `stdout` key which specifies what path to place stdout output (so that stdout can be used as one of the outputs of the command).

If this method is called with an array populated with command entries it will merge this set of commands into the global set and update any commands that may already be present, triggering the recomputation of any processes that refer to the updated command.

### merge

Once some commands exist in the system you can start merging in processes in order to trigger computation. Every process refers to a command registered in the system, and defines the relevant vars, inputs and outputs to pass to the command. Inputs and outputs refer to paths in the data store, while vars are strings that are passed directly as values and can be spliced into various parts of the invocation.

Processes are partitioned by *namespaces* which are entirely encapsulated from one another. Each namespace represents its own data space with its own set of keys and values. Every method besides `command` is relative to the provided namespace, while commands are available to the entire system.

To call this method, provide a namespace key and an array of process entries:

```
flow.merge('biostream', [{'key': 'ls-home', 'command': 'ls', 'inputs': {...}, ...}, ...])
```

Each process entry has the following keys:

* key - unique identifier for process
* command - reference to which command in the system is being invoked
* inputs - map of input keys defined by the command to keys in the data store where the inputs will come from
* outputs - map of output keys from the command to keys in the data store where the output will be placed after successfully completing the command
* vars - map of var keys to values the var will take. If this is an array it will create a process for each element in the array with the given value for the var

If this is a process with a key that hasn't been seen before, it will create the process entry and trigger the computation of outputs if the required inputs are available in the data store.  If the `key` of the process being merged already exists in the namespace, that process will be updated and recomputed, along with all processes that depend on outputs from the updated process in that namespace.

### trigger

The `trigger` method simply triggers the computation in the provided namespace if it is not already running:

```
flow.trigger('biostream')
```

### halt

The 'halt' method is the inverse of the 'trigger' method. It will immediately cancel all running tasks and stop the computation in the given namespace:

```
flow.halt('biostream')
```

### status

The `status` method provides information about a given namespace. There is a lot of information available, and it is partitioned into four keys:

* state - a single string representing the state of the overall namespace. Possible values are 'initialized', 'running', 'complete', 'halted' and 'error'.
* flow - contains a representation of the defined processes in the namespace as a bipartite graph: process and data. There are two keys, `process` and `data` which represent the two halves of this bipartite graph. Each entry has a `from` field containing keys it is dependent on and a `to` field containing all keys dependent on it. 
* data - contains a map of data keys to their current status (either missing or complete)
* tasks - contains information about each task run through the configured executor. This will largely be executor dependent

```
flow.status('biostream')
```

### expire

The `expire` method accepts a namespace and a list of keys of either processes or data, and recomputes each key and every process that depends on any of the given keys.

```
flow.expire('biostream', ['ls-home', 'genomes', ...])
```
