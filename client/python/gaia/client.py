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

def process(key, command, inputs, outputs, var={}):
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

    def command(self, workflow_name, commands=None):
        if commands is None:
            commands = []
        return self._post('command', {
            'root': workflow_name,
            'commands': commands})

    def merge(self, workflow_name, processes=None):
        if processes is None:
            processes = []
        return self._post('merge', {
            'root': workflow_name,
            'processes': processes})

    def trigger(self, workflow_name):
        return self._post('trigger', {
            'root': workflow_name})

    def halt(self, workflow_name):
        return self._post('halt', {
            'root': workflow_name})

    def status(self, workflow_name):
        return self._post('status', {
            'root': workflow_name})

    def expire(self, workflow_name, keys):
        return self._post('expire', {
            'root': workflow_name,
            'expire': keys})

    def launch(self, keys):
        pool = multiprocessing.Pool(10)
        pool.map(launch_sisyphus, keys)


if __name__ == '__main__':
    print(sys.argv)
