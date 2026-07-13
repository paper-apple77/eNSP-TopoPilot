<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import LogicFlow, { LineEdge, LineEdgeModel } from '@logicflow/core'
import '@logicflow/core/dist/index.css'

/**
 * 只读拓扑画布
 *
 * 功能：查看拓扑（缩放/平移/悬停）、接口标签显示。
 * 不支持编辑（添加/删除/连线/改名），数据源唯一 = .topo 文件。
 */

const props = defineProps<{ topologyJson: string }>()
const containerRef = ref<HTMLDivElement>()
let lf: LogicFlow | null = null
let rendered = false

// ===== 设备颜色 =====
const typeStyle: Record<string, { fill: string; stroke: string }> = {
  firewall: { fill: '#FFF3E0', stroke: '#E65100' },
  switch:   { fill: '#E3F2FD', stroke: '#1565C0' },
  router:   { fill: '#E8F5E9', stroke: '#2E7D32' },
  pc:       { fill: '#F5F5F5', stroke: '#9E9E9E' },
  server:   { fill: '#FCE4EC', stroke: '#C62828' },
  client:   { fill: '#F3E5F5', stroke: '#6A1B9A' },
}

// ===== JSON → LogicFlow =====
function toGraphData(jsonStr: string) {
  let topo: any
  try { topo = JSON.parse(jsonStr || '{}') } catch { topo = { devices: [], connections: [] } }
  const devices: any[] = topo.devices || []
  const connections: any[] = topo.connections || []

  const deviceConfigs: Record<string, string> = topo.deviceConfigs || {}

  const nameToId = new Map<string, string>()
  const nodes = devices.map((d: any) => {
    const id = d.id || d.name
    nameToId.set(d.name, id)
    const s = typeStyle[d.type] || { fill: '#FFF', stroke: '#999' }
    return {
      id, type: 'rect',
      x: (d.x || 300) * 1.3,
      y: (d.y || 200) * 1.3,
      text: `${d.name || '?'} | ${d.model || ''}`,
      properties: { name: d.name, model: d.model, type: d.type, interfaces: d.interfaces || [], config: deviceConfigs[d.name] || '' },
      style: { fill: s.fill, stroke: s.stroke, strokeWidth: 1.5, radius: 4, width: 130, height: 40 },
    }
  })

  const edges = connections.map((c: any, idx: number) => ({
    id: `e${idx}`,
    type: 'line-no-arrow',
    sourceNodeId: nameToId.get(c.fromDevice) || c.fromDevice,
    targetNodeId: nameToId.get(c.toDevice) || c.toDevice,
    text: `${c.fromDevice || ''}:${c.fromInterface || ''} — ${c.toDevice || ''}:${c.toInterface || ''}`,
    style: { stroke: '#999', strokeWidth: 1.2 },
    properties: { fromDevice: c.fromDevice, fromInterface: c.fromInterface, toDevice: c.toDevice, toInterface: c.toInterface },
  }))

  return { nodes, edges }
}

// ===== 自适应缩放 =====
function fitAll(nodes: any[]) {
  if (!lf || nodes.length === 0) return
  let mx = Infinity, my = Infinity, Mx = -Infinity, My = -Infinity
  nodes.forEach((n: any) => { if (n.x<mx) mx=n.x; if (n.y<my) my=n.y; if (n.x>Mx) Mx=n.x; if (n.y>My) My=n.y })
  lf.zoom(Math.min(
    (containerRef.value!.clientWidth || 1000) / (Mx - mx + 400),
    (containerRef.value!.clientHeight || 600) / (My - my + 250), 1.0
  ) * 0.85)
  lf.translateCenter()
}

// ===== 获取设备已连接接口 =====
function getUsedIfaces(deviceName: string): Set<string> {
  if (!lf) return new Set()
  const data = lf.getGraphData() as any
  const nodes = data.nodes || []
  const nodeMap = new Map<string, string>()
  for (const n of nodes) nodeMap.set(n.id, n.properties?.name || '')
  const used = new Set<string>()
  for (const e of (data.edges || [])) {
    if (nodeMap.get(e.sourceNodeId) === deviceName) used.add(e.properties?.fromInterface || '')
    if (nodeMap.get(e.targetNodeId) === deviceName) used.add(e.properties?.toInterface || '')
  }
  return used
}

