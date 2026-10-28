<manifest>
{"format":1,"id":"example:textarea","version":"1.1.0","name":"本地便签","author":"MCPhone"}
</manifest>
<template>
<column class="page">
  <text>本地便签</text>
  <textarea id="note" class="editor" bind="note" placeholder="在这里写下多段文字…" max-length="1024" />
  <row class="actions">
    <button id="save" @click="save()">保存</button>
    <button id="clear" @click="clear()">清空编辑区</button>
  </row>
  <button id="delete" @click="deleteNote()">删除已保存便签</button>
  <text :text="status" />
</column>
</template>
<script>
state = { note: '', status: '点击保存后，重开 App 可恢复。' }

function onInit() {
  state.note = ctx.store.getString('note', '').slice(0, 1024)
}

function save() {
  ctx.store.setString('note', state.note)
  state.status = '上次保存成功。'
}

function clear() {
  state.note = ''
  state.status = '编辑区已清空；保存后才覆盖记录。'
}

function deleteNote() {
  ctx.store.remove('note')
  state.note = ''
  state.status = '已删除保存的便签，重开不会恢复。'
}
</script>
<style>
.page { height: fill; gap: 4; padding: 4; }
.editor { height: 0; grow: 1; }
.actions { gap: 4; }
</style>
