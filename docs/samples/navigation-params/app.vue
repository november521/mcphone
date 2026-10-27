<template>
<column class="page">
  <text>便签列表</text>
  <text>本次打开 {{ opened }} 次详情</text>
  <text-input bind="draft" placeholder="列表页草稿，返回后保留" />
  <button v-for="note in notes" :key="note.id" @click="opened++; nav('detail', { id: note.id, title: note.title })">{{ note.title }}</button>
</column>
</template>
<script>
state = {
  opened: 0, draft: '',
  notes: [{ id: 1, title: '便签 1' }, { id: 2, title: '便签 2' }, { id: 3, title: '便签 3' }]
}
</script>
<style>
.page { gap: 4; padding: 4; }
</style>
