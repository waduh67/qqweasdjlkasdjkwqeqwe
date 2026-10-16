#!/usr/bin/env python3
"""Permit the configured FTTH container to poll one local Fiberlab SNMP endpoint."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import shlex
import subprocess

CHAIN = 'FTTH-FIBERLAB'


def run(args, **kwargs):
    return subprocess.run(args, text=True, capture_output=True, timeout=15, **kwargs)


def read_json(args):
    result = run(args, check=True)
    return json.loads(result.stdout)


def bridge(network):
    details = read_json(['docker', 'network', 'inspect', network['NetworkID']])[0]
    if details['Driver'] != 'bridge':
        raise ValueError('Both containers must use Docker bridge networks')
    return details.get('Options', {}).get('com.docker.network.bridge.name') or 'br-' + details['Id'][:12]


def plan(config):
    target = str(ipaddress.IPv4Address(config['target_ip']))
    port = int(config.get('port', 161))
    if not 1 <= port <= 65535:
        raise ValueError('Invalid SNMP port')
    source = read_json(['docker', 'inspect', config['source_container']])[0]
    runtime = read_json(['docker', 'inspect', config['runtime_container']])[0]
    if not source['State']['Running'] or not runtime['State']['Running']:
        raise ValueError('Both containers must be running')
    source_route = read_json(['nsenter', '-t', str(source['State']['Pid']), '-n', 'ip', '-j', 'route', 'get', target])[0]
    host_route = read_json(['ip', '-j', 'route', 'get', target])[0]
    runtime_route = read_json(['nsenter', '-t', str(runtime['State']['Pid']), '-n', 'ip', '-j', 'route', 'get', target])[0]
    source_network = next(n for n in source['NetworkSettings']['Networks'].values() if n['IPAddress'] == source_route.get('prefsrc'))
    runtime_network = next(n for n in runtime['NetworkSettings']['Networks'].values() if n['IPAddress'] == host_route.get('gateway'))
    ingress, egress = bridge(source_network), bridge(runtime_network)
    if ingress == egress or host_route['dev'] != egress or runtime_route.get('type') != 'local':
        raise ValueError('The host route must lead to an address owned by the separate Fiberlab runtime')
    address = str(ipaddress.IPv4Address(source_network['IPAddress']))
    rules = [
        ['-A', CHAIN, '-s', address + '/32', '-d', target + '/32', '-i', ingress, '-o', egress,
         '-p', 'udp', '-m', 'udp', '--dport', str(port), '-m', 'conntrack', '--ctstate', 'NEW,ESTABLISHED', '-j', 'ACCEPT'],
        ['-A', CHAIN, '-s', target + '/32', '-d', address + '/32', '-i', egress, '-o', ingress,
         '-p', 'udp', '-m', 'udp', '--sport', str(port), '-m', 'conntrack', '--ctstate', 'ESTABLISHED', '-j', 'ACCEPT'],
    ]
    return {'source': address, 'target': target, 'port': port, 'ingress': ingress, 'egress': egress, 'rules': rules}


def apply(desired):
    # Fail before changing anything if Docker has not initialized its user chain.
    run(['iptables', '-w', '5', '-S', 'DOCKER-USER'], check=True)
    current = run(['iptables', '-w', '5', '-S', CHAIN])
    if current.returncode:
        run(['iptables', '-w', '5', '-N', CHAIN], check=True)
    existing = [shlex.split(line) for line in current.stdout.splitlines() if line.startswith('-A ')]
    if existing != desired['rules']:
        # Only replace our chain, atomically; preserve Docker and other applications' rules.
        restore = '\n'.join(['*filter', '-F ' + CHAIN, *[shlex.join(rule) for rule in desired['rules']], 'COMMIT', ''])
        run(['iptables-restore', '-w', '5', '--noflush'], input=restore, check=True)
    jump = ['DOCKER-USER', '-j', CHAIN]
    if run(['iptables', '-w', '5', '-C', *jump]).returncode:
        run(['iptables', '-w', '5', '-I', 'DOCKER-USER', '1', '-j', CHAIN], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, type=Path)
    parser.add_argument('--apply', action='store_true', help='Apply the plan; otherwise only display it')
    args = parser.parse_args()
    if os.geteuid() != 0:
        parser.error('Run as root to inspect the container network namespaces')
    desired = plan(json.loads(args.config.read_text()))
    if args.apply:
        apply(desired)
    print(json.dumps(desired))


if __name__ == '__main__':
    main()
