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
    command = "script/launch-sisyphus.sh"
    if not os.path.exists(command):
        command = "launch-sisyphus.sh"
    os.system("{} {}".format(command, key))

class Gaia(object):
    def __init__(self, config):
        self.protocol = "http://"
        self.host = config.get('gaia_host', 'localhost:24442')

    def post(self, endpoint, data):
        url = self.protocol + self.host + '/' + endpoint
        data=json.dumps(data)
        return requests.post(url, data=data).json()

    def command(self, root, commands=None):
        if commands is None:
            commands = []
        return self.post('command', {
            'root': root,
            'commands': commands})

    def merge(self, root, processes=None):
        if processes is None:
            processes = []
        return self.post('merge', {
            'root': root,
            'processes': processes})

    def trigger(self, root):
        return self.post('trigger', {
            'root': root})

    def halt(self, root):
        return self.post('halt', {
            'root': root})

    def status(self, root):
        return self.post('status', {
            'root': root})

    def expire(self, root, keys):
        return self.post('expire', {
            'root': root,
            'expire': keys})

    def launch(self, keys):
        pool = multiprocessing.Pool(10)
        pool.map(launch_sisyphus, keys)


if __name__ == '__main__':
    print(sys.argv)
