import request from './request'

/** 设备连接相关 */
export function scanDevices(start = 2000, end = 2050) {
  return request.get('/chat/devices/scan', { params: { start, end } })
}
export function connectAllDevices(topologyJson?: string) {
  return request.post('/chat/devices/connect-all', topologyJson ? { topologyJson } : {})
}
export function disconnectDevice(name: string) {
  return request.post('/chat/devices/disconnect', { deviceName: name })
}
export function getConnectedDevices() {
  return request.get('/chat/devices/connected')
}

/** 导出 .topo */
export function exportTopo(topology: any, projectName: string) {
  return request.post('/chat/export-topo', { topology, projectName })
}
