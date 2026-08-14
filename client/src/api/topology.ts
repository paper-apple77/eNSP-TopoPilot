import request from './request'

/** 拓扑持久化接口（MySQL tb_topology） */

export interface TopologySaveData {
  id?: number | null
  name: string
  topologyJson: string
  sourceType?: string
}

/** 我的拓扑列表（按更新时间倒序，不含完整 JSON） */
export function listTopology() {
  return request.get('/topology/list')
}

/** 拓扑详情（含完整 topologyJson，恢复画布用） */
export function getTopology(id: number) {
  return request.get(`/topology/${id}`)
}

/** 保存拓扑：id 为空新建，否则覆盖保存 */
export function saveTopology(data: TopologySaveData) {
  return request.post('/topology/save', data)
}

/** 删除拓扑（逻辑删除，仅限本人） */
export function deleteTopology(id: number) {
  return request.delete(`/topology/${id}`)
}
