<template>
<column class="page">
  <text>搜索防抖 · 已查询 {{ searches }} 次</text>
  <text-input id="query" bind="query" max-length="32" placeholder="输入 2，停下后查询" @input="queueSearch()" />
  <text :text="status" />
  <text>已应用：{{ applied }}</text>
  <row class="actions">
    <button id="cancel" @click="cancelSearch()">取消等待</button>
    <button id="away" @click="nav('away')">切页测试</button>
  </row>
  <button id="long" @click="queueLong()">安排 3 秒后查询</button>
  <scroll id="results" class="results">
    <column>
      <column v-for="note in notes" :key="note.id">
        <text v-if="note.visible" :text="note.title" />
      </column>
    </column>
  </scroll>
</column>
</template>
<script>
state = {
  query: '', applied: '', job: 0, searches: 0, status: '停下输入 300 毫秒后查询。',
  notes: [
    { id: 1, title: '便签 1', visible: true },
    { id: 2, title: '便签 2', visible: true },
    { id: 3, title: '便签 3', visible: true }
  ]
}

function queueSearch() {
  ctx.timer.cancel(state.job)
  state.job = ctx.timer.after(300, 'search')
  state.status = '等待输入结束…'
}

function queueLong() {
  ctx.timer.cancel(state.job)
  state.job = ctx.timer.after(3000, 'search')
  state.status = '3 秒后查询；切页会取消。'
}

function cancelSearch() {
  ctx.timer.cancel(state.job)
  state.job = 0
  state.status = '已取消等待，输入草稿保留。'
}

function search() {
  state.applied = state.query.trim()
  for (var i = 0; i < state.notes.length; i++) {
    state.notes[i].visible = state.applied === '' || state.notes[i].title.indexOf(state.applied) >= 0
  }
  state.searches++
  state.job = 0
  state.status = '查询完成；继续输入可再次查询。'
}

function onResume() {
  state.job = 0
  state.status = '旧等待已清理，修改文字再试。'
}
</script>
<style>
.page { width: fill; height: fill; gap: 3; padding: 4; }
.actions { gap: 4; }
.results { height: 0; grow: 1; }
</style>
