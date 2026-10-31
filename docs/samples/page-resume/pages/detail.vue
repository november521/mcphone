<template>
<column class="page">
  <text :text="title" />
  <textarea id="note" class="editor" bind="note" max-length="1024" placeholder="写下便签正文" />
  <row class="actions">
    <button id="back" @click="back()">返回列表</button>
    <button id="save" @click="save()">保存</button>
  </row>
  <button id="delete" @click="deleteNote()">删除已保存便签</button>
  <text :text="status" />
</column>
</template>
<script>
state = { id: 0, title: '', note: '', status: '' }

function onInit() {
  state.id = ctx.params.id
  state.title = ctx.params.title
  state.note = ctx.store.getString('note_' + state.id, '').slice(0, 1024)
}

function save() {
  ctx.store.setString('note_' + state.id, state.note)
  state.status = '已保存，返回列表查看更新。'
}

function deleteNote() {
  ctx.store.remove('note_' + state.id)
  state.note = ''
  state.status = '已删除，返回列表查看更新。'
}
</script>
<style>
.page { height: fill; gap: 4; padding: 4; }
.editor { height: 0; grow: 1; }
.actions { gap: 4; }
</style>
