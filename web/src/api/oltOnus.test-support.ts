import type { OltDeviceOnu, OltOnusSnapshot } from './oltOnus'

export const deviceOnu: OltDeviceOnu = {
  index: '4194561',
  ontId: '1/1:7',
  name: 'Cabinet Utara',
  serialNumber: 'HWTC00112233',
  state: 'Enabled',
  runningState: 'ONLINE',
  configState: 'Success',
  deviceType: 'HG8245H',
  rxPowerDbm: -21.75,
  lastUpTime: '2001-01-01 08:05:06 (OLT clock)',
  lastDownTime: '31/12/2000 23:59:59',
  lastDownCause: 'Dying gasp',
}

export const unsupportedOnu: OltDeviceOnu = {
  index: '4194562',
  ontId: null,
  name: null,
  serialNumber: 'ZTEG44556677',
  state: null,
  runningState: null,
  configState: null,
  deviceType: null,
  rxPowerDbm: null,
  lastUpTime: null,
  lastDownTime: null,
  lastDownCause: null,
}

export function oltOnusSnapshot(overrides: Partial<OltOnusSnapshot> = {}): OltOnusSnapshot {
  return {
    oltId: 'olt-a',
    oltCode: 'OLT-A',
    vendor: 'HUAWEI',
    systemDescription: 'Test OLT firmware',
    readAt: '2026-09-08T04:05:06Z',
    onus: [deviceOnu],
    warnings: [],
    ...overrides,
  }
}

export function deferred<T>() {
  let resolve: (value: T) => void = () => { throw new Error('Deferred not initialized') }
  let reject: (reason: unknown) => void = () => { throw new Error('Deferred not initialized') }
  const promise = new Promise<T>((onResolve, onReject) => {
    resolve = onResolve
    reject = onReject
  })
  return { promise, resolve, reject }
}
