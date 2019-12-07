from __future__ import absolute_import, division, print_function

import os
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

def launch_sisyphus(options):
    command = os.path.join("script", "launch-sisyphus.sh")
    if not os.path.exists(command):
        command = "launch-sisyphus.sh"

    worker = options.get('worker', 'sisyphus')
    metadata = options.get('metadata', {})

    launch_metadata = ''
    if metadata:
        metadata_fields = []
        for key, value in metadata.items():
            metadata_fields.append('{}={}'.format(key, value))
        launch_metadata = ','.join(metadata_fields)

    os.system("{} {} {}".format(command, worker, launch_metadata))


class Gaia(object):
    def __init__(self, config=None):
        # type: (Optional[dict]) -> None
        if not config:
            config = {}
        self.protocol = "http://"
        self.host = config.get('gaia_host', 'localhost:24442')

    def _post(self, endpoint, data):
        # type: (str, dict) -> dict
        url = self.protocol + self.host + '/' + endpoint
        data=json.dumps(data)
        return requests.post(url, data=data).json()

    def command(self, workflow, commands=None):
        # type: (str, Optional[List[dict]]) -> dict
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
        # type: (str, Optional[List[dict]]) -> dict
        """Merge a list of Steps into the named workflow and start running the
        Steps that can run. Return a list of the workflow's Steps."""
        if steps is None:
            steps = []
        assert isinstance(workflow, str)
        assert isinstance(steps, list)

        return self._post('merge', {
            'workflow': workflow,
            'steps': steps})

    def upload(self, workflow, properties, commands, steps):
        # type: (str, Dict[str, str], List[dict], List[dict]) -> dict
        """Upload a new workflow. `properties` should include 'owner'."""
        assert isinstance(workflow, str)
        assert isinstance(properties, dict)
        assert isinstance(commands, list)
        assert isinstance(steps, list)

        return self._post('upload', {
            'workflow': workflow,
            'properties': properties,
            'commands': commands,
            'steps': steps})

    def run(self, workflow):
        # type: (str) -> dict
        """Start running the named workflow. Usually this happens automatically."""
        assert isinstance(workflow, str)
        return self._post('run', {
            'workflow': workflow})

    def halt(self, workflow):
        # type: (str) -> dict
        """Stop running the named workflow."""
        assert isinstance(workflow, str)
        return self._post('halt', {
            'workflow': workflow})

    def status(self, workflow, debug=False):
        # type: (str, Optional[bool]) -> dict
        """Return all the status info for the named workflow."""
        assert isinstance(workflow, str)
        return self._post('status', {
            'workflow': workflow,
            'debug': debug})

    def expire(self, workflow, keys):
        # type: (str, List[str]) -> dict
        """Expire outputs and downstream dependencies given storage keys and/or Step names."""
        assert isinstance(workflow, str)
        assert isinstance(keys, list), 'need a list of storage keys and/or Step names'

        return self._post('expire', {
            'workflow': workflow,
            'expire': keys})

    def workflows(self):
        # type () -> dict
        """List the current workflows, with summary info on each one."""
        return self._post('workflows', {})

    def launch(self, names, metadata=None):

        # type: (List[str]) -> None
        """Launch the named Sisyphus worker nodes."""
        assert isinstance(names, list), 'need a list of worker names'
        args = [
            {'worker': worker, 'metadata': metadata}
            for worker in names]

        pool = multiprocessing.Pool(10)
        pool.map(launch_sisyphus, args)

    def pull_inputs(self, workflow, task_name, root=None, path_fn=pop_path):
        # type: (str, str, Optional[str], Callable[[str], str]) -> None
        """
        Pull the inputs for a given task. Also prints the command afterwards.

        Args:
            workflow: name of the workflow.
            task_name: name of the task we want inputs for.
            root: root of the path to sync files to locally.
            path_fn: function to call on the task path before using locally.

        Example:
            flow.pull_inputs(
                'WCM_mialydefelice_20190812.122845',
                'simulation_Var0_Seed57_Gen19_Cell0',
                root='/home/spanglry/Code/wcEcoli')
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


def main():
    parser = argparse.ArgumentParser(
        description='Command line testing the Gaia client API.')
    pp = pprint.PrettyPrinter(indent=4)
    parser.add_argument(
        'command',
        choices=['status', 'upload', 'workflows', 'expire', 'launch'],
        help='gaia endpoint to invoke')
    parser.add_argument(
        'workflow',
        help='name of the workflow to operate on')
    parser.add_argument(
        '--host',
        default='localhost:24442',
        help='address for gaia host')
    parser.add_argument(
        '--path',
        type=str,
        default='',
        help='Path to input files (with file prefix) or file/step keys to expire')
    parser.add_argument(
        '--extension',
        type=str,
        choices=['json', 'yaml'],
        default='json',
        help='extension for input files')
    parser.add_argument(
        '--workers',
        type=int,
        default=0,
        help='number of workers to launch')

    args = parser.parse_args()

    flow = Gaia({'gaia_host': args.host})

    if args.command == 'status':
        status = flow.status(args.workflow)['status']
        print('Status:')
        pp.pprint(status)

        commands = flow.command(args.workflow)
        print('\nCommands:')
        pp.pprint(commands)

        steps = flow.merge(args.workflow)
        print('\nSteps:')
        pp.pprint(steps)

    elif args.command == 'upload':
        if not args.path:
            parser.error('No --path specified')
        if args.extension == 'json':
            commands = json.load(open('{}commands.json'.format(args.path)))
            steps = json.load(open('{}steps.json'.format(args.path)))
        else:
            commands = load_yaml('{}commands.yaml'.format(args.path))
            steps = load_yaml('{}steps.yaml'.format(args.path))

        print(commands)
        print(steps)

        properties = {
            'owner': os.environ['USER'],
            }
        flow.upload(
            workflow=args.workflow,
            properties=properties,
            commands=commands,
            steps=steps)

    elif args.command == 'workflows':
        workflows = flow.workflows()
        pp.pprint(workflows)

    elif args.command == 'expire':
        if not args.path:
            parser.error('No --path specified')
        keys = args.path.split(',')
        response = flow.expire(args.workflow, keys)
        pp.pprint(response)

    elif args.command == 'launch':
        workers = ['{}-{}'.format(args.workflow, i) for i in range(args.workers)]
        metadata = {
			'workflow': args.workflow}

        flow.launch(workers, metadata)



if __name__ == '__main__':
    main()