/** 接口列表压缩显示 */
function formatIfaces(ifaces: string[]): string {
  if (ifaces.length === 0) return '无'
  const groups: Record<string, number[]> = {}
  for (const i of ifaces) {
    const m = i.match(/^([A-Za-z]+)([\d\/]+?)(\d+)$/)
    if (m) { const p = m[1] + m[2]; if (!groups[p]) groups[p] = []; groups[p].push(parseInt(m[3])) }
    else { if (!groups[i]) groups[i] = []; groups[i].push(-1) }
  }
  return Object.entries(groups).map(([pref, nums]) => {
    if (nums[0] === -1) return pref
    nums.sort((a,b) => a-b)
    let s = nums[0], e = nums[0], parts: string[] = []
    for (let i = 1; i < nums.length; i++) {
      if (nums[i] === e + 1) { e = nums[i]; continue }
      parts.push(pref + s + (e > s ? '~' + e : '')); s = nums[i]; e = nums[i]
    }
    parts.push(pref + s + (e > s ? '~' + e : ''))
    return parts.join(',')
  }).join(' | ')
}

// ===== 初始化 =====
function init() {
  if (!containerRef.value) return

  lf = new LogicFlow({
    container: containerRef.value,
    grid: { size: 20, visible: true, type: 'dot' },
    keyboard: { enabled: true },
    background: { backgroundColor: '#FAFAFA' },
    edgeTextStyle: { fontSize: 9, color: '#666' },
    style: { nodeText: { overflowMode: 'ellipsis', fontSize: 11, color: '#333' } },
    edgeTextEdit: false,
  })

  // 无箭头直线
  lf.register({
    type: 'line-no-arrow',
    model: LineEdgeModel,
    view: class extends LineEdge {
      getEndArrow() { return null as any }
      getStartArrow() { return null as any }
    },
  } as any)

  // 悬停显示接口
  const tooltip = createTooltip()
  let tooltipNode: any = null
  lf.on('node:mouseenter', ({ data }: any) => {
    tooltipNode = data
    const p = data.properties || {}
    const used = getUsedIfaces(p.name)
    let text = `${p.name} | ${p.model}\n已连接: ${formatIfaces([...used])}`
    // 有设备配置时显示前 3 行
    if (p.config) {
      const lines = p.config.trim().split('\n').filter((l: string) => l.trim()).slice(0, 5)
      text += `\n\n已配置命令预览:\n${lines.join('\n')}${lines.length >= 5 ? '\n...' : ''}`
    }
    tooltip.textContent = text
    tooltip.style.display = 'block'
  })
  containerRef.value!.addEventListener('mousemove', (e: MouseEvent) => {
    if (tooltipNode) { tooltip.style.left = (e.clientX + 14) + 'px'; tooltip.style.top = (e.clientY + 14) + 'px' }
  })
  lf.on('node:mouseleave', () => { tooltipNode = null; tooltip.style.display = 'none' })

  // 渲染
  const data = toGraphData(props.topologyJson)
  if (data.nodes.length > 0) {
    lf.render(data)
    rendered = true
    requestAnimationFrame(() => requestAnimationFrame(() => { lf?.resize(); fitAll(data.nodes) }))
  }
}

function createTooltip() {
  const t = document.createElement('div')
  t.style.cssText = 'position:fixed;background:#333;color:#fff;padding:10px 14px;border-radius:4px;font-size:12px;pointer-events:none;z-index:9999;display:none;white-space:pre-line;max-width:500px;line-height:1.4;font-family:monospace'
  document.body.appendChild(t)
  return t
}

function loadJson(jsonStr: string) {
  if (!lf) return
  const data = toGraphData(jsonStr)
  if (rendered) { lf.clearData(); lf.render(data) }
  else if (data.nodes.length > 0) { lf.render(data); rendered = true }
  requestAnimationFrame(() => requestAnimationFrame(() => { lf?.resize(); fitAll(data.nodes) }))
}

defineExpose({ loadJson })

onMounted(() => nextTick(init))
onUnmounted(() => { lf?.destroy(); lf = null; rendered = false })
watch(() => props.topologyJson, (v) => { if (v && lf) loadJson(v) })
</script>

<template>
  <div ref="containerRef" class="canvas-root"></div>
</template>

<style scoped>
.canvas-root { width: 100%; height: 100%; position: relative; }
</style>
