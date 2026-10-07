<manifest>
{"format":1,"id":"localtest:update_demo","version":"1.0.0","name":"本地更新测试","author":"tester"}
</manifest>
<template>
  <column class="page">
    <text>当前版本：1.0.0</text>
    <text>旧版页面</text>
    <button @click="count++">点击计数：{{ count }}</button>
  </column>
</template>
<script>
state = { count: 0 }
</script>
<style>
.page { padding: 6; gap: 4; }
</style>
