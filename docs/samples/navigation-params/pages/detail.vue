<template>
<column class="page">
  <text :text="title" />
  <textarea id="note" class="editor" bind="note" max-length="1024" placeholder="每条便签独立保存" />
  <row class="actions">
    <button id="back" @click="back()">返回</button>
    <button id="save" @click="save()">保存</button>
    <button id="next" @click="nav('detail', { id: nextId, title: nextTitle })">下一条</button>
  </row>
  <text :text="status" />
</column>
</template>
<script>
state = { id: 0, title: '', note: '', nextId: 0, nextTitle: '', status: '' }

function onInit() {
  state.id = typeof ctx.params.id === 'number' ? ctx.params.id : 1
  state.title = typeof ctx.params.title === 'string' ? ctx.params.title : '便签'
  state.note = ctx.store.getString('note_' + state.id, '这是第 ' + state.id + ' 条便签。').slice(0, 1024)
  state.nextId = state.id === 3 ? 1 : state.id + 1
  state.nextTitle = '便签 ' + state.nextId
}

function save() {
  ctx.store.setString('note_' + state.id, state.note)
  state.status = '上次保存成功。'
}
</script>
<style>
.page { height: fill; gap: 4; padding: 4; }
.editor { height: 0; grow: 1; }
.actions { gap: 4; }
</style>
