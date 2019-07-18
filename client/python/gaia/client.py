from __future__ import absolute_import, division, print_function

import os
import sys
import json
import yaml
import requests
import multiprocessing


def load_yaml(path):
    handle = open(path)
    load = yaml.safe_load(handle)
    handle.close()
    return load

def step(key, command, inputs, outputs, var={}):
    out = {
        key: key,
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
        if commands is None:
            commands = []
        return self._post('command', {
            'workflow': workflow,
            'commands': commands})

    def merge(self, workflow, steps=None):
        if steps is None:
            steps = []
        return self._post('merge', {
            'workflow': workflow,
            'steps': steps})

    def run(self, workflow):
        return self._post('run', {
            'workflow': workflow})

    def halt(self, workflow):
        return self._post('halt', {
            'workflow': workflow})

    def status(self, workflow):
        return self._post('status', {
            'workflow': workflow})

    def expire(self, workflow, keys):
        return self._post('expire', {
            'workflow': workflow,
            'expire': keys})

    def launch(self, keys):
        pool = multiprocessing.Pool(10)
        pool.map(launch_sisyphus, keys)


if __name__ == '__main__':
    print(sys.argv)
