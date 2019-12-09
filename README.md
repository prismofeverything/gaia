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
It sends HTTP requests to the Gaia server.

See its [README](client/python/README.md) for details.
The README and `gaia.py` document Gaia's HTTP API.


## server

Gaia requires three main components for its operation:

* Executor - This is the service that will be managing the actual execution of all the tasks Gaia triggers. Currently [Sisyphus](https://github.com/CovertLab/sisyphus) is the target.
* Bus - In order to determine when a task has finished running, Gaia subscribes to an event bus containing messages from the Executor. So far this is [Kafka](https://kafka.apache.org/), but additional busses could easily be supported.
* Store - The data store is where the inputs and results from each of the running tasks is stored. Currently Gaia supports google cloud storage, filesystem, and [Openstack Swift](https://wiki.openstack.org/wiki/Swift) data stores.

### config

Here is an example of Gaia configuration (living under `resources/config/gaia.clj`):

```clj
{:kafka
 {:host "localhost"
  :port "9092"
  :status-topic "sisyphus-status"}

 :rabbit
 {:host "localhost"}

 :executor
 {:target "sisyphus"
  :path ""}

 :store
 {:type :cloud
  :root ""}

 :flow
 {:path "resources/test/wcm/wcm"}}
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