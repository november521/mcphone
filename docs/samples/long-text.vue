<manifest>
{"format":1,"id":"example:long_text","version":"1.0.0","name":"长文阅读器","author":"MCPhone"}
</manifest>
<template>
<column class="page">
  <text>长文阅读器</text>
  <scroll id="viewport" class="viewport">
    <text id="article" :text="body" />
  </scroll>
  <text>{{ status }}</text>
  <row>
    <button id="save" @click="saveArticle()">保存正文</button>
    <button id="load" @click="loadArticle()">读取正文</button>
  </row>
  <button @click="showShort()">显示短文</button>
</column>
</template>
<script>
state = { body: "长文阅读器：开始标记\n\n第1段：这是一段保存在客户端页面中的长正文。它可以自动换行，也可以放在滚动区域里阅读。中文、英文和表情 🦎 都会保留，阅读时不需要把文章拆成许多短字段。\n\n第2段：这是一段保存在客户端页面中的长正文。它可以自动换行，也可以放在滚动区域里阅读。中文、英文和表情 🦎 都会保留，阅读时不需要把文章拆成许多短字段。\n\n第3段：这是一段保存在客户端页面中的长正文。它可以自动换行，也可以放在滚动区域里阅读。中文、英文和表情 🦎 都会保留，阅读时不需要把文章拆成许多短字段。\n\n第4段：这是一段保存在客户端页面中的长正文。它可以自动换行，也可以放在滚动区域里阅读。中文、英文和表情 🦎 都会保留，阅读时不需要把文章拆成许多短字段。\n\n第5段：这是一段保存在客户端页面中的长正文。它可以自动换行，也可以放在滚动区域里阅读。中文、英文和表情 🦎 都会保留，阅读时不需要把文章拆成许多短字段。\n\n第6段：这是一段保存在客户端页面中的长正文。它可以自动换行，也可以放在滚动区域里阅读。中文、英文和表情 🦎 都会保留，阅读时不需要把文章拆成许多短字段。\n\n第7段：这是一段保存在客户端页面中的长正文。它可以自动换行，也可以放在滚动区域里阅读。中文、英文和表情 🦎 都会保留，阅读时不需要把文章拆成许多短字段。\n\n第8段：这是一段保存在客户端页面中的长正文。它可以自动换行，也可以放在滚动区域里阅读。中文、英文和表情 🦎 都会保留，阅读时不需要把文章拆成许多短字段。\n\n第9段：这是一段保存在客户端页面中的长正文。它可以自动换行，也可以放在滚动区域里阅读。中文、英文和表情 🦎 都会保留，阅读时不需要把文章拆成许多短字段。\n\nEnd of article：末尾标记。", status: '上下滚动可阅读完整正文' }
function onInit() {
  state.body = ctx.store.getString('article', state.body)
}
function saveArticle() {
  ctx.store.setString('article', state.body)
  state.status = '正文已保存'
}
function loadArticle() {
  state.body = ctx.store.getString('article', '尚未保存正文')
  state.status = '已读取保存的正文'
}
function showShort() {
  state.body = '这是短文。保存过长文后，可以点击读取正文，恢复并滚动到末尾。'
  state.status = '当前显示短文，已保存的数据仍保留'
}
</script>
<style>
.page { gap: 4; padding: 4; }
.viewport { height: 70; }
</style>
