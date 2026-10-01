"""Register demo ONUs through the current warehouse/field APIs, with resumable commands."""
import json
import os
from pathlib import Path
import secrets
import urllib.error
import urllib.request
import uuid

WH = '/api/v1/warehouse'


class WarehouseSeed:
    def __init__(self, api):
        self.api = api
        self.tenant = os.environ.get('FTTH_SIM_TENANT', 'demo')
        directory = Path(os.environ.get('FTTH_SIM_STATE_DIR', '.omo/lab-seed-state')) / self.tenant
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.path = directory / 'warehouse.json'
        self.state = json.loads(self.path.read_text()) if self.path.exists() else {}
        self.tech_token = None
        self.ready = False

    def save(self):
        temporary = self.path.with_suffix('.tmp')
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(self.state, stream)
        temporary.replace(self.path)

    def request(self, method, path, body=None, key=None, token=None):
        request = urllib.request.Request(self.api.base + path, method=method,
            data=json.dumps(body).encode() if body is not None else None,
            headers={'Content-Type': 'application/json'})
        credential = token or self.api.token
        if credential:
            request.add_header('Authorization', 'Bearer ' + credential)
        if key:
            request.add_header('Idempotency-Key', key)
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                code = json.loads(raw).get('code', 'HTTP_ERROR')
            except ValueError:
                code = 'HTTP_ERROR'
            raise RuntimeError(f'{method} {path}: HTTP {error.code} ({code})') from None

    def step(self, name, method, path, body=None, technician=False):
        if name not in self.state:
            self.state[name] = {'method': method, 'path': path, 'body': body}
            self.save()
        command = self.state[name]
        if 'result' not in command:
            key = str(uuid.uuid5(uuid.NAMESPACE_URL, self.api.base + '/' + self.tenant + '/' + name))
            command['result'] = self.request(command['method'], command['path'], command['body'], key,
                                            self.tech_token if technician else None)
            self.save()
        return command['result']

    @staticmethod
    def rows(body):
        return body if isinstance(body, list) else body.get('items', body.get('content', []))

    def master(self, resource, code, **fields):
        path = WH + '/' + resource
        for row in self.rows(self.request('GET', path + '?size=100')):
            if row['code'] == code:
                return row['id']
        body = {'code': code, **fields}
        if resource == 'locations':
            body['areaId'] = self.area
        return self.step('master-' + code, 'POST', path, body)['id']

    def setup(self):
        if self.ready:
            return
        areas = self.rows(self.request('GET', '/api/areas'))
        area = next((item for item in areas if item['code'] == 'SIM'), None)
        self.area = (area or self.step('area', 'POST', '/api/areas', {'code': 'SIM', 'name': 'Area simulator'}))['id']
        me = self.request('GET', '/api/me')
        self.step('admin-area', 'PUT', '/api/users/' + me['id'] + '/access',
                  {'roleIds': me['roleIds'], 'areaIds': list(set(me.get('areaIds', []) + [self.area]))})
        roles = self.rows(self.request('GET', '/api/roles'))
        role = next(item['id'] for item in roles if item['name'] == 'Teknisi')
        email = 'technician@' + os.environ.get('FTTH_SIM_DOMAIN', 'demo.ftth')
        if 'technician-password' not in self.state:
            self.state['technician-password'] = secrets.token_hex(24)
            self.save()
        password = self.state['technician-password']
        users = self.rows(self.request('GET', '/api/users?size=100'))
        user = next((item for item in users if item['email'] == email), None)
        self.tech = (user or self.step('technician', 'POST', '/api/users', {
            'name': 'Teknisi Simulator', 'email': email, 'password': password,
            'roleIds': list(set(me['roleIds'] + [role])), 'areaIds': [self.area],
        }))['id']
        result = self.request('POST', '/api/auth/login', {'tenantSlug': self.tenant, 'email': email, 'password': password})
        self.tech_token = result['accessToken']
        self.supplier = self.master('suppliers', 'SIM-SUP', name='Pemasok demo')
        self.source = self.master('locations', 'RECEIPT_SOURCE', name='Penerimaan demo', kind='TRANSIT')
        self.inspection = self.master('locations', 'SIM-INSPECT', name='Pemeriksaan demo', kind='QUARANTINE')
        warehouse = self.master('locations', 'SIM-WH', name='Gudang simulator', kind='WAREHOUSE')
        self.bin = self.master('locations', 'SIM-BIN', name='Rak ONT', kind='BIN', parentLocationId=warehouse, issueEligible=True)
        transit = self.master('locations', 'WO_TRANSIT', name='Pengiriman teknisi', kind='TRANSIT')
        field = self.master('locations', 'FIELD_STOCK', name='Stok teknisi simulator', kind='TECHNICIAN', custodianId=self.tech)
        self.master('locations', 'CUSTOMER_INSTALLED', name='Perangkat pelanggan', kind='CUSTOMER_SITE')
        for location in (self.bin, transit, field, self.inspection):
            self.step('scope-' + location, 'PUT', f'{WH}/settings/scopes/{self.tech}/{location}', {'expectedRevision': 0, 'active': True})
        self.sku = self.master('skus', 'SIM-ONT', name='ONT simulator', category='ONU', tracking='SERIAL', baseUnit='EA',
                               inspectionRequired=False, allowedOwnershipModes=['LOAN', 'SALE'])
        self.ready = True

    def install(self, customer, serial, topology=None):
        onus = self.request('GET', f'/api/customers/{customer}/onus')
        existing = next((onu for onu in onus if onu['serialNumber'] == serial), None)
        if existing:
            return existing['id']
        self.setup()
        prefix = 'onu-' + serial + '-'
        def step(name, method, path, body=None, technician=False):
            return self.step(prefix + name, method, path, body, technician)
        receipt = step('receipt', 'POST', WH + '/receipts', {
            'supplierId': self.supplier, 'externalReference': 'SIM-' + serial,
            'sourceLocationId': self.source, 'inspectionLocationId': self.inspection,
            'lines': [{'skuId': self.sku, 'quantityBase': '1', 'serials': [{'serial': serial}],
                       'cost': {'totalMinor': '250000', 'currency': 'IDR'}}],
        })['id']
        step('receive', 'POST', f'{WH}/receipts/{receipt}/receive', {'expectedRevision': 0})
        line = self.request('GET', f'{WH}/receipts/{receipt}')['lines'][0]
        identity = line['pieces'][0]['stockIdentityId']
        step('putaway', 'POST', f'{WH}/receipts/{receipt}/putaway', {
            'expectedRevision': 1, 'destinationLocationId': self.bin,
            'lines': [{'lineId': line['id'], 'stockIdentityId': identity, 'quantityBase': '1', 'baseUnit': 'EA'}],
        })
        work = step('work', 'POST', '/api/work-orders', {'type': 'PSB', 'title': 'Instalasi demo ' + serial,
                    'areaId': self.area, 'customerId': customer})['id']
        step('assign', 'POST', f'/api/work-orders/{work}/assign', {'technicianIds': [self.tech]})
        root = f'/api/work-orders/{work}/materials'
        def summary():
            return self.request('GET', root)
        rev = summary()['revisions']
        step('plan', 'PUT', root + '/plan', {'expectedRevision': 0, 'workOrderRevision': rev['workOrderRevision'],
             'materialMode': 'MATERIAL_REQUIRED', 'lines': [{'skuId': self.sku, 'quantityBase': '1', 'baseUnit': 'EA', 'continuousCut': False}]})
        for action in ('submit-request', 'reserve'):
            rev = summary()['revisions']
            step(action, 'POST', root + '/' + action, {'expectedRevision': rev['planRevision'], 'workOrderRevision': rev['workOrderRevision']})
        state = summary()
        allocations = self.request('GET', f'{WH}/material-requests/allocations/{work}')
        selected = [row for row in allocations if row['stockIdentityId'] == identity and row['reservedUnpickedBase'] != '0']
        issue = step('pick', 'POST', root + '/pick', {'expectedRevision': state['revisions']['planRevision'],
            'workOrderRevision': state['revisions']['workOrderRevision'], 'demandRevision': state['demandRevision'],
            'lines': [{'reservationId': row['reservationId'], 'expectedRevision': row['reservationRevision'],
                       'stockIdentityId': identity, 'stockRevision': 0, 'quantityBase': '1', 'baseUnit': 'EA'} for row in selected]})
        state = summary()
        issued = step('dispatch', 'POST', root + '/dispatch', {'issueId': issue['issueId'], 'expectedRevision': issue['revision'],
            'workOrderRevision': state['revisions']['workOrderRevision'], 'planRevision': state['revisions']['planRevision'],
            'demandRevision': state['demandRevision'], 'partial': False, 'reason': 'Pengiriman perangkat demo'})
        issued_line = issued['lines'][0]
        step('acknowledge', 'POST', root + '/acknowledge', {'issueId': issued['issueId'], 'expectedRevision': issued['revision'],
            'workOrderRevision': issued['workOrderRevision'], 'evidenceReference': 'Penerimaan perangkat simulator',
            'lines': [{'issueLineId': issued_line['id'], 'stockIdentityId': identity, 'baseUnit': 'EA', 'acceptedBase': '1', 'serial': serial}]}, True)
        rev = summary()['revisions']
        authorization = step('authorize', 'POST', f'/api/work-orders/{work}/assets/authorize', {
            'expectedRevision': rev['workOrderRevision'], 'assetId': identity, 'issueLineId': issued_line['id'], 'purpose': 'INSTALL'}, True)
        installed = step('install', 'POST', f'/api/customers/{customer}/assets/install', {
            'authorizationId': authorization['authorizationId'], 'expectedRevision': 0, 'topology': topology}, True)
        return installed['onuId']
