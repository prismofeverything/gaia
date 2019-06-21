import sys
import yaml
import json
import requests

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

class Gaia(object):
    def __init__(self, host):
        self.protocol = "http://"
        self.host = host

    def post(self, endpoint, data):
        url = self.protocol + self.host + '/' + endpoint
        data=json.dumps(data)
        return requests.post(url, data=data).json()

    def command(self, commands=[]):
        return self.post('command', {
            'commands': commands})

    def merge(self, root, processes):
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

class Flow(object):
	pass

if __name__ == '__main__':
	print(sys.argv)
