<manifest>
{"format":1,"id":"example:client_functions","version":"1.2.0","name":"名字检查器","author":"MCPhone"}
</manifest>
<template>
<column class="page">
  <text>客户端函数示例</text>
  <text-input id="name" bind="draft" placeholder="输入名字" max-length="32" />
  <button id="check" @click="checkName()">检查名字</button>
  <text>{{ result }}</text>
  <text>已检查 {{ checks }} 次</text>
  <row>
    <button id="save" @click="saveName()">保存名字</button>
    <button id="load" @click="loadName()">读取名字</button>
  </row>
  <button @click="reset()">清空输入</button>
</column>
</template>
<script>
state = { draft: '', result: '输入名字后点击检查', checks: 0 }

function onInit() {
  state.draft = ctx.store.getString('name', '').slice(0, 32)
  if (state.draft.length > 0) {
    state.result = '已恢复保存的名字'
  }
}

function checkName() {
  var name = state.draft.trim()
  state.checks++
  if (name.length === 0) {
    state.result = '请输入名字'
  } else {
    state.result = '你好，' + name
  }
}

function reset() {
  state.draft = ''
  state.result = '输入名字后点击检查'
  state.checks = 0
}

function saveName() {
  var name = state.draft.trim()
  ctx.store.setString('name', name)
  state.draft = name
  state.result = '名字已保存'
}

function loadName() {
  state.draft = ctx.store.getString('name', '')
  state.result = state.draft.length === 0 ? '本地没有保存的名字' : '已读取保存的名字'
}
</script>
<style>
.page { gap: 6; padding: 6; }
</style>
