[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

# gaia

manage computation of a network of dependent processes

![GAIA](https://github.com/prismofeverything/gaia/blob/master/resources/gaia.jpg)

## quickstart

To run a Gaia server, you need a few things:

* [Kafka](https://kafka.apache.org/quickstart)
* [RabbitMQ](https://www.rabbitmq.com/download.html)
* [gcloud](https://cloud.google.com/sdk/docs/quickstarts)
* [leiningen](https://leiningen.org/)

In addition, you will need to set up `gcloud` to authorize with google. This means running `gcloud auth login` at some point with an account that is tied to google cloud, and you have to follow the directions [here](https://cloud.google.com/storage/docs/reference/libraries) for "setting up authentication" to access the cloud resources programmatically.

Once all this is in place you can run

    lein run

at the command line to start Gaia. 

## idea

Gaia tracks a set of keys representing files to be computed, and the network of processes that compute them. Its main focus is a data store that holds these keys, which can begin life seeded with prior information, and which it progressively fills as new processes trigger and contribute their outputs.

At each cycle, Gaia compares the keys that are present to the inputs of processes who compute keys that are missing, triggering any processes found. Once these missing keys are computed, the cycle is run again and again until either all keys are computed or no more processes can be run.

Gaia has both a server to launch these computations and a client to interact with the server, trigger new processes or commands, or gather information about the status of each process or data key (initialized/running/error/complete).

## client

The python client for Gaia lives at `client/python/gaia.py`. To connect to a running Gaia instance, find the host and do the following:

```
import gaia
host = "localhost:24442"
flow = gaia.Gaia(host)
```

Now that we have a reference to the client, there are several methods we can call.

* command - see what commands are available and add new commands
* merge - update or add new processes into the given namespace
* trigger - recompute dependencies and launch outstanding processes in a namespace
* halt - stop a running namespace
* status - find out all information about a given namespace
* expire - recompute a given key (process or data) and all of its dependent processes

All of these methods are relative to a given namespace (root) except for `command`, which operates globally to all namespaces.

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

## server

Gaia requires three main components for its operation:

* Executor - This is the service that will be managing the actual execution of all the tasks Gaia triggers. Currently [Sisyphus](https://github.com/CovertLab/sisyphus) is the target.
* Bus - In order to determine when a task has finished running, Gaia subscribes to an event bus containing messages from the Executor. So far this is [Kafka](https://kafka.apache.org/), but additional busses could easily be supported.
* Store - The data store is where the inputs and results from each of the running tasks is stored. Currently Gaia supports filesystem, google cloud storage and [Openstack Swift](https://wiki.openstack.org/wiki/Swift) data stores.

### config

Here is an example of Gaia configuration (living under `resources/config/gaia.clj`):

```clj
{:kafka
 {:base
  {:host "localhost"        ;; wherever your kafka cluster lives
   :port "9092"}}

 :executor
 {:target "sisyphus"
  :host "http://localhost:19191"
  :path ""}

 :store
 {:type :swift              ;; can also be :file
  :root ""                  ;; this will prefix all keys in the store
  :container "biostream"
  :username "swiftuser"
  :password "password"
  :url "http://10.96.11.20:5000/v2.0/tokens"
  :tenant-name "CCC"
  :tenant-id "8897b62d8a8d45f38dfd2530375fbdac"
  :region "RegionOne"}

 :flow                      ;; path to set of commands and processes files
 {:path "resources/test/triangle/triangle"}}
```

Once this is all established, you can start Gaia by typing

    lein run --config resources/config/gaia.clj

in the root level of the project (or a path to whatever config file you want to use).

### commands.yaml

You don't need to supply commands and processes through a yaml file (you can post them to the HTTP endpoint), but you can if you want.

The format of this file is a set of keys with a description of how to run the command. This description maps onto the Task Execution Schema with some additional information about how to translate inputs and outputs into keys in the data store. Here is an example:

```yaml
    ensembl-transform:
      image: spanglry/ensembl-transform
      command: ["go", "run", "/command/run.go", "/in/gaf.gz"]
      inputs:
        GAF_GZ: /in/gaf.gz
      outputs:
        TRANSCRIPT: /out/Transcript.json
        GENE: /out/Gene.json
        EXON: /out/Exon.json
```

Under the `inputs` and `outputs` lives a map of keys to locations in the local file system where the computation took place.

### processes.yaml

These are invocations of commands defined in the `commands.yaml` file. Each one refers to a single command and provides the mapping of inputs and outputs to keys in the data store.

Here is an example of an invocation of the previous command:

```
- key: ensembl-transform
  command: ensembl-transform
  inputs:
    GAF_GZ: source/ensembl.gtf.gz
  outputs:
    TRANSCRIPT: biostream/ensembl/ensembl.Transcript.json
    GENE: biostream/ensembl/ensembl.Gene.json
    EXON: biostream/ensembl/ensembl.Exon.json
```

In the `inputs` and `outputs` maps, the keys represent those declared by the command, and the values represent what keys to store the results under in the data store. These keys can then be declared as inputs to other processes.

### vars

There is one additional concept of a `var`: values that do not come from files but are instead directly supplied by the process invocation. Here is an example.

The command is defined like so:

```
curl-extract:
  repo: https://github.com/biostream/curl-extract
  image_name: appropriate/curl
  cmd: ["curl", "{{URL}}", "-o", "/tmp/out"]
  outputs:
    OUT: /tmp/out
```

Notice the second argument to curl is embedded in curly braces. This signifies that the value will be supplied directly during the invocation. Here is how that happens:

```
- key: cancerrxgene-cellline-extract
  command: curl-extract
  vars:
    URL: ftp://ftp.sanger.ac.uk/pub/project/cancerrxgene/releases/release-6.0/Cell_Lines_Details.xlsx
  outputs:
    OUT: source/crx/cell-lines.xlsx
```

Here under the `vars` key we specify the `URL` which will be substituted into the command.