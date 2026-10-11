#!/usr/bin/env python3
"""Current-tenant receipt, transfer, count and isolation checks for packaged images."""
import importlib.util
from pathlib import Path
from uuid import uuid4

from http_support import check

spec = importlib.util.spec_from_file_location('warehouse_legacy_http_helpers', Path(__file__).with_name('wave5-http.py'))
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)
ROOT = '/api/v2/warehouse'


class Journey(helpers.Journey):
    def master(self, resource, body):
        if resource == 'locations':
            body['areaId'] = self.state['area']
        return self.call('POST', f'{ROOT}/{resource}', body=body, expected=201)

    def positions(self, sku):
        page = self.call('GET', f'{ROOT}/stock/{sku}/positions?size=100&availableOnly=true')
        check(page['totalElements'] == len(page['items']), 'Fixture positions exceed one page')
        return page['items']

    def quantities(self, sku, expected):
        totals = {}
        for row in self.positions(sku):
            check(row['status'] == 'AVAILABLE', 'Available stock contains another status')
            totals[row['locationId']] = totals.get(row['locationId'], 0) + int(row['quantityBase'])
        check(totals == expected, f'Unexpected physical distribution: {totals}')


def stock(journey, code, tracking, unit, quantity):
    sku = journey.master('skus', {'code': code, 'name': code, 'tracking': tracking, 'baseUnit': unit, 'inspectionRequired': False})
    line = {'skuId': sku['id'], 'quantityBase': quantity}
    if tracking == 'SERIAL':
        line['serials'] = [{'serial': code + '-SERIAL'}]
    else:
        line['lotCode'] = code + '-LOT'
    receipt = journey.remember(ROOT + '/receipts', {'warehouseId': journey.state['warehouse'], 'notes': code, 'lines': [line]}, expected=201)
    positions = journey.positions(sku['id'])
    check(len(positions) == 1 and positions[0]['quantityBase'] == quantity, 'Receipt did not credit exact available stock')
    journey.keep_read(f"{ROOT}/movements/{receipt['id']}")
    journey.keep_read(f"{ROOT}/movements/{receipt['id']}/lines")
    return {'sku': sku['id'], 'identity': positions[0]['stockIdentityId'], 'unit': unit, 'quantity': quantity}


def transfer(journey, item, quantity, key=None, expected=201):
    body = {'sourceWarehouseId': journey.state['warehouse'], 'warehouseId': journey.state['destination'],
            'lines': [{'stockIdentityId': item['identity'], 'quantityBase': quantity}], 'notes': 'Measured transfer'}
    if expected != 201:
        return journey.call('POST', ROOT + '/transfers', body=body, key=key, expected=expected)
    result = journey.remember(ROOT + '/transfers', body, expected=201)
    journey.keep_read(f"{ROOT}/movements/{result['id']}")
    journey.keep_read(f"{ROOT}/movements/{result['id']}/lines")
    return result


def count(journey, item, physical, serials=None):
    snapshot = journey.call('POST', ROOT + '/counts/snapshot', body={'skuId': item['sku'], 'locationId': journey.state['warehouse']})
    body = {'snapshotId': snapshot['id'], 'physicalBase': physical, 'reason': 'Signed physical count'}
    if serials is not None:
        body['serials'] = serials
    result = journey.remember(ROOT + '/counts', body, expected=201)
    check(result['physicalBase'] == physical, 'Count did not retain the observed quantity')
    check(int(result['differenceBase']) == int(physical) - int(snapshot['bookBase']), 'Count variance differs from its snapshot')
    journey.keep_read(f"{ROOT}/counts/{result['id']}")
    return result


def assert_final(journey):
    state = journey.state
    for name, amounts in {'cable': (60000, 40000), 'missing': (100000, 0), 'parts': (97, 0), 'serial': (1, 0)}.items():
        expected = {state['warehouse']: amounts[0]}
        if amounts[1]:
            expected[state['destination']] = amounts[1]
        journey.quantities(state['stocks'][name]['sku'], expected)


