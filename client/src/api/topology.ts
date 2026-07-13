import request from './request'

/** 拓扑相关 API */

export function updateTopology(id: number, name: string, topologyJson: string) {
  return request.put(`/topology/${id}`, { name, topologyJson })
}

export function deleteTopology(id: number) {
  return request.delete(`/topology/${id}`)
}

export function getTopology(id: number) {
  return request.get(`/topology/${id}`)
}

export function listTopologies() {
  return request.get('/topology/list')
}

/**
 * 上传 .topo 文件导入拓扑
 * Content-Type: multipart/form-data（axios 自动设置）
 */
export function importTopoFile(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/topology/import/topo', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function importZipFile(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/topology/import/zip', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
