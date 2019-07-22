from __future__ import absolute_import, division, print_function

import os
import sys
import json
import yaml
import requests
from typing import List, Optional
import multiprocessing


def load_yaml(path):
    handle = open(path)
    load = yaml.safe_load(handle)
    handle.close()
    return load


def step(name, command, inputs, outputs, var=()):
    out = {
        name: name,
        command: command,
        outputs: outputs}

    if len(inputs) > 0:
        out['inputs'] = inputs
    if len(var) > 0:
        out['vars'] = var

    return out


def launch_sisyphus(key):
    command = os.path.join("script", "launch-sisyphus.sh")
    if not os.path.exists(command):
        command = "launch-sisyphus.sh"
    os.system("{} {}".format(command, key))


class Gaia(object):
    def __init__(self, config):
        self.protocol = "http://"
        self.host = config.get('gaia_host', 'localhost:24442')

    def _post(self, endpoint, data):
        url = self.protocol + self.host + '/' + endpoint
        data=json.dumps(data)
        return requests.post(url, data=data).json()

    def command(self, workflow, commands=None):
        # type: (str, Optional[List[str]]) -> List[str]
        """Add a list of Commands to the named workflow. Return all of its Commands."""
        if commands is None:
            commands = []
        return self._post('command', {
            'workflow': workflow,
            'commands': commands})

    def merge(self, workflow, steps=None):
        # type: (str, Optional[List[str]]) -> List[str]
        """Merge a list of Steps into the named workflow. Return all of its Steps."""
        if steps is None:
            steps = []
        return self._post('merge', {
            'workflow': workflow,
            'steps': steps})

    def run(self, workflow):
        # type: (str) -> None
        """Start running the named workflow. Usually this happens automatically."""
        return self._post('run', {
            'workflow': workflow})

    def halt(self, workflow):
        # type: (str) -> None
        """Stop running the named workflow."""
        return self._post('halt', {
            'workflow': workflow})

    def status(self, workflow):
        # type: (str) -> dict
        """Return all the status info for the named workflow."""
        return self._post('status', {
            'workflow': workflow})

    def expire(self, workflow, keys):
        # type: (str, List[str]) -> None
        """Expire outputs and downstream dependencies given storage keys and/or Step names."""
        return self._post('expire', {
            'workflow': workflow,
            'expire': keys})

    def launch(self, names):
        # type: (List[str]) -> None
        """Launch the named Sisyphus worker nodes."""
        pool = multiprocessing.Pool(10)
        pool.map(launch_sisyphus, names)


if __name__ == '__main__':
    print(sys.argv)
