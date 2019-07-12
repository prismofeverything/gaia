import os
import sys
import json
import uuid
import yaml
import requests
import multiprocessing

from confluent_kafka import Producer, Consumer, KafkaError

def delivery_report(err, msg):
    """
    This is a utility method passed to the Kafka Producer to handle the delivery
    of messages sent using `send(topic, message)`. 
    """
    if err is not None:
        print('message delivery failed: {}'.format(msg))
        print('failed message: {}'.format(err))

def initialize_consumer(config):
    consumer = Consumer({
        'bootstrap.servers': config['host'],
        'enable.auto.commit': True,
        'group.id': uuid.uuid1(),
        'default.topic.config': {
            'auto.offset.reset': 'latest'}})

    consumer.subscribe(config['subscribe'])
    return consumer

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
        self.consumer = initialize_consumer({
            'host': config.get('kafka_host', '127.0.0.1:9092'),
            'subscribe': [
                config.get('log_topic', 'sisyphus-log'),
                config.get('status_topic', 'sisyphus-status')]})

    def post(self, endpoint, data):
        url = self.protocol + self.host + '/' + endpoint
        data=json.dumps(data)
        return requests.post(url, data=data).json()

    def command(self, root, commands=[]):
        return self.post('command', {
            'root': root,
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

    def launch(self, keys):
        pool = multiprocessing.Pool(10)
        pool.map(launch_sisyphus, keys)

    def receive(self, topic, message):
        print("{}: {}".format(topic, message))

    def listen(self):
        self.running = True
        while self.running:
            raw = self.consumer.poll(timeout=1.0)

            if raw is None:
                continue
            if raw.error():
                if raw.error().code() == KafkaError._PARTITION_EOF:
                    continue
                else:
                    print('Error in kafka consumer:', raw.error())
                    self.running = False

            else:
                message = json.loads(raw.value())
                if not message:
                    continue

                self.receive(raw.topic(), message)

class Flow(object):
    pass

if __name__ == '__main__':
    print(sys.argv)
