import argparse
import hashlib
import base64
import http.client
import json
import secrets
import shlex
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote, urlsplit
from urllib.request import parse_http_list, parse_keqv_list

parser = argparse.ArgumentParser(description='Verify CWMP authentication, Inform persistence and cleanup')
parser.add_argument('--env-file', required=True, type=Path)
parser.add_argument('--url', required=True)
parser.add_argument('--nbi', required=True)
args = parser.parse_args()
settings = {}
for line in args.env_file.read_text().splitlines():
    if line.strip() and not line.lstrip().startswith('#') and '=' in line:
        key, value = line.split('=', 1)
        settings[key] = shlex.split(value)[0] if value else ''
url = urlsplit(args.url)
assert url.path in ('', '/'), 'Probe requires the ACS root URL'
connect = lambda: (http.client.HTTPSConnection if url.scheme == 'https' else http.client.HTTPConnection)(url.hostname, url.port, timeout=15)
username = settings['FTTH_CPE_ONT_ACS_USERNAME']
password = settings['FTTH_CPE_ONT_ACS_PASSWORD']
serial = 'FTTHCOMMISSION' + secrets.token_hex(6)
device_id = 'FFFFFF-NetworkProbe-' + serial
now = datetime.now(timezone.utc).isoformat()
body = f'''<?xml version="1.0"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" xmlns:cwmp="urn:dslforum-org:cwmp-1-0" xmlns:soapenc="http://schemas.xmlsoap.org/soap/encoding/" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<soap:Header><cwmp:ID soap:mustUnderstand="1">commissioning</cwmp:ID></soap:Header>
<soap:Body><cwmp:Inform><DeviceId><Manufacturer>FTTH</Manufacturer><OUI>FFFFFF</OUI><ProductClass>NetworkProbe</ProductClass><SerialNumber>{serial}</SerialNumber></DeviceId>
<Event soapenc:arrayType="cwmp:EventStruct[1]"><EventStruct><EventCode>1 BOOT</EventCode><CommandKey/></EventStruct></Event>
<MaxEnvelopes>1</MaxEnvelopes><CurrentTime>{now}</CurrentTime><RetryCount>0</RetryCount><ParameterList soapenc:arrayType="cwmp:ParameterValueStruct[0]"/>
</cwmp:Inform></soap:Body></soap:Envelope>'''.encode()

def post(connection, payload, extra=None):
    headers = {'Content-Type': 'text/xml; charset=utf-8', 'SOAPAction': '""'}
    headers.update(extra or {})
    connection.request('POST', '/', body=payload, headers=headers)
    response = connection.getresponse()
    return response.status, dict((k.lower(), v) for k, v in response.getheaders()), response.read()

def authenticate(connection, supplied_password):
    status, headers, _ = post(connection, body)
    assert status == 401, f'Unauthenticated Inform returned {status}, expected 401'
    challenge = headers['www-authenticate']
    if challenge.startswith('Basic '):
        assert url.scheme == 'https', 'Basic authentication must use HTTPS'
        auth = 'Basic ' + base64.b64encode(f'{username}:{supplied_password}'.encode()).decode()
        return post(connection, body, {'Authorization': auth})
    assert challenge.startswith('Digest '), 'Expected Digest authentication'
    values = parse_keqv_list(parse_http_list(challenge[7:]))
    assert values.get('algorithm', 'MD5') == 'MD5'
    cnonce = secrets.token_hex(16)
    digest = lambda value: hashlib.md5(value.encode()).hexdigest()
    ha1 = digest(f"{username}:{values['realm']}:{supplied_password}")
    ha2 = digest('POST:/')
    response = digest(f"{ha1}:{values['nonce']}:00000001:{cnonce}:auth:{ha2}")
    auth = f'Digest username="{username}", realm="{values["realm"]}", nonce="{values["nonce"]}", uri="/", response="{response}", qop=auth, nc=00000001, cnonce="{cnonce}"'
    if 'opaque' in values:
        auth += ', opaque="' + values['opaque'] + '"'
    return post(connection, body, {'Authorization': auth, 'Cookie': headers.get('set-cookie', '').split(';')[0]})

nbi_url = urlsplit(args.nbi)
nbi = http.client.HTTPConnection(nbi_url.hostname, nbi_url.port, timeout=15)
try:
    invalid = connect()
    try:
        status, _, _ = authenticate(invalid, secrets.token_hex(32))
        assert status == 401, f'Incorrect credentials returned {status}, expected 401'
        print('PASS: unauthenticated and incorrect-password Inform rejected')
    finally:
        invalid.close()
    valid = connect()
    try:
        status, headers, response = authenticate(valid, password)
        assert status == 200 and b'InformResponse' in response, f'Valid Inform failed with HTTP {status}'
        cookie = headers.get('set-cookie', '').split(';')[0]
        status, _, response = post(valid, b'', {'Cookie': cookie})
        assert status == 204, f'Expected completed empty session, got HTTP {status}'
        print('PASS: authenticated ONT Inform accepted and CWMP session completed')
    finally:
        valid.close()
    query = quote(json.dumps({'_id': device_id}))
    for attempt in range(20):
        nbi.request('GET', '/devices?query=' + query + '&projection=_id')
        result = nbi.getresponse()
        persisted = result.status == 200 and len(json.loads(result.read())) == 1
        if persisted:
            break
        time.sleep(0.25)
    assert persisted, 'Authenticated device was not persisted'
    print('PASS: authenticated device persisted in ACS')
finally:
    for attempt in range(20):
        nbi.request('DELETE', '/devices/' + quote(device_id, safe=''))
        response = nbi.getresponse()
        response.read()
        if response.status in (200, 404):
            break
        time.sleep(0.25)
    assert response.status in (200, 404), f'Probe cleanup failed: HTTP {response.status}'
    nbi.close()
    print('PASS: temporary ACS device removed')
