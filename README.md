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

## gcloud setup

If you want to control Gaia from a google cloud instance, allocate a new instance, then ssh in:

    gcloud compute ssh new-instance

You have to do some setup to have the right credentials for executing gcloud commands from this new VM. First, download the credentials as JSON from the gcloud console (in the IAM > Service Accounts). We have a `sisyphus` service account for this with permissions to create and delete instances and access storage. Once you get the key in put it somewhere (I have it under ~/.cloud.json). Then you can do the following:

    echo "GOOGLE_APPLICATION_CREDENTIALS=$HOME/.cloud.json" >> /etc/environment

(log out and log back in):

    gcloud auth activate-service-account sisyphus@allen-discovery-center-mcovert.iam.gserviceaccount.com --key-file ~/.cloud.json

Once here, you want to be able to send processes and commands to Gaia. First, clone this repo and go to the client:

    git clone https://github.com/prismofeverything/gaia.git
    cd gaia/client/python
    pip install -r requirements.txt
    ipython

(you might have to install ipython, pip etc)

## client

The python client for Gaia lives at `client/python/gaia.py`.

To connect to a running Gaia server, find the host (open an ssh tunnel to it if needed) and do the following:

```
import gaia
config = {'gaia_host': 'localhost:24442'}
flow = gaia.Gaia(config)
```

Now that we have a reference to the client, we can call these methods:

* workflows - list current workflows with summary information about each one
* upload - upload a new workflow
* command - see what Commands are in a workflow and add new Commands
* merge - update or add new Steps to a workflow
* run - recompute dependencies and run outstanding Steps
* halt - stop a running workflow
* status - find out all information about a given workflow
* expire - recompute the given storage keys and Steps and all their dependent Steps

### workflows

The `workflows` method lists the current workflows with summary info about each one.

```python
flow.workflows()
```

### upload

To get something running, upload a test workflow:

```
commands = gaia.load_yaml('../../resources/test/wcm/wcm.commands.yaml')
steps = gaia.load_yaml('../../resources/test/wcm/wcm.processes.yaml')
flow.upload('crick_wcm_20191130.121500', dict(owner='crick'), commands, steps)
```

Each workflow needs a unique name. Best practice is to construct a name in the
form `owner_program_datetime`.

You will also need to launch some sisyphus workers. To do that
[Note: This part is in flux]:

```
flow.launch(['a', 'b'])
```

Launch more if you want : ) Give each a unique name.
They will deallocate 5 minutes after finishing their last Steps.

### command

Commands are the base level operations that can be run, specifically: command line programs in a given docker container image. Once defined, a Command can be invoked any number of times with a new set of vars, inputs, and outputs.

If you call this method with an empty or absent array argument, it will just return the Commands in the named workflow.

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

If this is a Step with a name that hasn't been seen before, it will create the Step entry and trigger the computation of outputs if the required inputs are available in the data store.  If the `name` of the Step being merged already exists in the workflow, that Step will be updated and recomputed, along with all Steps that depend on outputs from the updated Step in that workflow.

### run

The `run` method simply triggers the computation in the provided workflow if it is not already running:

```
flow.run('biostream')
```

### halt

The `halt` method is the inverse of the `run` method. It will immediately cancel all running tasks and stop the computation in the given workflow:

```
flow.halt('biostream')
```

### status

The `status` method provides debugging information about a given workflow. There is a lot of information available. It's formatted as a dictionary with these keys:

* state - a string representing the state of the overall workflow. Possible values are 'initialized', 'running', 'complete', 'halted', and 'error'.
* flow - contains a representation of the Steps in the workflow as a bipartite graph: `step` and `data`. Each entry has a `from` field containing Step or data names it is dependent on and a `to` field containing all Step or data names dependent on it. 
* data - contains a map of data keys to their current status: either missing or complete
* tasks - contains information about each task run through the configured executor. This will largely be executor dependent

```
flow.status('biostream')
```

### expire

The `expire` method accepts a workflow and a list of Steps names and/or storage paths (storage keys). It makes those Steps and their dependent Steps have to run again.

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