<template>
<column class="page">
  <text :text="title" />
  <textarea id="note" class="editor" bind="note" max-length="1024" placeholder="输入后立即更新预览" @input="updatePreview()" />
  <text>输入长度：{{ length }}</text>
  <text>预览：{{ preview }}</text>
  <row class="actions">
    <button id="back" @click="back()">返回列表</button>
    <button id="save" @click="save()">保存</button>
  </row>
  <text :text="status" />
</column>
</template>
<script>
state = { id: 0, title: '', note: '', preview: '', length: 0, status: '' }

function onInit() {
  state.id = ctx.params.id
  state.title = ctx.params.title
  state.note = ctx.store.getString('note_' + state.id, '').slice(0, 1024)
  updatePreview()
}

function updatePreview() {
  state.length = state.note.length
  var text = state.note.split('\n').join(' ')
  var end = Math.min(24, text.length)
  var last = end ? text.charCodeAt(end - 1) : 0
  if (last >= 0xD800 && last <= 0xDBFF) end--
  state.preview = text.slice(0, end)
}

function save() {
  ctx.store.setString('note_' + state.id, state.note)
  state.status = '已保存，返回列表查看更新。'
}
</script>
<style>
.page { width: fill; height: fill; gap: 4; padding: 4; }
.editor { height: 0; grow: 1; }
.actions { gap: 4; }
</style>