def before_restart():
    journey = Journey()
    admin = helpers.signup(journey, 'admin')
    current = journey.call('GET', ROOT + '/workflow')
    check(current['snapshot']['workflow'] == 'REFERENCE' and current['snapshot']['state'] == 'ENFORCED', 'New tenant did not start in the current workflow')
    journey.keep_read(ROOT + '/workflow')
    area = journey.call('POST', '/api/areas', body={'code': 'IMAGE', 'name': 'Image QA'}, expected=201)
    journey.state['area'] = area['id']
    journey.call('PUT', f"/api/users/{admin['id']}/access", body={'roleIds': admin['roleIds'], 'areaIds': [area['id']]})
    for name in ['warehouse', 'destination']:
        journey.state[name] = journey.master('locations', {'code': name.upper(), 'name': name.title(), 'kind': 'WAREHOUSE', 'issueEligible': True})['id']
    helpers.user(journey, 'viewer', {'warehouse.stock.view'}, [journey.state['warehouse']])
    helpers.signup(journey, 'foreign')
    journey.state['stocks'] = {
        'cable': stock(journey, 'CABLE', 'LOT', 'MM', '100000'),
        'missing': stock(journey, 'MISSING', 'LOT', 'MM', '100000'),
        'parts': stock(journey, 'PARTS', 'BULK', 'EA', '100'),
        'serial': stock(journey, 'ONU', 'SERIAL', 'EA', '1'),
    }
    cable, missing, parts, serial = (journey.state['stocks'][name] for name in ['cable', 'missing', 'parts', 'serial'])
    # Keep the old-route fence: new tenants must not regain retired writers just to pass QA.
    retired = journey.call('POST', '/api/v1/warehouse/receipts', body={'supplierId': str(uuid4()), 'externalReference': 'RETIRED',
        'sourceLocationId': str(uuid4()), 'inspectionLocationId': str(uuid4()), 'lines': [{'skuId': cable['sku'], 'quantityBase': '1', 'lotCode': 'OLD'}]}, expected=409)
    check(retired.get('code') == 'CUTOVER_REQUIRED', 'Retired receipt writer did not enforce the workflow fence')
    transfer(journey, cable, '100001', expected=409)
    moved = transfer(journey, cable, '40000')
    journey.quantities(cable['sku'], {journey.state['warehouse']: 60000, journey.state['destination']: 40000})
    for actor in ['foreign', 'viewer']:
        journey.call('GET', f"{ROOT}/movements/{moved['id']}", actor, expected=404)
    denied = {'warehouseId': journey.state['warehouse'], 'lines': [{'skuId': parts['sku'], 'quantityBase': '1'}]}
    journey.call('POST', ROOT + '/receipts', 'viewer', denied, expected=403)
    count(journey, parts, '97')
    count(journey, missing, '60000')
    count(journey, missing, '100000')
    count(journey, serial, '0', [])
    count(journey, serial, '1', [{'serial': 'ONU-SERIAL'}])
    stale = journey.call('POST', ROOT + '/counts/snapshot', body={'skuId': parts['sku'], 'locationId': journey.state['warehouse']})
    count(journey, parts, '96')
    journey.call('POST', ROOT + '/counts', body={'snapshotId': stale['id'], 'physicalBase': '95', 'reason': 'Stale count must fail'}, expected=409)
    count(journey, parts, '97')
    for item in journey.state['stocks'].values():
        journey.keep_read(f"{ROOT}/stock/{item['sku']}/positions?size=100")
        journey.keep_read(f"{ROOT}/stock/{item['sku']}/history?size=100")
    journey.keep_read(ROOT + '/movements?size=100')
    journey.keep_read(ROOT + '/counts?size=100')
    assert_final(journey)
    print('PASS: current tenant workflow, four stock kinds, exact transfer, count loss/recovery, stale count and access isolation')
    return journey.state


def after_restart(state):
    journey = Journey(state)
    for saved in state['replays']:
        actual = journey.call('POST', saved['path'], saved['actor'], saved['body'], saved['key'], saved['expected'])
        check(actual == saved['result'], 'Replay changed across image restart')
    for saved in state['reads']:
        check(journey.call('GET', saved['path'], saved['actor']) == saved['result'], 'Stored history changed across image restart')
    assert_final(journey)
    print('PASS: current workflow stock, documents and identical operation replays survive restart')
