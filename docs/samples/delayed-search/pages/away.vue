<template>
<column class="page">
  <text>已离开搜索页。</text>
  <text>旧页的延迟任务已取消。等几秒再返回，查询次数应保持不变。</text>
  <button id="back" @click="back()">返回搜索页</button>
</column>
</template>
<script>
state = {}
</script>
<style>
.page { width: fill; height: fill; gap: 4; padding: 4; }
</style>
