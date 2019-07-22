# gaia python client

## running

To connect to a running Gaia server, find the host (open an ssh tunnel to it if needed) and do the following:

```
import gaia
config = {'gaia_host': 'localhost:24442'}
flow = gaia.Gaia(config)
```

Now that we have a reference to the client, we can call these methods to operate on a named workflow:

* command - see what Commands are available and add new Commands
* merge - update or add new Steps
* run - recompute dependencies and run outstanding Steps
* halt - stop a running workflow
* status - find out all information about a given workflow
* expire - recompute the given storage keys and Steps and all their dependent Steps

To just get something going, run the workflow in WCM:

```
commands = gaia.load_yaml('../../resources/test/wcm/wcm.commands.yaml')
wcm = gaia.load_yaml('../../resources/test/wcm/wcm.processes.yaml')
flow.command('wcm', commands)
flow.merge('wcm', wcm)
```

You will also need to launch some sisyphus workers. To do that:

```
flow.launch(['a', 'b'])
```

Launch more if you want : ) Give each a unique name.
They will deallocate 5 minutes after finishing their last Steps.

### command

Commands are the base level operations that can be run, specifically: command line programs in a given docker container image. Once defined, a Command can be invoked any number of times with a new set of vars, inputs, and outputs.

If you call this method with an empty or absent array argument, it will return all Commands in the named workflow.

```
flow.command('biostream')
# [{'name': 'ls', 'image': 'ubuntu', ...}, ...]
```

A Command is expressed as a dictionary with the following keys:

* name - name of the Command
* image - docker image to run in
* command - array of shell tokens to execute
* inputs - map of storage keys to internal paths inside the docker container where the Command's input files will be placed
* outputs - map of storage keys to internal paths inside the docker container where the Command's output files will be retrieved after the Command has run
* vars - map of var keys to string values to insert into Command tokens

They may also have an optional `stdout` key which specifies what path to place stdout output (so that stdout can be used as one of the outputs of the command).

```
flow.command('biostream', [...])
```

If `flow.command()` is called with an array of Command entries it will merge the given Commands into the workflow, thus adding and/or replacing Commands and triggering the recomputation of any Steps that refer to these Commands.

### merge

Once some Commands exist in the workflow you can start merging in Steps in order to trigger computation. Every Step names a Command and sets the Command's vars, inputs, and outputs. Inputs and outputs refer to paths in the data store while vars are strings that can be spliced into various parts of the Command's shell tokens.

Commands and Steps are kept in *workflows* which are entirely encapsulated from one another. Each workflow has its own data space with its own set of names and values.

To call the `merge` method, provide a workflow name and an array of Steps:

```
flow.merge('biostream', [{'name': 'ls-home', 'command': 'ls', 'inputs': {...}, ...}, ...])
```

Each Step is a dictionary with the following keys:

* name - name of the Step
* command - name of the Command to invoke
* inputs - map of input keys defined by the Command to keys in the data store to read the input files
* outputs - map of output keys from the Command to keys in the data store to write the output files after successfully invoking the Command
* vars - map of var keys to values. If this is an array it will create a Step for each element in the array with the given value

If this is a Step with a name that hasn't been seen before, it will create the Step entry and trigger the computation of outputs if the required inputs are available in the data store.  If the `key` of the Step being merged already exists in the workflow, that Step will be updated and recomputed, along with all Steps that depend on outputs from the updated Step in that workflow.

### run

The `run` method simply triggers the computation in the provided workflow if it is not already running:

```
flow.run('biostream')
```

### halt

The 'halt' method is the inverse of the 'run' method. It will immediately cancel all running tasks and stop the computation in the given workflow:

```
flow.halt('biostream')
```

### status

The `status` method provides information about a given workflow. There is a lot of information available, and it is formatted as a dictionary with these keys:

* state - a string representing the state of the overall workflow. Possible values are 'initialized', 'running', 'complete', 'halted', and 'error'.
* flow - contains a representation of the Steps in the workflow as a bipartite graph: `step` and `data`. Each entry has a `from` field containing Step or data names it is dependent on and a `to` field containing all Step or data names dependent on it. 
* data - contains a map of data keys to their current status: either missing or complete
* tasks - contains information about each task run through the configured executor. This will largely be executor dependent

```
flow.status('biostream')
```

### expire

The `expire` method accepts a workflow and a list of Steps names and data names (storage keys). It makes those Steps and dependent Steps have to run again.

```
flow.expire('biostream', ['ls-home', 'genomes', ...])
```
