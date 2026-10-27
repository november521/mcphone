<template>
<column class="page">
  <text>便签列表 · 恢复刷新 {{ refreshes }} 次</text>
  <row class="search">
    <text-input id="query" class="query" bind="query" max-length="32" placeholder="标题或正文" />
    <button id="filter" @click="applyFilter()">筛选</button>
  </row>
  <text>当前筛选：{{ activeQuery }}</text>
  <scroll id="notes" class="notes">
    <column class="items">
      <column v-for="note in notes" :key="note.id">
        <column v-if="note.visible">
          <button id="open" @click="nav('detail', { id: note.id, title: note.title })">{{ note.title }}</button>
          <text :text="note.summary" />
        </column>
      </column>
    </column>
  </scroll>
</column>
</template>
<script>
state = {
  query: '', activeQuery: '', refreshes: 0,
  notes: [
    { id: 1, title: '便签 1', summary: '', visible: true },
    { id: 2, title: '便签 2', summary: '', visible: true },
    { id: 3, title: '便签 3', summary: '', visible: true }
  ]
}

function onInit() {
  refresh()
}

function onResume() {
  refresh()
  state.refreshes++
}

function applyFilter() {
  state.activeQuery = state.query.trim()
  refresh()
}

function refresh() {
  for (var i = 0; i < state.notes.length; i++) {
    var note = state.notes[i]
    var body = ctx.store.getString('note_' + note.id, '')
    note.summary = body.length ? excerpt(body) : '（暂无正文）'
    note.visible = state.activeQuery === '' || note.title.indexOf(state.activeQuery) >= 0 || body.indexOf(state.activeQuery) >= 0
  }
}

function excerpt(text) {
  var end = Math.min(32, text.length)
  var last = end ? text.charCodeAt(end - 1) : 0
  if (last >= 0xD800 && last <= 0xDBFF) end--
  return text.slice(0, end)
}
</script>
<style>
.page { height: fill; gap: 4; padding: 4; }
.search { gap: 4; }
.query { width: 0; grow: 1; }
.notes { height: 0; grow: 1; }
.items { gap: 6; }
</style>
