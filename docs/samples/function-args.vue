<manifest>
{"format":1,"id":"example:function_args","version":"1.0.0","name":"待办清单","author":"MCPhone"}
</manifest>
<template>
<column class="page">
  <text>待办清单</text>
  <button v-for="(task, index) in tasks" :key="task.id" @click="toggleTask(index)">{{ task.done ? '完成：' : '待办：' }}{{ task.title }}</button>
  <text>完成 {{ done }} / {{ tasks.length }}</text>
  <button id="reset" @click="setAll(false)">全部重置</button>
</column>
</template>
<script>
state = { tasks: [{ id: 1, title: '读书', done: false }, { id: 2, title: '整理', done: false }, { id: 3, title: '散步', done: false }], done: 0 }

function toggleTask(index) {
  state.tasks[index].done = !state.tasks[index].done
  recount()
}

function setAll(value) {
  for (var i = 0; i < state.tasks.length; i++) {
    state.tasks[i].done = value
  }
  recount()
}

function recount() {
  var count = 0
  for (var i = 0; i < state.tasks.length; i++) {
    if (state.tasks[i].done) count++
  }
  state.done = count
}
</script>
<style>
.page { gap: 4; padding: 4; }
</style>
