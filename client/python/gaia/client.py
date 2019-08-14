from __future__ import absolute_import, division, print_function

import os
import sys
import json
import yaml
import pprint
import argparse
import requests
from typing import Dict, List, Optional, Callable
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

def to_gs(key):
    parts = key.split(':')
    bucket = parts[0]
    path = ':'.join(parts[1:])
    return 'gs://{}/{}'.format(bucket, path)

def pop_path(path):
    if path == '':
        return path

    parts = path.split('/')
    if parts[0] == '':
        parts = parts[1:]

    return '/'.join(parts[1:])

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
        # type: (str, Optional[List[dict]]) -> Dict[Dict[dict]]
        """Add a list of Commands to the named workflow. Return a dict containing
        all of them, {'commands': {name: command, ...}}."""
        if commands is None:
            commands = []
        assert isinstance(workflow, str)
        assert isinstance(commands, list)

        return self._post('command', {
            'workflow': workflow,
            'commands': commands})

    def merge(self, workflow, steps=None):
        # type: (str, Optional[List[dict]]) -> List[dict]
        """Merge a list of Steps into the named workflow and start running the
        Steps that can run. Return a list of the workflow's Steps."""
        if steps is None:
            steps = []
        assert isinstance(workflow, str)
        assert isinstance(steps, list)

        return self._post('merge', {
            'workflow': workflow,
            'steps': steps})

    def run(self, workflow):
        # type: (str) -> None
        """Start running the named workflow. Usually this happens automatically."""
        assert isinstance(workflow, str)
        return self._post('run', {
            'workflow': workflow})

    def halt(self, workflow):
        # type: (str) -> None
        """Stop running the named workflow."""
        assert isinstance(workflow, str)
        return self._post('halt', {
            'workflow': workflow})

    def status(self, workflow):
        # type: (str) -> dict
        """Return all the status info for the named workflow."""
        assert isinstance(workflow, str)
        return self._post('status', {
            'workflow': workflow})

    def expire(self, workflow, keys):
        # type: (str, List[str]) -> None
        """Expire outputs and downstream dependencies given storage keys and/or Step names."""
        assert isinstance(workflow, str)
        assert isinstance(keys, list), 'need a list of storage keys and/or Step names'

        return self._post('expire', {
            'workflow': workflow,
            'expire': keys})

    def launch(self, names):
        # type: (List[str]) -> None
        """Launch the named Sisyphus worker nodes."""
        assert isinstance(names, list), 'need a list of worker names'
        pool = multiprocessing.Pool(10)
        pool.map(launch_sisyphus, names)

    def pull_inputs(self, workflow, task_name, root=None, path_fn=pop_path):
        # type: (str, str, Optional[str], Callable[[str], str]) -> None
        """
        Pull the inputs for a given task. Also prints the command afterwards.

        Args:
            workflow: name of the workflow.
            task_name: name of the task we want inputs for.
            root: root of the path to sync files to locally.
            path_fn: function to call on the task path before using locally.
        """
        tasks = self.status(workflow)['status']['tasks']
        if task_name in tasks:
            task = tasks[task_name]
            for key, full_path in task['inputs']:
                gs = to_gs(key)
                path = path_fn(full_path)
                if root:
                    path = os.path.join(root, path)

                os.system('mkdir -p {}'.format(path))
                os.system('gsutil -m rsync -r {} {}'.format(gs, path))
            print(' '.join(task['command']))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    pp = pprint.PrettyPrinter(indent=4)
    parser.add_argument(
        'command',
        help='gaia command to perform')
    parser.add_argument(
        'workflow',
        help='name of workflow to operate on')
    parser.add_argument(
        '--host',
        default='localhost:24442',
        help='address for gaia host')
    args = parser.parse_args()

    flow = Gaia({
        'gaia_host': args.host})
    if args.command == 'status':
        status = flow.status(args.workflow)
        output = status
        output = {
            'steps': status['status']['tasks'],
            'state': status['status']['state'],
            'waiting': status['status']['waiting']}

    pp.pprint(output)
